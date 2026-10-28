const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../..');
const dir = path.join(root, 'build/verification/font-browser');
const fixtureDir = path.join(__dirname, 'fixtures');
const js = require('./reader-source.cjs')();
const structural = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.css'), 'utf8');
const css = (size, baseline) => fs.readFileSync(
  path.join(fixtureDir, baseline ? 'baseline' : '', 'theme-' + size + '.css'), 'utf8');
fs.mkdirSync(dir, { recursive: true });

// One CSS pixel covers integer scroll-coordinate rounding. Do not allow a whole
// line/page of drift: the same character must survive every 14 -> 30 -> 14 change.
const pixelTolerance = 1;

async function settle(page) {
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
}

// Independent DOM oracle: inspect the actual glyph rectangles, not the reader's
// offset/page conversion. All anchors in this fixture are in its first paragraph.
async function firstVisibleCharacter(page, mode) {
  return page.evaluate(mode => {
    const p = document.querySelector(window.__fontRoot + ' p');
    const bounds = document.querySelector('#mirad-content').getBoundingClientRect();
    const walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
    const range = document.createRange();
    const top = mode === 'PAGED' ? bounds.top : 0;
    const bottom = mode === 'PAGED' ? bounds.bottom : innerHeight;
    let offset = 0;
    let node;
    while ((node = walker.nextNode())) {
      for (let i = 0; i < node.length; i++) {
        range.setStart(node, i);
        range.setEnd(node, i + 1);
        const r = range.getBoundingClientRect();
        if (r.width > 0 && r.left >= bounds.left - 1 && r.right <= bounds.right + 1 &&
            r.top >= top - 1 && r.bottom <= bottom + 1) return offset + i;
      }
      offset += node.length;
    }
    throw new Error('fixture has no visible character');
  }, mode);
}

async function sample(page, anchor) {
  return page.evaluate(offset => {
    const p = document.querySelector(window.__fontRoot + ' p');
    const walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
    let remaining = offset;
    let node;
    let rect = null;
    while ((node = walker.nextNode())) {
      if (remaining < node.length) {
        const range = document.createRange();
        range.setStart(node, remaining);
        range.setEnd(node, remaining + 1);
        const r = range.getBoundingClientRect();
        rect = { left: r.left, right: r.right, top: r.top, bottom: r.bottom };
        break;
      }
      remaining -= node.length;
    }
    const paragraphRange = document.createRange();
    paragraphRange.selectNodeContents(p);
    const content = document.querySelector('#mirad-content');
    const bounds = content.getBoundingClientRect();
    return {
      state: JSON.parse(MiradReader.getState()),
      font: getComputedStyle(p).fontSize,
      colWidth: getComputedStyle(content).width,
      lineRects: paragraphRange.getClientRects().length,
      marks: [...p.querySelectorAll('mark')].map(m => [m.className, m.textContent]),
      scrollHeight: document.documentElement.scrollHeight,
      rect,
      bounds: { left: bounds.left, right: bounds.right, top: bounds.top, bottom: bounds.bottom },
      viewportHeight: innerHeight,
      text: p.textContent.slice(offset, offset + 16),
      activeChapter: window.__activeChapter
    };
  }, anchor);
}

function assertVisible(s, mode) {
  assert(s.rect, 'anchor must refer to a real character in the fixture');
  assert(s.rect.left >= s.bounds.left - pixelTolerance &&
    s.rect.right <= s.bounds.right + pixelTolerance,
  'anchor must remain inside the visible column: ' + JSON.stringify(s.rect));
  const top = mode === 'PAGED' ? s.bounds.top : 0;
  const bottom = mode === 'PAGED' ? s.bounds.bottom : s.viewportHeight;
  assert(s.rect.top >= top - pixelTolerance && s.rect.bottom <= bottom + pixelTolerance,
    'anchor must remain vertically visible: ' + JSON.stringify(s.rect));
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const results = [];
  const failures = [];
  const scenarios = [];
  for (const mode of ['PAGED', 'SCROLL']) {
    for (const limited of [false, true]) {
      for (const baseline of [true, false]) scenarios.push({ mode, limited, baseline, windowed: false });
    }
  }
  // The app enables continuous scrolling by default. Exercise chapter-local
  // offsets with a whole earlier chapter still mounted above the current one.
  for (const limited of [false, true]) {
    scenarios.push({ mode: 'SCROLL', limited, baseline: false, windowed: true });
  }

  try {
    for (const scenario of scenarios) {
      const { mode, limited, baseline, windowed } = scenario;
      const name = [mode, limited ? 'limited' : 'phone', baseline ? 'baseline' : 'current',
        windowed ? 'windowed' : 'single'].join('-');
      const page = await browser.newPage({ viewport: { width: limited ? 960 : 390, height: 700 } });
      const result = { ...scenario, name, states: [] };
      results.push(result);
      try {
        // All resources are inline; any accidental network request is blocked.
        await page.route('**/*', route => route.abort());
        const config = size => ({
          mode, anim: 'NONE', pad: 20, padTop: 28, padBottom: 32,
          colMax: limited ? Math.round(size * 20) : 0,
          windowedScroll: windowed, chapterCount: windowed ? 3 : 1
        });
        const body = '山间的风吹过竹林，他继续沿着石阶前行。眼前的路转过山腰，远处传来清晰的钟声。'.repeat(130);
        const paragraphs = '<p>' + body + '</p><p>' + body + '</p>';
        const chapters = windowed
          ? [0, 1, 2].map(index => '<section class="mirad-chapter" data-chapter="' + index + '">' +
              paragraphs + '</section>').join('')
          : paragraphs;
        await page.setContent('<!doctype html><html><head><style>' + structural +
          '</style><style id="mirad-theme">' + css(14, baseline) +
          '</style></head><body><div id="mirad-content" data-chapter="0">' +
          chapters + '</div></body></html>');
        await page.evaluate(windowed => {
          window.__fontRoot = windowed
            ? 'section.mirad-chapter[data-chapter="1"]' : '#mirad-content';
          window.MiradBridge = {
            onReady() {}, onState() {}, onMetrics() {}, onSelection() {},
            onChapterChanged(index) { window.__activeChapter = index; }
          };
        }, windowed);
        await page.addScriptTag({ content: js });
        await page.evaluate(c => MiradReader.init(c), config(14));
        await settle(page);
        if (windowed) {
          await page.evaluate(() => {
            const section = document.querySelector(window.__fontRoot);
            window.scrollTo(0, section.getBoundingClientRect().top + scrollY + 500);
          });
          await page.waitForFunction(() => window.__activeChapter === 1);
        }
        await page.evaluate(() => {
          MiradReader.renderHighlights([{ start: 1500, end: 1510, note: 'note' }]);
          MiradReader.markSearch(1520, 1530);
          MiradReader.markTts(1540, 1550);
        });
        if (windowed) {
          const target = await sample(page, 1800);
          await page.evaluate(top => window.scrollTo(0, top + scrollY - 40), target.rect.top);
        } else {
          await page.evaluate(() => MiradReader.gotoOffset(1800, false));
        }
        await settle(page);
        const anchor = await firstVisibleCharacter(page, mode);
        const original = await sample(page, anchor);
        result.anchor = anchor;
        result.original = original;
        assertVisible(original, mode);
        for (const size of [14, 30, 14]) {
          const before = await page.evaluate(() => MiradReader.currentOffset());
          await page.evaluate(({ theme, c }) => MiradReader.setThemeCssOnly(theme, c),
            { theme: css(size, baseline), c: config(size) });
          await settle(page);
          const s = await sample(page, anchor);
          s.requested = size;
          s.before = before;
          result.states.push(s);
          assert.deepStrictEqual(s.marks, original.marks, 'marks must retain their kind and text');
          assert.strictEqual(s.marks.length, 3, 'marks lost');
          assert.strictEqual(s.font, baseline ? '16px' : size + 'px');
          assert.strictEqual(s.text, original.text, 'anchor text changed');
          assertVisible(s, mode);
          if (mode === 'SCROLL') {
            assert(Math.abs(s.rect.top - original.rect.top) <= pixelTolerance,
              'same character must keep its viewport position: ' + original.rect.top + ' -> ' + s.rect.top);
          }
          if (windowed) assert.strictEqual(s.activeChapter, 1, 'font change must not switch chapters');
        }
        const states = result.states;
        if (!baseline) {
          if (!limited) assert.notStrictEqual(states[0].lineRects, states[1].lineRects, 'wrapping must change');
          else assert.notStrictEqual(states[0].colWidth, states[1].colWidth, 'colMax must scale with font');
          assert.strictEqual(states[0].lineRects, states[2].lineRects, 'wrapping must restore');
          assert.strictEqual(states[0].state.pages, states[2].state.pages, 'page count must restore');
          if (!limited) assert(states[1].state.pages > states[0].state.pages);
        }
        // A later user position must replace the old styling anchor.
        await page.evaluate(mode => {
          if (mode === 'PAGED') MiradReader.nextPage();
          else window.scrollBy(0, 300);
        }, mode);
        await settle(page);
        const movedAnchor = await firstVisibleCharacter(page, mode);
        assert.notStrictEqual(movedAnchor, anchor, 'navigation fixture must move to different text');
        const movedOriginal = await sample(page, movedAnchor);
        result.navigation = { anchor: movedAnchor, original: movedOriginal, states: [] };
        for (const size of [30, 14]) {
          await page.evaluate(({ theme, c }) => MiradReader.setThemeCssOnly(theme, c),
            { theme: css(size, baseline), c: config(size) });
          await settle(page);
          const s = await sample(page, movedAnchor);
          result.navigation.states.push(s);
          assertVisible(s, mode);
          assert.strictEqual(s.text, movedOriginal.text);
          if (mode === 'SCROLL') {
            assert(Math.abs(s.rect.top - movedOriginal.rect.top) <= pixelTolerance,
              'font change after navigation must use the new scroll anchor');
          }
          if (windowed) assert.strictEqual(s.activeChapter, 1);
        }
        if (mode === 'SCROLL') {
          // The public jump API also receives chapter-local offsets. Its 8px
          // top allowance must not depend on earlier mounted chapters/margins.
          await page.evaluate(() => MiradReader.gotoOffset(1800, false));
          await settle(page);
          const jump = await sample(page, 1800);
          result.offsetNavigation = jump;
          assertVisible(jump, mode);
          assert(Math.abs(jump.rect.top - 8) <= pixelTolerance,
            'chapter-local gotoOffset must place its target at the 8px top allowance');
          assert.strictEqual(jump.state.offset, await firstVisibleCharacter(page, mode),
            'reported offset must match the visible chapter-local text');
          if (windowed) assert.strictEqual(jump.activeChapter, 1);
        }
        await page.screenshot({ path: path.join(dir, name + '.png') });
        result.ok = true;
        console.log('PASS ' + name);
      } catch (error) {
        result.ok = false;
        result.error = String(error.stack || error);
        failures.push({ name, error: result.error });
        console.error('FAIL ' + name + ': ' + error.message);
      } finally {
        await page.close();
      }
    }
  } finally {
    fs.writeFileSync(path.join(dir, 'results.json'),
      JSON.stringify({ browser: browser.version(), results, failures }, null, 2));
    await browser.close();
  }
  console.log('Font/anchor cases: ' + (results.length - failures.length) + '/' + results.length);
  if (failures.length) process.exitCode = 1;
}

main().catch(error => { console.error(error); process.exitCode = 1; });

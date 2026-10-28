const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../..');
const outDir = path.resolve(root, process.env.MIRAD_ANCHOR_EVIDENCE_DIR || 'build/verification/anchor-regressions');
const readerJs = require('./reader-source.cjs')();
const readerCss = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.css'), 'utf8');
const theme14 = fs.readFileSync(path.join(__dirname, 'fixtures/theme-14.css'), 'utf8');
const theme = size => theme14.replace('font-size:14.0px', 'font-size:' + size + 'px');
const paragraph = '山间的风吹过竹林，他继续沿着石阶前行。眼前的路转过山腰，远处传来清晰的钟声。'.repeat(130);
const tolerance = 1; // CSS pixels; covers integer scroll-coordinate rounding.
fs.mkdirSync(outDir, { recursive: true });

async function settle(fixture) {
  await fixture.page.evaluate(() => document.fonts.ready);
  if (fixture.clock) await fixture.page.clock.runFor(50);
  else await fixture.page.evaluate(() =>
    new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function requestReady(promise) {
  let timeout;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timeout = setTimeout(() => reject(new Error('mock request did not arrive')), 3000);
    })]);
  } finally {
    clearTimeout(timeout);
  }
}

async function openFixture(browser, options) {
  const mode = options.mode || 'PAGED';
  const width = options.width || 390;
  const indices = options.chapters || [];
  const active = options.active === undefined ? 1 : options.active;
  const clock = !!options.clock;
  const page = await browser.newPage({ viewport: { width, height: 700 } });
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  const config = size => ({
    mode, anim: 'NONE', pad: options.pad === undefined ? 20 : options.pad,
    padTop: 28, padBottom: 32,
    clipTop: options.cutouts ? 28 : 0, clipBottom: options.cutouts ? 32 : 0,
    colMax: options.limited === false || width !== 960 ? 0 : size * 20,
    windowedScroll: indices.length > 0,
    chapterCount: indices.length ? Math.max(...indices) + 1 : 1
  });
  const fixture = { page, mode, clock, config, errors };
  try {
    await page.route('**/*', route => route.abort());
    if (clock) await page.clock.install({ time: 0 });
    let imageRequested;
    if (options.image) {
      let resolveImage;
      imageRequested = new Promise(resolve => { resolveImage = resolve; });
      await page.route('**/late.svg', route => resolveImage(route));
      fixture.loadImage = async () => {
        const route = await requestReady(imageRequested);
        await route.fulfill({ contentType: 'image/svg+xml',
          body: '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"><rect width="1" height="1" fill="white"/></svg>' });
      };
    }
    if (options.prepend) {
      let resolveFragment;
      const requested = new Promise(resolve => { resolveFragment = resolve; });
      await page.route('**/fragment/0', route => resolveFragment(route));
      fixture.prepend = async () => {
        const route = await requestReady(requested);
        await route.fulfill({ contentType: 'text/html', body:
          '<section class="mirad-chapter" data-chapter="0"><p>' + paragraph + '</p><p>' + paragraph + '</p></section>' });
      };
    }
    const text = options.text || paragraph;
    const paragraphs = '<p>' + text + '</p><p>' + text + '</p>';
    const body = indices.length
      ? indices.map(index => '<section class="mirad-chapter" data-chapter="' + index + '">' + paragraphs + '</section>').join('')
      : paragraphs;
    const image = options.image ? '<img id="late-image" width="1" height="1" src="/late.svg">' : '';
    await page.setContent('<!doctype html><html><head><base href="https://fixture.invalid/"><style>' +
      readerCss + '</style><style id="mirad-theme">' + theme(14) +
      '</style></head><body><div id="mirad-content" data-chapter="' + (indices[0] || 0) + '">' +
      body + image + '</div></body></html>', { waitUntil: 'domcontentloaded' });
    // Freeze before installing production timers. runFor executes their real callbacks.
    if (clock) await page.clock.pauseAt(60000);
    await page.evaluate(({ mode, indices, active }) => {
      window.__anchorRoot = indices.length ? 'section[data-chapter="' + active + '"]' : '#mirad-content';
      window.__layoutReports = 0;
      window.__visualReports = [];
      window.MiradBridge = {
        onReady() {}, onState() {}, onSelection() {},
        onVisualChanged() { window.__visualReports.push(performance.now()); },
        onMetrics() { window.__layoutReports++; },
        onChapterChanged(index) { window.__activeChapter = index; }
      };
      // Fixture paragraphs contain plain text. This DOM oracle does not use
      // the reader's offset/page conversion or reproduce its column calculation.
      window.__glyph = offset => {
        const p = document.querySelector(window.__anchorRoot + ' p');
        const range = document.createRange();
        range.setStart(p.firstChild, offset);
        range.setEnd(p.firstChild, offset + 1);
        const r = range.getBoundingClientRect();
        return { left: r.left, right: r.right, top: r.top, bottom: r.bottom };
      };
      window.__visible = offset => {
        const r = window.__glyph(offset);
        const c = document.querySelector('#mirad-content').getBoundingClientRect();
        return r.left >= c.left - 1 && r.right <= c.right + 1 &&
          r.top >= (mode === 'PAGED' ? c.top : 0) - 1 &&
          r.bottom <= (mode === 'PAGED' ? c.bottom : innerHeight) + 1;
      };
      window.__firstVisible = () => {
        const length = document.querySelector(window.__anchorRoot + ' p').textContent.length;
        for (let i = 0; i < length; i++) if (window.__visible(i)) return i;
        throw new Error('no visible fixture character');
      };
    }, { mode, indices, active });
    await page.addScriptTag({ content: readerJs });
    await page.evaluate(c => MiradReader.init(c), config(14));
    await settle(fixture);
    if (indices.length) {
      await page.evaluate(() => {
        const section = document.querySelector(window.__anchorRoot);
        scrollTo(0, section.getBoundingClientRect().top + scrollY + 100);
        document.dispatchEvent(new Event('scroll'));
      });
      if (clock) {
        await page.clock.runFor(200);
        assert.strictEqual(await page.evaluate(() => window.__activeChapter), active);
      } else await page.waitForFunction(index => window.__activeChapter === index, active);
      await page.evaluate(offset => {
        scrollTo(0, window.__glyph(offset).top + scrollY - 40);
        document.dispatchEvent(new Event('scroll'));
      }, options.target || 1800);
    } else await page.evaluate(() => MiradReader.gotoOffset(1800, false));
    await settle(fixture);
    return fixture;
  } catch (error) {
    await page.close();
    throw error;
  }
}

async function snapshot(fixture, offset) {
  return fixture.page.evaluate(offset => {
    const glyph = window.__glyph(offset);
    const bounds = document.querySelector('#mirad-content').getBoundingClientRect();
    // With no gutter, a fractional glyph rectangle can straddle the boundary.
    // Its center identifies the containing column without copying page arithmetic.
    const center = (glyph.left + glyph.right) / 2;
    return {
    offset, glyph, visible: window.__visible(offset),
    columnContains: center >= bounds.left && center < bounds.right &&
      glyph.top >= bounds.top - 1 && glyph.bottom <= bounds.bottom + 1,
    state: JSON.parse(MiradReader.getState()),
    active: window.__activeChapter,
    chapters: [...document.querySelectorAll('section.mirad-chapter')].map(s => Number(s.dataset.chapter)),
    layoutReports: window.__layoutReports
  };
  }, offset);
}

async function change(fixture, size) {
  await fixture.page.evaluate(({ css, config }) => MiradReader.setThemeCssOnly(css, config),
    { css: theme(size), config: fixture.config(size) });
  await settle(fixture);
  assert.strictEqual(await fixture.page.evaluate(() =>
    getComputedStyle(document.querySelector(window.__anchorRoot + ' p')).fontSize), size + 'px');
}

function retained(fixture, before, after) {
  assert(after.visible, 'original character left the viewport: ' + JSON.stringify(after.glyph));
  if (fixture.mode === 'SCROLL') {
    assert(Math.abs(after.glyph.top - before.glyph.top) <= tolerance,
      'scroll anchor drift: ' + before.glyph.top + ' -> ' + after.glyph.top);
    assert.strictEqual(after.active, before.active, 'active chapter changed');
  }
}

async function capture(fixture) {
  const offset = await fixture.page.evaluate(() => window.__firstVisible());
  const before = await snapshot(fixture, offset);
  assert(before.visible);
  return before;
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const results = [];
  async function run(name, options, check) {
    const result = { name, states: [] };
    results.push(result);
    let fixture;
    try {
      fixture = await openFixture(browser, options);
      await check(fixture, result);
      assert.deepStrictEqual(fixture.errors, [], 'unexpected browser errors');
      result.ok = true;
      console.log('PASS ' + name);
      if (name === 'font-390-16' || name === 'resource-PAGED-single' || name === 'trim-390') {
        await fixture.page.screenshot({ path: path.join(outDir, name + '.png') });
      }
    } catch (error) {
      result.ok = false;
      result.error = String(error.stack || error);
      console.error('FAIL ' + name + ': ' + error.message);
    } finally {
      if (fixture) await fixture.page.close();
    }
  }
  try {
    for (const chapters of [[], [0, 1, 2]]) {
      await run('scroll-safe-area-' + (chapters.length ? 'windowed' : 'single'),
        { mode: 'SCROLL', chapters, cutouts: true }, async (f, r) => {
          const before = await capture(f);
          const assertSafePaint = async () => {
            const paint = await f.page.evaluate(() => {
              const c = document.getElementById('mirad-content');
              const painted = (x, y) => c.contains(document.elementFromPoint(x, y));
              const xs = [innerWidth * .25, innerWidth * .5, innerWidth * .75];
              return {
                top: xs.some(x => painted(x, 14)),
                bottom: xs.some(x => painted(x, innerHeight - 16)),
                middle: xs.some(x => painted(x, innerHeight / 2)),
                viewport: [innerWidth, innerHeight],
                scrollY
              };
            });
            r.states.push(paint);
            assert(!paint.top && !paint.bottom, 'text paints inside the reserved safe area');
            assert(paint.middle, 'safe-area protection must keep body text visible');
            assert.deepStrictEqual(paint.viewport, [390, 700], 'paint protection changed the viewport');
          };
          await assertSafePaint();
          await change(f, 30);
          await change(f, 14);
          retained(f, before, await snapshot(f, before.offset));
          await assertSafePaint();
          await f.page.evaluate(() => scrollBy(0, 333));
          await settle(f);
          await assertSafePaint();
          await f.page.evaluate(({ css, config }) => MiradReader.setThemeCssOnly(css,
            { ...config, mode: 'PAGED', windowedScroll: false }),
            { css: theme(14), config: f.config(14) });
          await settle(f);
          assert.strictEqual(await f.page.evaluate(() =>
            getComputedStyle(document.getElementById('mirad-content')).clipPath), 'none',
          'scroll clipping must not hide later columns after switching to paged mode');
        });
    }
    await run('scroll-without-cutout-jump', { mode: 'SCROLL' }, async (f, r) => {
      await f.page.evaluate(() => MiradReader.gotoOffset(1800, false));
      await settle(f);
      const target = await f.page.evaluate(() => {
        const glyph = window.__glyph(1800);
        const content = document.getElementById('mirad-content');
        return { top: glyph.top, painted: content.contains(document.elementFromPoint(
          (glyph.left + glyph.right) / 2, (glyph.top + glyph.bottom) / 2)) };
      });
      r.states.push(target);
      assert(Math.abs(target.top - 8) <= tolerance, 'ordinary jump position changed');
      assert(target.painted, 'cutout protection hid a jump target on an ordinary screen');
    });
    for (const width of [390, 960]) {
      for (const size of [16, 18, 20, 22, 24, 26, 28, 30]) {
        await run('font-' + width + '-' + size, { width }, async (f, r) => {
          const before = await capture(f);
          r.before = before;
          for (const next of [size, 14]) {
            await change(f, next);
            const after = await snapshot(f, before.offset);
            r.states.push(after);
            retained(f, before, after);
          }
        });
      }
    }

    const boundaryCases = [390, 960].flatMap(width =>
      [12, 14, 16, 24, 30, 60].map(size => ({ width, size })));
    boundaryCases.push({ width: 390, size: 12, pad: 0, limited: false, text: 'ili.,li.'.repeat(5000) });
    boundaryCases.push({ width: 960, size: 12, pad: 0, limited: false, text: 'ili.,li.'.repeat(5000) });
    for (const options of boundaryCases) {
      await run('boundary-' + options.width + '-' + options.size + (options.pad === 0 ? '-no-gap' : ''),
        options, async (f, r) => {
          await change(f, options.size);
          await f.page.evaluate(() => MiradReader.gotoPage(2, false));
          await settle(f);
          const boundary = await f.page.evaluate(() => window.__firstVisible());
          const rightHalf = await f.page.evaluate(start => {
            const c = document.querySelector('#mirad-content').getBoundingClientRect();
            for (let i = start; i < start + 300; i++) {
              if (window.__visible(i) && window.__glyph(i).left > (c.left + c.right) / 2) return i;
            }
            throw new Error('fixture does not exercise the right half of a column');
          }, boundary);
          r.boundary = boundary;
          r.rightHalf = rightHalf;
          for (const offset of [boundary - 1, boundary, boundary + 1, rightHalf]) {
            await f.page.evaluate(offset => MiradReader.gotoOffset(offset, false), offset);
            await settle(f);
            const position = await snapshot(f, offset);
            r.states.push(position);
            assert(position.columnContains, 'column boundary mapped to the wrong page: ' + JSON.stringify(position));
          }
        });
    }

    for (const options of [
      { mode: 'PAGED', image: true },
      { mode: 'SCROLL', image: true },
      { mode: 'SCROLL', image: true, chapters: [0, 1, 2] }
    ]) {
      await run('resource-' + options.mode + (options.chapters ? '-windowed' : '-single'),
        options, async (f, r) => {
          const before = await capture(f);
          r.before = before;
          await change(f, 14);
          await change(f, 30);
          const enlarged = await snapshot(f, before.offset);
          retained(f, before, enlarged);
          r.states.push(enlarged);
          await f.loadImage();
          await f.page.waitForFunction(count =>
            document.querySelector('#late-image').naturalWidth === 1 && window.__layoutReports > count,
          enlarged.layoutReports);
          await settle(f);
          const afterLoad = await snapshot(f, before.offset);
          r.states.push(afterLoad);
          retained(f, before, afterLoad);
          await change(f, 14);
          const after = await snapshot(f, before.offset);
          r.states.push(after);
          retained(f, before, after);
        });
    }

    for (const width of [390, 960]) {
      await run('trim-' + width, { mode: 'SCROLL', width, chapters: [0, 1, 2, 3, 4, 5], active: 2, clock: true },
        async (f, r) => {
          const before = await capture(f);
          r.before = before;
          await change(f, 14);
          await change(f, 30);
          const enlarged = await snapshot(f, before.offset);
          assert.deepStrictEqual(enlarged.chapters, [0, 1, 2, 3, 4, 5], 'timer must not fire before the interleaving');
          r.states.push(enlarged);
          retained(f, before, enlarged);
          await f.page.clock.runFor(1000);
          const trimmed = await snapshot(f, before.offset);
          assert.deepStrictEqual(trimmed.chapters, [1, 2, 3], 'real production trim must run');
          r.states.push(trimmed);
          retained(f, before, trimmed);
          await change(f, 14);
          const after = await snapshot(f, before.offset);
          r.states.push(after);
          retained(f, before, after);
          // Structural compensation must not cause a later user scroll to reuse an old anchor.
          await f.page.evaluate(() => { scrollBy(0, 300); document.dispatchEvent(new Event('scroll')); });
          await f.page.clock.runFor(200);
          const moved = await capture(f);
          assert.notStrictEqual(moved.offset, before.offset);
          await change(f, 30);
          await change(f, 14);
          const movedAfter = await snapshot(f, moved.offset);
          r.navigation = { before: moved, after: movedAfter };
          retained(f, moved, movedAfter);
        });

      await run('prepend-' + width, { mode: 'SCROLL', width, chapters: [1, 2, 3], active: 1, target: 200, prepend: true, clock: true },
        async (f, r) => {
          const before = await capture(f);
          r.before = before;
          await change(f, 14);
          await change(f, 30);
          const enlarged = await snapshot(f, before.offset);
          assert.deepStrictEqual(enlarged.chapters, [1, 2, 3]);
          retained(f, before, enlarged);
          r.states.push(enlarged);
          await f.prepend();
          await settle(f);
          const prepended = await snapshot(f, before.offset);
          assert.deepStrictEqual(prepended.chapters, [0, 1, 2, 3], 'mock fragment must be inserted by production fetch');
          retained(f, before, prepended);
          r.states.push(prepended);
          await change(f, 14);
          const after = await snapshot(f, before.offset);
          r.states.push(after);
          retained(f, before, after);
        });
    }
    await run('manual-scroll-before-trim', {
      mode: 'SCROLL', chapters: [0, 1, 2, 3, 4, 5], active: 2, clock: true
    }, async (f, r) => {
      const original = await capture(f);
      await change(f, 30);
      await f.page.evaluate(() => { scrollBy(0, 300); document.dispatchEvent(new Event('scroll')); });
      await f.page.clock.runFor(200);
      const moved = await capture(f);
      r.before = moved;
      assert.notStrictEqual(moved.offset, original.offset);
      assert.deepStrictEqual(moved.chapters, [0, 1, 2, 3, 4, 5]);
      await f.page.clock.runFor(1000);
      const trimmed = await snapshot(f, moved.offset);
      assert.deepStrictEqual(trimmed.chapters, [1, 2, 3]);
      retained(f, moved, trimmed);
      r.states.push(trimmed);
      await change(f, 14);
      const after = await snapshot(f, moved.offset);
      r.states.push(after);
      retained(f, moved, after);
    });

    for (const mode of ['SCROLL', 'PAGED']) {
      await run('semantic-visual-' + mode, { mode, image: true }, async (f, r) => {
        const count = () => f.page.evaluate(() => window.__visualReports.length);
        const beforeIdle = await capture(f);
        const domBefore = await f.page.evaluate(() => document.getElementById('mirad-content').innerHTML);
        await f.page.waitForTimeout(1200);
        const initialCount = await count();
        await f.page.waitForTimeout(3000);
        assert.strictEqual(await count(), initialCount, 'idle reader keeps sending visual notifications');
        assert.strictEqual(await f.page.evaluate(() => document.getElementById('mirad-content').innerHTML), domBefore,
          'visual observation modifies the document');
        retained(f, beforeIdle, await snapshot(f, beforeIdle.offset));
        await f.page.evaluate(mode => {
          if (mode === 'PAGED') MiradReader.nextPage(); else scrollBy(0, 160);
        }, mode);
        await f.page.waitForFunction(previous => window.__visualReports.length > previous, initialCount, { timeout: 2000 });
        await f.page.waitForTimeout(650);
        const afterMove = await count();
        assert(afterMove > initialCount, 'real scroll/page changes do not notify analysis');
        await f.page.evaluate(() => { document.querySelector('#mirad-content p').style.color = '#354535'; });
        await f.page.waitForFunction(previous => window.__visualReports.length > previous, afterMove, { timeout: 2000 });
        await f.page.waitForTimeout(650);
        const afterStyle = await count();
        assert(afterStyle > afterMove, 'real inline style changes do not notify analysis');
        await f.page.evaluate(() => {
          const text = document.querySelector('#mirad-content p').firstChild;
          text.data = '林' + text.data.slice(1);
        });
        await f.page.waitForFunction(previous => window.__visualReports.length > previous, afterStyle, { timeout: 2000 });
        await f.page.waitForTimeout(650);
        const afterText = await count();
        assert(afterText > afterStyle, 'real text changes do not notify analysis');
        await f.loadImage();
        await f.page.waitForFunction(() => document.querySelector('#late-image').complete);
        await f.page.waitForFunction(previous => window.__visualReports.length > previous, afterText, { timeout: 2000 });
        await f.page.waitForTimeout(650);
        const afterImage = await count();
        assert(afterImage > afterText, 'delayed image completion does not notify analysis');
        await f.page.waitForTimeout(2000);
        assert.strictEqual(await count(), afterImage, 'resource completion leaves a repeating visual timer');
        const reports = await f.page.evaluate(() => window.__visualReports);
        for (let i = 1; i < reports.length; i++) {
          assert(reports[i] - reports[i - 1] >= 500, 'semantic notifications exceed two per second: ' + (reports[i] - reports[i - 1]));
        }
        r.states.push({ initialCount, afterMove, afterStyle, afterText, afterImage, reports });
      });
    }
    await run('semantic-visual-animation-visibility', { mode: 'SCROLL' }, async (f, r) => {
      const count = () => f.page.evaluate(() => window.__visualReports.length);
      await f.page.waitForTimeout(1200);
      await f.page.evaluate(() => {
        const style = document.createElement('style');
        style.id = 'visual-animation-fixture';
        style.textContent = '@keyframes visualPulse { from { opacity: 1; } to { opacity: .6; } }';
        document.head.appendChild(style);
        document.querySelector('#mirad-content p').style.animation = 'visualPulse 10s infinite alternate';
      });
      await f.page.evaluate(() => document.getAnimations().length);
      await f.page.waitForTimeout(1500);
      const active = await count();
      assert(await f.page.evaluate(() => document.getAnimations().some(a => a.playState === 'running')),
        'the native browser CSS animation did not start');
      await f.page.waitForTimeout(1500);
      const stillActive = await count();
      assert(stillActive > active && stillActive - active <= 4, 'active CSS animation has no bounded updates');
      await f.page.evaluate(() => {
        Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
        document.dispatchEvent(new Event('visibilitychange'));
      });
      await f.page.waitForTimeout(2000);
      assert.strictEqual(await count(), stillActive, 'hidden documents keep requesting analysis');
      await f.page.evaluate(() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); });
      await f.page.waitForFunction(previous => window.__visualReports.length > previous, stillActive, { timeout: 2000 });
      const resumed = await count();
      assert(resumed > stillActive, 'resuming a visible animated document does not notify');
      await f.page.evaluate(() => {
        document.querySelector('#mirad-content p').style.animation = 'none';
        document.getElementById('visual-animation-fixture').remove();
      });
      await f.page.waitForTimeout(1200);
      const stopped = await count();
      await f.page.waitForTimeout(2500);
      assert.strictEqual(await count(), stopped, 'finished animation leaves an always-running visual timer');
      r.states.push({ active, stillActive, resumed, stopped });
    });
  } finally {
    fs.writeFileSync(path.join(outDir, 'results.json'),
      JSON.stringify({ browser: browser.version(), results }, null, 2));
    await browser.close();
  }
  const failed = results.filter(result => !result.ok).length;
  console.log('Anchor regressions: ' + (results.length - failed) + '/' + results.length);
  if (failed) process.exitCode = 1;
}

main().catch(error => { console.error(error); process.exitCode = 1; });


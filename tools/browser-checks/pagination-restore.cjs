const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../..');
const out = path.join(root, 'build/verification/pagination-restore');
const script = require('./reader-source.cjs')();
const structure = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.css'), 'utf8');
const theme = fs.readFileSync(path.join(__dirname, 'fixtures/theme-14.css'), 'utf8')
  .replace('font-size:14.0px', 'font-size:24px');
const text = '山间的风吹过竹林，远处传来清晰的钟声。';
fs.mkdirSync(out, { recursive: true });

async function open(browser, geometry, paragraph, offset = 0) {
  const page = await browser.newPage({ viewport: { width: geometry.width, height: 872 } });
  // Android 的逻辑视口整数值与实际 CSS 布局宽度可能不同，如392与392.7273。
  // 保留这种差异，不把测试容器提前取整，才能覆盖原生设备中的虚假末页。
  await page.setContent('<!doctype html><html><head><style>' + structure + theme +
    '</style><style>html{width:' + (geometry.width + geometry.fraction) + 'px}</style></head>' +
    '<body class="mirad-txt"><div id="mirad-content" data-chapter="0"><p>' + paragraph + '</p></div></body></html>');
  await page.evaluate(() => { window.MiradBridge = { onReady() {}, onState() {}, onMetrics() {}, onVisualChanged() {} }; });
  await page.addScriptTag({ content: script });
  await page.evaluate(async ({ geometry, offset }) => {
    await document.fonts.ready;
    MiradReader.init({ mode: 'PAGED', anim: 'NONE', pad: 20, padTop: 36, padBottom: 36,
      colMax: geometry.limit, chapterCount: 1, windowedScroll: false });
    MiradReader.gotoOffset(offset, false);
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  }, { geometry, offset });
  return page;
}

async function measure(page, offset = 0) {
  return page.evaluate(offset => {
    const c = document.getElementById('mirad-content');
    const bounds = c.getBoundingClientRect();
    const node = c.querySelector('p').firstChild;
    const range = document.createRange();
    range.setStart(node, Math.min(offset, node.length - 1));
    range.setEnd(node, Math.min(offset + 1, node.length));
    const rect = range.getBoundingClientRect();
    return { state: JSON.parse(MiradReader.getState()), width: bounds.width,
      scrollWidth: c.scrollWidth, scrollLeft: c.scrollLeft, column: getComputedStyle(c).columnWidth,
      glyph: { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom },
      bounds: { left: bounds.left, right: bounds.right, top: bounds.top, bottom: bounds.bottom } };
  }, offset);
}

function visible(sample) {
  const { glyph: r, bounds: b } = sample;
  assert(r.left >= b.left - 1 && r.right <= b.right + 1 && r.top >= b.top - 1 && r.bottom <= b.bottom + 1,
    '保存的字符必须在实际可见列内：' + JSON.stringify(sample));
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const results = [];
  try {
    for (const geometry of [
      { width: 392, fraction: .7272727, limit: 0 },
      { width: 320, fraction: .75, limit: 0 },
      { width: 960, fraction: .75, limit: 480 }
    ]) {
      const result = { geometry };
      results.push(result);
      let page;
      let cold;
      try {
        page = await open(browser, geometry, text);
        result.short = await measure(page);
        visible(result.short);
        assert.equal(result.short.state.pages, 1, '内容全部可见的短章不能出现虚假末页');
        assert.equal(await page.evaluate(() => MiradReader.nextPage()), false, '短章不可翻到原地不动的第二页');
        await page.close();
        page = await open(browser, geometry, text.repeat(300));
        assert((await measure(page)).state.pages > 6, '长章必须有真实后续页');
        await page.evaluate(() => MiradReader.gotoPage(6, false));
        const saved = JSON.parse(await page.evaluate(() => MiradReader.getState()));
        assert(saved.offset > 0 && saved.offset < saved.textLength, '真实页首应是可恢复的正文偏移');
        result.saved = await measure(page, saved.offset);
        visible(result.saved);
        await page.close();
        page = null;
        // 新文档与原生会话相同：init后按保存字符恢复，不沿用旧页面或旧滚动状态。
        cold = await open(browser, geometry, text.repeat(300), saved.offset);
        result.restored = await measure(cold, saved.offset);
        visible(result.restored);
        assert.equal(result.restored.state.page, 6, '冷打开必须回到原真实页');
        await cold.evaluate(() => MiradReader.gotoPage(JSON.parse(MiradReader.getState()).pages - 1, false));
        const tail = JSON.parse(await cold.evaluate(() => MiradReader.getState()));
        assert(tail.offset < tail.textLength, '真正末页必须含有正文，不能只产生末尾偏移');
        result.last = await measure(cold, tail.offset);
        visible(result.last);
        result.pass = true;
      } catch (error) {
        result.pass = false;
        result.error = error.stack;
      } finally {
        if (page && !page.isClosed()) await page.close();
        if (cold) await cold.close();
      }
    }
  } finally {
    await browser.close();
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(results, null, 2));
  }
  for (const result of results) console.log((result.pass ? 'PASS ' : 'FAIL ') + JSON.stringify(result.geometry) +
    (result.error ? '\n' + result.error : ''));
  process.exitCode = results.every(result => result.pass) ? 0 : 1;
}

main().catch(error => { console.error(error); process.exitCode = 1; });

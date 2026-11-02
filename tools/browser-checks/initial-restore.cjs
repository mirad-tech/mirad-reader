const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../..');
const reader = require('./reader-source.cjs')();
const css = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.css'), 'utf8');
const batch = new Date().toISOString().replace(/[:.]/g, '-');
const out = path.resolve(root, process.env.MIRAD_INITIAL_RESTORE_EVIDENCE_DIR || `build/verification/initial-restore/${batch}`);
const body = Array.from({ length: 180 }, (_, i) => `<p>第${i + 1}段：用于初次阅读位置恢复的合成正文。页面加载不应把章首默认位置作为新的阅读进度。${'测试文字与句子。'.repeat(8)}</p>`).join('');
const html = `<!doctype html><html><head><meta charset="utf-8"><style>${css}
body{font:18px/1.6 sans-serif}p{margin:0 0 1em}#mirad-content{color:#222}</style></head>
<body><div id="mirad-content" data-chapter="0">${body}</div></body></html>`;

async function main() {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const results = [];
  async function check(name, config, initialize, expected) {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    try {
      await page.route('**/*', route => route.abort());
      await page.setContent(html);
      await page.evaluate(() => {
        window.reports = [];
        window.MiradBridge = {
          onState(page, pages, offset, length) { window.reports.push({ type: 'state', page, pages, offset, length }); },
          onReady() { window.reports.push({ type: 'ready', ...JSON.parse(MiradReader.getState()) }); },
          onMetrics() {}, onVisualChanged() {}, onSelection() {}, onChapterChanged() {}
        };
      });
      await page.addScriptTag({ content: reader });
      await page.evaluate(initialize, config);
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const actual = await page.evaluate(() => ({
        reports: window.reports,
        state: JSON.parse(MiradReader.getState()),
        scrollLeft: document.getElementById('mirad-content').scrollLeft,
        scrollY: window.scrollY
      }));
      expected(actual);
      results.push({ name, pass: true, actual });
      console.log(`PASS ${name}`);
    } catch (error) {
      results.push({ name, pass: false, error: error.stack || String(error) });
      console.error(`FAIL ${name}: ${error.message}`);
    } finally { await page.close(); }
  }
  const base = { pad: 20, gap: 40, padTop: 24, padBottom: 32, anim: 'NONE', chapterCount: 1 };
  const restored = actual => {
    const positions = actual.reports.filter(r => r.type === 'state');
    const ready = actual.reports.filter(r => r.type === 'ready');
    assert.ok(positions.length > 0, '必须发布恢复后的进度');
    assert.ok(positions.every(r => r.offset > 0), '首次发布不能包含章首默认0');
    assert.equal(ready.length, 1);
    assert.ok(ready[0].offset > 0, 'onReady发生时恢复位置已经生效');
    assert.ok(actual.scrollLeft > 0 || actual.scrollY > 0, '真实正文应已经离开章首');
  };
  try {
    for (const [name, mode, windowedScroll] of [
      ['paged-first-restore', 'PAGED', false],
      ['scroll-first-restore', 'SCROLL', false],
      ['window-first-restore', 'SCROLL', true]
    ]) {
      await check(name, { ...base, mode, windowedScroll }, config => MiradReader.init(config, 5000), restored);
    }
    await check('legacy-init-without-offset', { ...base, mode: 'PAGED' }, config => MiradReader.init(config), actual => {
      assert.equal(actual.state.ready, true);
      assert.equal(actual.state.page, 0);
      assert.equal(actual.state.offset, 0);
      assert.equal(actual.reports.filter(r => r.type === 'ready').length, 1);
    });
    await check('legacy-pending-offset-before-init', { ...base, mode: 'SCROLL' }, config => {
      MiradReader.gotoOffset(5000, false);
      MiradReader.init(config);
    }, restored);
    await check('previous-chapter-end-offset', { ...base, mode: 'PAGED' }, config => MiradReader.init(config, 2147483647), actual => {
      restored(actual);
      assert.equal(actual.state.page, actual.state.pages - 1);
    });
    await check('resize-before-init-does-not-publish-default-progress', { ...base, mode: 'PAGED' }, config => {
      window.dispatchEvent(new Event('resize'));
      if (window.reports.some(r => r.type === 'state')) throw new Error('未初始化的resize发布了默认进度');
      MiradReader.init(config, 5000);
    }, restored);
    await check('reinitialize-restores-before-publishing', { ...base, mode: 'PAGED' }, config => {
      MiradReader.init(config);
      window.reports = [];
      MiradReader.init(config, 5000);
    }, restored);
  } finally {
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ browser: browser.version(), results }, null, 2));
    await browser.close();
  }
  const failed = results.filter(r => !r.pass).length;
  console.log(`Initial restore: ${results.length - failed}/${results.length}`);
  if (failed) process.exitCode = 1;
}

main().catch(error => { console.error(error); process.exitCode = 1; });

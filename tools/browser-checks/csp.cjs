/** CSP执行回归；MIRAD_CSP_FIXTURE_DIR 指向新生成的生产HTML，未指定时仅独立检查生产策略。 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../..');
const origin = 'https://mirad.local';
const provider = 'app/src/main/java/com/mirad/reader/reader/ReaderResourceProvider.kt';
const source = fs.readFileSync(path.join(root, provider), 'utf8');
const block = source.match(/CONTENT_SECURITY_POLICY = listOf\(([\s\S]*?)\)\.joinToString/);
assert.ok(block, '找不到生产CSP');
const policy = [...block[1].matchAll(/"([^"]+)"/g)].map(m => m[1].replaceAll('$ORIGIN', origin)).join('; ');
const readerJs = require('./reader-source.cjs')();
const readerCss = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.css'), 'utf8');
const font = fs.readFileSync(path.join(root, 'app/src/main/assets/fonts/mirad-reading-sans.woff2'));
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const out = path.resolve(root, process.env.MIRAD_CSP_EVIDENCE_DIR || 'build/verification/csp-regressions');
fs.mkdirSync(out, { recursive: true });

// 普通npm回归不依赖Android构建；完整修复验收必须另传入当前生产HTML。
function policyFixture(index) {
  return `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="${policy}">
<base href="${origin}/book/1/asset/OPS/"><link rel="stylesheet" href="book.css">
<link rel="stylesheet" href="${origin}/reader/reader.css">
<style id="mirad-theme">body{background-color:rgb(250,248,243)} #legitimate{color:rgb(12,34,56)!important}</style>
<script src="${origin}/reader/reader.js"></script></head><body class="book-style">
<div id="mirad-content" data-chapter="${index}"><h2>合成章节 ${index}</h2>
<p id="legitimate">${'用于验证分页、样式与跨章的正文。'.repeat(300)}</p>
<img id="local-image" src="pixel.png"><script src="proof.js"></script>
<script>window.__unsafeInline=true</script><img src="missing.png" onerror="window.__unsafeHandler=true">
<iframe src="frame.html"></iframe></div></body></html>`;
}
let fixtures;
if (process.env.MIRAD_CSP_FIXTURE_DIR) {
  fixtures = JSON.parse(fs.readFileSync(path.join(process.env.MIRAD_CSP_FIXTURE_DIR, 'documents.json'), 'utf8'));
  assert.equal(fixtures.policy, policy, '生产夹具策略已过期，重跑ReaderDocumentSecurityTest');
  for (const [file, expected] of Object.entries(fixtures.sourceHashes)) {
    assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex'), expected, `生产夹具已过期：${file}`);
  }
} else {
  fixtures = { documents: [{ name: 'policy-only', html: policyFixture(0) }], nextChapter: policyFixture(1),
    nextFragment: `<section class="mirad-chapter" data-chapter="1"><p>${'补章正文。'.repeat(300)}</p><script src="${origin}/book/1/asset/OPS/proof.js"></script></section>` };
}
function withoutMeta(html) {
  return html.replace(/<meta\b(?=[^>]*http-equiv\s*=\s*["']?Content-Security-Policy\b)[^>]*>/gi, '');
}
async function pageFor(browser, html, channel) {
  const page = await browser.newPage({ viewport: { width: 390, height: 740 } });
  await page.addInitScript(() => {
    window.__audit = { ready: 0, states: 0, poison: 0, chapters: [] };
    window.MiradBridge = {
      onReady() { window.__audit.ready++; }, onState() { window.__audit.states++; },
      onSelection(a, b, text) { if (text === 'poison') window.__audit.poison++; },
      onChapterChanged(index) { window.__audit.chapters.push(index); },
      onVisualChanged() {}, onMetrics() {}, onAutoScrollEnd() {}, onRequestNextChapter() {}
    };
  });
  const documentResponse = body => ({ status: 200, contentType: 'text/html; charset=utf-8',
    headers: channel === 'meta' ? {} : { 'Content-Security-Policy': policy },
    body: channel === 'header' ? withoutMeta(body) : body });
  await page.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) return route.abort();
    const p = decodeURIComponent(url.pathname);
    if (p === '/book/1/chapter/0') return route.fulfill(documentResponse(html));
    if (p === '/book/1/chapter/1') return route.fulfill(documentResponse(fixtures.nextChapter));
    if (p === '/book/1/fragment/1') return route.fulfill(documentResponse(fixtures.nextFragment));
    if (p === '/reader/reader.js') return route.fulfill({ contentType: 'application/javascript', body: readerJs });
    if (p === '/reader/reader.css') return route.fulfill({ contentType: 'text/css', body: readerCss });
    if (p === '/book/1/asset/OPS/pixel.png') return route.fulfill({ contentType: 'image/png', body: png });
    if (p === '/book/1/asset/OPS/font.woff2') return route.fulfill({ contentType: 'font/woff2', body: font });
    if (p === '/book/1/asset/OPS/book.css') return route.fulfill({ contentType: 'text/css', body: `@font-face{font-family:AuditFont;src:url('font.woff2')} #legitimate{font-family:AuditFont;color:rgb(255,0,0)}` });
    if (p.endsWith('/frame.html')) return route.fulfill({ contentType: 'text/html', body: '<script>parent.__unsafeFrame=true;parent.MiradBridge.onSelection(777,778,"poison",0)</script>' });
    if (p.startsWith('/book/') && /proof/i.test(p)) return route.fulfill({ contentType: 'application/javascript', body: 'window.__unsafeExternal=true;MiradBridge.onSelection(777,778,"poison",0);' });
    return route.fulfill({ status: 404, body: '' });
  });
  await page.goto(`${origin}/book/1/chapter/0`, { waitUntil: 'load' });
  return page;
}
async function checkState(page) {
  await page.evaluate(() => document.fonts.ready);
  return page.evaluate(() => ({
    reader: typeof window.MiradReader === 'object', external: !!window.__unsafeExternal,
    inline: !!window.__unsafeInline, handler: !!window.__unsafeHandler,
    svg: !!window.__unsafeSvg, frame: !!window.__unsafeFrame,
    poison: window.__audit.poison, readyCalls: window.__audit.ready, stateCalls: window.__audit.states,
    state: window.MiradReader ? JSON.parse(MiradReader.getState()) : null,
    color: document.getElementById('legitimate') ? getComputedStyle(document.getElementById('legitimate')).color : null,
    image: document.getElementById('local-image')?.naturalWidth || 0,
    font: [...document.fonts].some(f => f.family === 'AuditFont' && f.status === 'loaded')
  }));
}
function secure(state) {
  assert.deepEqual([state.external,state.inline,state.handler,state.svg,state.frame,state.poison], [false,false,false,false,false,0], '不可信内容执行或调用了专用毒化桥接标记');
}
(async () => {
  const browser = await chromium.launch({ headless: true, args: [`--log-file=${path.join(out, 'chromium.log')}`] });
  const results = [];
  async function test(name, run) {
    try { const detail = await run(); results.push({ name, pass: true, detail }); console.log('PASS ' + name); }
    catch (e) { results.push({ name, pass: false, error: e.stack || String(e) }); console.error('FAIL ' + name + ': ' + e.message); }
  }
  try {
    for (const channel of ['both', 'header', 'meta']) {
      for (const fixture of fixtures.documents) {
        await test(`${channel}/${fixture.name}`, async () => {
          const page = await pageFor(browser, fixture.html, channel);
          try {
            await page.evaluate(() => MiradReader.init({ mode: 'PAGED', anim: 'NONE', chapterCount: 2 }));
            const state = await checkState(page); secure(state);
            assert.ok(state.reader && state.state.ready && state.readyCalls > 0 && state.stateCalls > 0, '可信阅读器必须真正初始化与回报');
            assert.ok(state.state.pages > 1, '合法正文必须完成分页');
            assert.equal(state.color, 'rgb(12, 34, 56)', '主题样式仍覆盖原书样式');
            assert.equal(state.image, 1, '合法相对图片仍加载');
            assert.ok(state.font, '合法字体实际加载');
            return state;
          } finally { await page.close(); }
        });
      }
    }
    const main = fixtures.documents[0].html;
    for (const windowed of [false, true]) {
      await test(windowed ? 'windowed-append-switch-reload' : 'original-chapter-fetch-reload', async () => {
        const page = await pageFor(browser, main, 'both');
        try {
          await page.evaluate(windowed => MiradReader.init({ mode: 'SCROLL', anim: 'NONE', windowedScroll: windowed, chapterCount: 2 }), windowed);
          if (windowed) {
            await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
            await page.waitForSelector('section.mirad-chapter[data-chapter="1"]');
            await page.evaluate(() => MiradReader.applyConfig({ mode: 'PAGED', windowedScroll: false }));
            assert.equal(await page.locator('section.mirad-chapter').count(), 0, '模式切换后清除窗口包装');
          } else {
            assert.equal(await page.evaluate(() => MiradReader.advanceChapterForAuto()), 'ADVANCING');
            await page.waitForSelector('#mirad-content[data-chapter="1"]');
          }
          const changed = await checkState(page); secure(changed);
          assert.ok(changed.state.ready && changed.stateCalls > 0, '真实补章/换章后仍可阅读');
          await page.reload({ waitUntil: 'load' });
          await page.evaluate(() => MiradReader.init({ mode: 'PAGED', anim: 'NONE', chapterCount: 2 }));
          const reopened = await checkState(page); secure(reopened); assert.ok(reopened.state.ready);
          return { changed, reopened };
        } finally { await page.close(); }
      });
    }
    // 比较一次浏览器解析的真实字符流，防止HTML装配修复改变合法正文或补章偏移。
    for (const fixture of fixtures.compatibilityDocuments || []) {
      await test('text-preservation/' + fixture.name, async () => {
        const page = await browser.newPage();
        await page.route('**/*', route => route.abort());
        try {
          await page.setContent(fixture.raw);
          const expected = await page.locator('#whitespace').textContent();
          await page.setContent(fixture.html);
          assert.equal(await page.locator('#whitespace').textContent(), expected, '整章字符流与原文一致');
          await page.setContent(fixture.fragment);
          assert.equal(await page.locator('#whitespace').textContent(), expected, '补章字符流与整章一致');
          return { text: expected };
        } finally { await page.close(); }
      });
    }
  } finally {
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ browser: browser.version(), productionHtml: !!process.env.MIRAD_CSP_FIXTURE_DIR, policy, results }, null, 2));
    await browser.close();
  }
  const failed = results.filter(r => !r.pass).length;
  console.log(`CSP regressions: ${results.length - failed}/${results.length}`);
  if (failed) process.exitCode = 1;
})().catch(e => { console.error(e); process.exitCode = 1; });

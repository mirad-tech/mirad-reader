/**
 * 阅读文档 CSP 的真实 Chromium 验证。
 *
 * 背景：EPUB 正文不消毒就进 WebView，`ReaderResourceProvider` 的响应头是唯一的结构性防线。
 * CSP 写错有两种后果：一是漏放行（reader.js 不执行 → 整页白屏），二是多放行（内联脚本仍可执行）。
 * 这两类问题靠 JVM 单测看不出来，必须让浏览器真正按这份策略跑一遍。
 *
 * 策略串直接从 `ReaderResourceProvider.kt` 的 `CONTENT_SECURITY_POLICY` 解析，只把
 * `$ORIGIN` 换成本地测试服务的地址；不手抄指令，避免策略改了测试还"通过"。
 *
 * 运行：npm --prefix tools/browser-checks run test:csp
 */
const fs = require('fs');
const path = require('path');
const http = require('http');
const assert = require('assert');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../..');
const providerPath = path.join(
  root,
  'app/src/main/java/com/mirad/reader/reader/ReaderResourceProvider.kt'
);
const readerJs = require('./reader-source.cjs')();
const readerCss = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.css'), 'utf8');

/** 从生产源码解析出指令列表（保持顺序与内容），只替换 $ORIGIN 占位。 */
function readCspDirectives() {
  const source = fs.readFileSync(providerPath, 'utf8');
  const block = source.match(/CONTENT_SECURITY_POLICY = listOf\(([\s\S]*?)\)\.joinToString/);
  assert.ok(block, '未能在 ReaderResourceProvider.kt 中找到 CONTENT_SECURITY_POLICY');
  const directives = [...block[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  assert.ok(directives.length >= 5, `CSP 指令过少，解析结果可疑：${directives}`);
  return directives;
}

// 1x1 透明 PNG
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

const BRIDGE_STUB = `window.MiradBridge = {
  onReady: function () {}, onState: function () {},
  onChapterChanged: function () {}, onRequestNextChapter: function () {},
  onSelection: function () {}, onAutoScrollEnd: function () {},
  onVisualChanged: function () {}
};`;

/**
 * 优先用 Playwright 自带的 Chromium；没有下载浏览器时退回本机 Chrome / Edge。
 * CSP 是标准行为，三者结论一致，能跑起来比卡在下载上更有价值。
 */
async function launchBrowser() {
  const attempts = [{}, { channel: 'chrome' }, { channel: 'msedge' }];
  let lastError = null;
  for (const extra of attempts) {
    try {
      return await chromium.launch({ headless: true, ...extra });
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}

function chapterHtml() {
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
<style id="mirad-theme">body{background-color:rgb(250,248,243)}</style>
<link rel="stylesheet" href="/reader/reader.css">
<script src="/reader/reader.js"></script>
<script src="/probe.js"></script>
<script>window.__inlineRan = true;</script>
</head><body class="mirad-txt"><div id="mirad-content" data-chapter="0">
<h2 class="mirad-chapter-title">第一章</h2><p>正文段落。</p></div>
<iframe id="frame" src="/frame.html"></iframe>
<!-- on* 属性与 <script> 标签同属脚本执行面，必须一起被拦 -->
<img id="local" src="/pixel.png" onload="window.__handlerRan = true" onerror="window.__handlerRan = true">
</body></html>`;
}

async function main() {
  const directives = readCspDirectives();
  let origin = '';

  const server = http.createServer((req, res) => {
    const url = req.url.split('?')[0];
    const header = { 'Content-Security-Policy': directives.map((d) => d.replace('$ORIGIN', origin)).join('; ') };
    if (url === '/chapter') {
      res.writeHead(200, { ...header, 'Content-Type': 'text/html; charset=utf-8' });
      res.end(chapterHtml());
      return;
    }
    if (url === '/reader/reader.js') {
      res.writeHead(200, { 'Content-Type': 'application/javascript' });
      res.end(`${BRIDGE_STUB}\n${readerJs}`);
      return;
    }
    if (url === '/reader/reader.css') {
      res.writeHead(200, { 'Content-Type': 'text/css' });
      res.end(readerCss);
      return;
    }
    if (url === '/probe.js') {
      res.writeHead(200, { 'Content-Type': 'application/javascript' });
      res.end(`window.__probeLoaded = true;
        fetch('/probe.json').then(r => r.json()).then(() => { window.__fetchOk = true; })
          .catch(() => { window.__fetchOk = false; });`);
      return;
    }
    if (url === '/probe.json') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
      return;
    }
    if (url === '/pixel.png') {
      res.writeHead(200, { 'Content-Type': 'image/png' });
      res.end(PNG);
      return;
    }
    if (url === '/frame.html') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end('<!DOCTYPE html><html><body>frame</body></html>');
      return;
    }
    res.writeHead(404).end('not found');
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;

  const browser = await launchBrowser();
  const page = await browser.newPage();
  let passed = 0;
  const check = (name, ok) => {
    assert.ok(ok, `失败：${name}`);
    passed++;
    console.log(`  ✓ ${name}`);
  };

  try {
    const consoleErrors = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    await page.goto(`${origin}/chapter`, { waitUntil: 'load' });
    await page.waitForFunction('window.__fetchOk !== undefined', null, { timeout: 5000 }).catch(() => {});

    const result = await page.evaluate(() => ({
      inlineRan: window.__inlineRan === true,
      handlerRan: window.__handlerRan === true,
      readerLoaded: typeof window.MiradReader === 'object' && window.MiradReader !== null,
      probeLoaded: window.__probeLoaded === true,
      fetchOk: window.__fetchOk === true,
      themed: getComputedStyle(document.body).backgroundColor === 'rgb(250, 248, 243)',
      stylesheetCount: document.styleSheets.length,
      localImageLoaded: (document.getElementById('local') || {}).naturalWidth === 1,
      frameBlocked: document.getElementById('frame').contentDocument === null
    }));

    console.log(`CSP = ${directives.map((d) => d.replace('$ORIGIN', origin)).join('; ')}\n`);

    check('EPUB 内联 <script> 被拦截', result.inlineRan === false);
    check('EPUB 的 on* 内联事件属性被拦截', result.handlerRan === false);
    check('reader.js 从虚拟源正常加载并暴露 MiradReader', result.readerLoaded);
    check('外部探针脚本（虚拟源）可执行', result.probeLoaded);
    check('connect-src 放行虚拟源的 fetch（滚动补章依赖）', result.fetchOk);
    check('内联 <style> 生效（主题样式依赖 unsafe-inline）', result.themed);
    check('reader.css 已应用', result.stylesheetCount >= 2);
    check('img-src 放行虚拟源图片', result.localImageLoaded);
    check('frame-src 拦截 iframe', result.frameBlocked);

    if (consoleErrors.length) {
      console.log(`\n浏览器控制台错误（供参考）：\n  ${consoleErrors.join('\n  ')}`);
    }
    console.log(`\nCSP 回归：${passed}/9 通过`);
  } finally {
    await browser.close();
    server.close();
  }
}

main().catch((error) => {
  console.error(error.message || error);
  process.exit(1);
});

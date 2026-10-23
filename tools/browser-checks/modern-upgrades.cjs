/**
 * 对 Reviewer 审查意见（P1-4, P1-5, P2-7, P2-9）的真实 Chromium 定向自动化验证脚本。
 *
 * 运行：npm --prefix tools/browser-checks run test:modern
 */
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../..');
const readerJs = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.js'), 'utf8');
const readerCss = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.css'), 'utf8');

const themeCss = `
body { background: #faf8f3; color: #222; font-size: 20px; line-height: 1.8; margin: 0; padding: 0; }
p { margin: 0 0 0.8em 0; text-indent: 2em; }
h2 { text-align: center; margin: 0.5em 0 1em; }
`;

function buildHtml(bodyHtml, chapterIndex = 0) {
  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <style>${readerCss}</style>
  <style id="mirad-theme">${themeCss}</style>
</head>
<body data-chapter="${chapterIndex}"><div id="mirad-content" data-chapter="${chapterIndex}">${bodyHtml}</div><script>
    window.MiradBridge = {
      onReady: function () {},
      onState: function () {},
      onChapterChanged: function () {},
      onSelection: function () {}
    };
  </script><script>${readerJs}</script></body></html>`;
}

async function run() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 412, height: 760 } });
  let passed = 0;

  try {
    // =========================================================================
    // 测试 1: [P2-7] CFI 在 mark 标注注入后的抗破坏性与 TextQuote 自愈验证
    // =========================================================================
    {
      const page = await context.newPage();
      const content = '<p id="target-para">abcdefghijklmnopqrstuvwxyz</p>';
      await page.setContent(buildHtml(content, 0));
      await page.evaluate(() => {
        window.MiradReader.init({ mode: 'SCROLL', windowedScroll: false });
      });

      // 1.1 获取字符 20 的 CFI
      const cfi20 = await page.evaluate(() => {
        return window.MiradReader.getCfi(20);
      });
      console.log('  [P2-7] 字符 20 的 CFI:', cfi20);
      assert(cfi20 && cfi20.includes(':20'), `初始 CFI 应包含偏移 20, 实际为: ${cfi20}`);

      // 1.2 给字符 [2, 5) 添加划线 mark 标注
      await page.evaluate(() => {
        window.MiradReader.renderHighlights([
          { start: 2, end: 5, color: '#ffd666', style: 'UNDERLINE' }
        ]);
      });

      // 验证 DOM 确实被 mark 元素切分
      const hasMark = await page.evaluate(() => {
        return !!document.querySelector('mark.mirad-hl');
      });
      assert(hasMark, '划线 mark 应成功渲染在 DOM 中');

      // 1.3 核心验证：用划线前生成的 CFI 解析偏移，必须精确对齐 20，绝不被截断成 2！
      const resolvedOffset = await page.evaluate((targetCfi) => {
        return window.MiradReader.resolveCfiOffset(targetCfi);
      }, cfi20);

      const jumpResult = await page.evaluate((targetCfi) => {
        return window.MiradReader.gotoCfi(targetCfi, false);
      }, cfi20);

      console.log('  [P2-7] 划线后通过旧 CFI 解析出的字符偏移:', resolvedOffset, '跳转结果:', jumpResult);
      assert.strictEqual(jumpResult, true, 'gotoCfi 应成功跳转');
      assert.strictEqual(resolvedOffset, 20, `划线后通过 CFI 定位必须精确指向字符 20，实际为 ${resolvedOffset}`);

      // 1.4 越界假阳性防护验证：提供明显越界的 CFI
      const invalidResult = await page.evaluate(() => {
        return window.MiradReader.gotoCfi('/4/2/1:999', false);
      });
      assert.strictEqual(invalidResult, false, '越界 CFI 必须返回 false，绝不允许假阳性截断');

      // 1.5 TextQuote 选区自愈验证
      const healedResult = await page.evaluate(() => {
        var quote = { before: 'st', highlight: 'u', after: 'vwx', offset: 19 };
        return window.MiradReader.gotoCfi('/4/99/99:999', false, quote);
      });
      assert.strictEqual(healedResult, true, 'CFI 失效时 TextQuote 自愈应成功恢复定位');

      console.log('PASS [P2-7] CFI 忽略 mark 标签且精准定位，越界防护有效，TextQuote 成功自愈');
      passed++;
      await page.close();
    }

    // =========================================================================
    // 测试 2: 真实滚动事件 -> refreshActive -> scheduleTrim -> trimWindow
    // =========================================================================
    {
      const page = await context.newPage();
      await page.route('**/*', route => route.abort());
      const longPara = '<p>' + '山间的风吹过竹林，清泉石上流。'.repeat(10) + '</p>';
      const sections = Array.from({ length: 6 }, (_, index) =>
        '<section class="mirad-chapter" data-chapter="' + index + '">' + longPara.repeat(10) + '</section>');
      await page.setContent(buildHtml(sections.join(''), 0));
      await page.evaluate(() => {
        MiradBridge.onChapterChanged = index => { window.__activeChapter = index; };
        MiradReader.init({ mode: 'SCROLL', windowedScroll: true, chapterCount: 6 });
      });
      await page.evaluate(async () => {
        await document.fonts.ready;
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const anchor = document.querySelector('section[data-chapter="2"] p:nth-child(3)');
        window.scrollTo(0, anchor.getBoundingClientRect().top + scrollY - 180);
      });
      await page.waitForFunction(() => window.__activeChapter === 2);
      const before = await page.evaluate(() => ({
        top: document.querySelector('section[data-chapter="2"] p:nth-child(3)').getBoundingClientRect().top,
        text: document.querySelector('section[data-chapter="2"] p:nth-child(3)').textContent,
        scrollY,
        chapters: [...document.querySelectorAll('section.mirad-chapter')].map(s => Number(s.dataset.chapter))
      }));
      assert.deepStrictEqual(before.chapters, [0, 1, 2, 3, 4, 5], 'all fixture chapters must exist before production trim');
      assert(before.top >= 0 && before.top < 760, 'the tracked text must be visible before trimming');

      // Observe the real 800ms timer. The test never removes nodes or compensates scrolling.
      await page.waitForFunction(() => !document.querySelector('section[data-chapter="0"]') &&
        !document.querySelector('section[data-chapter="4"]') &&
        !document.querySelector('section[data-chapter="5"]'), null, { timeout: 3000 });
      const after = await page.evaluate(() => ({
        top: document.querySelector('section[data-chapter="2"] p:nth-child(3)').getBoundingClientRect().top,
        text: document.querySelector('section[data-chapter="2"] p:nth-child(3)').textContent,
        active: window.__activeChapter,
        scrollY,
        chapters: [...document.querySelectorAll('section.mirad-chapter')].map(s => Number(s.dataset.chapter))
      }));
      assert.deepStrictEqual(after.chapters, [1, 2, 3], 'production must trim both distant ends');
      assert.strictEqual(after.active, 2, 'trimming must keep the active chapter');
      assert.strictEqual(after.text, before.text, 'the same text must remain mounted');
      assert(after.scrollY > 0 && after.scrollY < before.scrollY, 'removing an earlier chapter must compensate scrollY');
      assert(Math.abs(after.top - before.top) <= 1, 'visible text drift after production trim: ' + (after.top - before.top));
      console.log('PASS production-window-trim-anchor', JSON.stringify({ before, after }));
      passed++;
      await page.close();
    }

    // 下方裁剪必须保留 2.5 屏总高度；固定 fixture 高度只控制输入，不复制裁剪算法。
    {
      const page = await context.newPage();
      await page.route('**/*', route => route.abort());
      const sections = Array.from({ length: 5 }, (_, index) =>
        '<section class="mirad-chapter" data-chapter="' + index +
        '" style="height:450px"><p>第' + index + '章：用于验证下方保留高度。</p></section>');
      await page.setContent(buildHtml(sections.join(''), 0));
      await page.evaluate(() => {
        MiradBridge.onChapterChanged = index => { window.__activeChapter = index; };
        MiradReader.init({ mode: 'SCROLL', windowedScroll: true, chapterCount: 5 });
      });
      await page.evaluate(async () => {
        await document.fonts.ready;
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const active = document.querySelector('section[data-chapter="1"]');
        window.scrollTo(0, active.getBoundingClientRect().top + scrollY + 1);
      });
      await page.waitForFunction(() => window.__activeChapter === 1);
      await page.waitForFunction(() => !document.querySelector('section[data-chapter="4"]'),
        null, { timeout: 3000 });
      const result = await page.evaluate(() => ({
        chapters: [...document.querySelectorAll('section.mirad-chapter')].map(s => Number(s.dataset.chapter)),
        height: document.documentElement.scrollHeight,
        lastHeight: document.querySelector('section[data-chapter="3"]')?.getBoundingClientRect().height || 0,
        viewport: innerHeight,
        active: window.__activeChapter
      }));
      assert.deepStrictEqual(result.chapters, [0, 1, 2, 3], 'keep the extra lower chapter when three chapters are too short');
      assert.strictEqual(result.active, 1);
      assert(result.height >= result.viewport * 2.5, 'retained document must cover 2.5 screens');
      assert(result.height - result.lastHeight < result.viewport * 2.5, 'fixture must exercise the height guard');
      console.log('PASS production-window-trim-height-guard', JSON.stringify(result));
      passed++;
      await page.close();
    }

    // =========================================================================
    // 测试 3: [P1-5] 连续短章自动推进补齐，直至充满视口或触碰书末，避免短章死锁
    // =========================================================================
    {
      const page = await context.newPage();
      const tinyPara = '<p>短小的一句话。</p>';
      await page.route('http://mirad.local/book/1/chapter/0', route => {
        route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: buildHtml(tinyPara, 0) });
      });
      await page.route('**/fragment/*', route => {
        const url = route.request().url();
        const match = url.match(/\/fragment\/(\d+)/);
        const chapterIdx = match ? parseInt(match[1], 10) : 0;
        const html = `<section class="mirad-chapter" data-chapter="${chapterIdx}"><h2>第${chapterIdx}章</h2><p>短章正文内容。</p></section>`;
        route.fulfill({
          status: 200,
          contentType: 'text/html; charset=utf-8',
          body: html
        });
      });

      await page.goto('http://mirad.local/book/1/chapter/0');

      // 初始化窗口滚动，告知全书共有 8 章
      await page.evaluate(() => {
        window.MiradReader.init({
          mode: 'SCROLL',
          windowedScroll: true,
          chapterCount: 8
        });
      });

      // 等待补章循环触发并稳定
      await page.waitForTimeout(600);

      const loadedChapters = await page.evaluate(() => {
        var secs = document.querySelectorAll('section.mirad-chapter');
        var res = [];
        for (var i = 0; i < secs.length; i++) {
          res.push(parseInt(secs[i].getAttribute('data-chapter'), 10));
        }
        return res;
      });

      console.log('  [P1-5] 连续短章场景下已加载的章节列表:', loadedChapters);
      assert(loadedChapters.length >= 3, `连续短章应自动推进挂载至少 3 章，实际挂载: ${loadedChapters.length} 章: ${JSON.stringify(loadedChapters)}`);
      assert(loadedChapters.includes(0) && loadedChapters.includes(1) && loadedChapters.includes(2), '应成功连续推进挂载第 0、1、2 章，彻底打破两章死锁');

      console.log('PASS [P1-5] 连续短章场景下成功沿窗口边界自动推进，打破短章死锁');
      passed++;
      await page.close();
    }

    // =========================================================================
    // 测试 4: [P2-9] EPUB 保留原书样式（windowedScroll: false）保持标准单章路径
    // =========================================================================
    {
      const page = await context.newPage();
      const content = '<p>EPUB 原书样式单章内容。</p>';
      await page.setContent(buildHtml(content, 0));
      await page.evaluate(() => {
        // 当 EPUB 为 ORIGINAL 时，原生端传入 windowedScroll: false
        window.MiradReader.init({ mode: 'SCROLL', windowedScroll: false });
      });

      const isSingleChapter = await page.evaluate(() => {
        // 不应创建多个 section 包装
        return document.querySelectorAll('section.mirad-chapter').length === 0;
      });

      assert(isSingleChapter, 'EPUB ORIGINAL 模式下应禁用窗口引擎，保持标准单章渲染');
      console.log('PASS [P2-9] EPUB 原书样式正确回退并退出窗口引擎');
      passed++;
      await page.close();
    }

    // =========================================================================
    // 测试 5: [Round 2 P2-3] 连续短章加载后请求稳定，无持续循环请求与震荡
    // =========================================================================
    {
      const page = await context.newPage();
      let fetchCount = 0;
      await page.route('http://mirad.local/book/1/chapter/0', route => {
        route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: buildHtml('<p>短小段落。</p>', 0) });
      });
      await page.route('**/fragment/*', route => {
        fetchCount++;
        const url = route.request().url();
        const match = url.match(/\/fragment\/(\d+)/);
        const chapterIdx = match ? parseInt(match[1], 10) : 0;
        route.fulfill({
          status: 200,
          contentType: 'text/html; charset=utf-8',
          body: `<section class="mirad-chapter" data-chapter="${chapterIdx}"><h2>第${chapterIdx}章</h2><p>短章节内容。</p></section>`
        });
      });

      await page.goto('http://mirad.local/book/1/chapter/0');
      await page.evaluate(() => {
        window.MiradReader.init({
          mode: 'SCROLL',
          windowedScroll: true,
          chapterCount: 8
        });
      });

      // 观察 2 秒期间的请求次数增长情况
      await page.waitForTimeout(600);
      const countAt600ms = fetchCount;
      await page.waitForTimeout(1400); // 跨过 800ms trimTimer 触发点两次
      const countAt2000ms = fetchCount;

      console.log(`  [P2-3] 连续短章请求统计: 600ms=${countAt600ms}, 2000ms=${countAt2000ms}`);
      assert(countAt2000ms - countAt600ms <= 2, `短章充满视口后请求必须稳定，绝不能持续循环裁剪与重载！差值: ${countAt2000ms - countAt600ms}`);
      console.log('PASS [Round 2 P2-3] 连续短章裁剪-补齐循环彻底解除，请求完全收敛稳定');
      passed++;
      await page.close();
    }

    // =========================================================================
    // 测试 6: [Round 2 P2-7] CFI 严格语法校验，格式损坏与非法输入必须返回 null/false
    // =========================================================================
    {
      const page = await context.newPage();
      const content = '<p id="p1">一段正常的测试文本。</p>';
      await page.setContent(buildHtml(content, 0));
      await page.evaluate(() => {
        window.MiradReader.init({ mode: 'SCROLL', windowedScroll: false });
      });

      const invalidCfis = [
        'invalid/cfi',
        '/2/4/1:0',               // 缺少 /4/ 根
        '/4/abc/1:0',             // 非数字步长
        '/4/2/1:notanumber',      // 非数字 offset
        '/4/2/1:-10',             // 负数 offset
        '/4/2/4:5',               // 元素节点不允许非零 offset
        '/4/2/2:5',               // 偶数步却指向文本组
        '/4/1/1:5',               // 奇数步却指向元素
        '/4/2/1/1:5'              // 非终止文本路径继续穿透
      ];

      for (const badCfi of invalidCfis) {
        const resOffset = await page.evaluate((c) => window.MiradReader.resolveCfiOffset(c), badCfi);
        const resGoto = await page.evaluate((c) => window.MiradReader.gotoCfi(c, false), badCfi);
        assert.strictEqual(resOffset, -1, `非法 CFI [${badCfi}] resolveCfiOffset 必须返回 -1`);
        assert.strictEqual(resGoto, false, `非法 CFI [${badCfi}] gotoCfi 必须返回 false`);
      }

      console.log('PASS [Round 2 P2-7] CFI 严格语法校验有效，所有损坏格式输入均被安全拒绝');
      passed++;
      await page.close();
    }

    // =========================================================================
    // 测试 7: [Round 2 P2-8] healWithTextQuote 多候选加权消歧验证
    // =========================================================================
    {
      const page = await context.newPage();
      // 在长文本中重复出现 5 次 "他说"
      const content = `
        <p>第零句：微风徐徐，他说这是个好天气。</p>
        <p>第一句：小鸟啼鸣，他说春天已经到来。</p>
        <p>第二句：夕阳西下，他说黄昏分外美丽。</p>
        <p>第三句：清风拂过，他说便转身离去不见踪影。</p>
        <p>第四句：夜幕降临，他说星空如此浩瀚。</p>
      `;
      await page.setContent(buildHtml(content, 0));
      await page.evaluate(() => {
        window.MiradReader.init({ mode: 'SCROLL', windowedScroll: false });
      });

      // 目标定位第 3 句的 "他说"（前有 "清风拂过，"，后有 "便转身离去"）
      const healedTargetOffset = await page.evaluate(() => {
        var quote = {
          before: '清风拂过，',
          highlight: '他说',
          after: '便转身离去',
          offset: 80
        };
        var bestOffset = window.MiradReader.resolveTextQuoteOffset(quote);
        var ok = window.MiradReader.gotoLocator({ text: quote }, false);
        return {
          ok: ok,
          bestOffset: bestOffset,
          fullText: window.MiradReader.getText()
        };
      });

      console.log('  [P2-8] 消歧命中位置:', healedTargetOffset.bestOffset);
      assert.strictEqual(healedTargetOffset.ok, true, 'TextQuote 消歧应成功定位');
      const textAtHit = healedTargetOffset.fullText.substring(healedTargetOffset.bestOffset, healedTargetOffset.bestOffset + 20);
      console.log('  [P2-8] 命中处文本片段:', textAtHit);
      assert(textAtHit.includes('便转身离去'), `TextQuote 消歧必须精确命中第 3 句的"他说"，实际命中在: ${textAtHit}`);

      console.log('PASS [Round 2 P2-8] TextQuote 成功完成多候选加权消歧，精确锁定特定重复短语');
      passed++;
      await page.close();
    }

  } finally {
    await browser.close();
  }

  console.log(`\n========================================`);
  console.log(`全部 ${passed}/8 项专项 Review 定向验证测试全部通过！`);
  console.log(`========================================\n`);
}

run().catch(err => {
  console.error('测试失败:', err);
  process.exit(1);
});

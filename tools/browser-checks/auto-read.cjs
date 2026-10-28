/**
 * 自动阅读 / 上下滚动的真实浏览器验证。
 *
 * 使用 app/src/main/reader/ 的拼接脚本与 reader.css，在 Chromium 里铺真实章节 HTML：
 *  - 手动驱动 requestAnimationFrame 的虚拟时间，检查不同帧率下的位移、长帧钳制；
 *  - 触摸后自动下滑/点按滚动动画立即停止；
 *  - 滚动位置回报的 200ms 节流与停止后最终补报；
 *  - 手势开始时不在边界 8px 内，滑到章末不会换章；从边界继续外滑才换章。
 *
 * 不覆盖 Android WebView 的触摸手感、系统栏安全区域与原生计时器；那些需要模拟器验收。
 *
 * 运行：npm --prefix tools/browser-checks run test:auto-read
 */
const fs = require('fs')
const path = require('path')
const assert = require('assert')
const { chromium } = require('playwright')

const root = path.resolve(__dirname, '../..')
const readerJs = require('./reader-source.cjs')()
const readerCss = fs.readFileSync(path.join(root, 'app/src/main/assets/reader/reader.css'), 'utf8')
const outDir = path.join(root, 'build/verification/auto-read')
fs.mkdirSync(outDir, { recursive: true })

const viewport = { width: 412, height: 760 }
const themeCss = `
body { background: #faf8f3; color: #222; font-size: 20px; line-height: 1.8; margin: 0; padding: 0; }
p { margin: 0 0 0.6em 0; text-indent: 2em; text-align: justify; }
h2 { text-align: center; margin: 0.4em 0 0.8em; }
`
const paragraph = '山间的风吹过竹林，他继续沿着石阶前行。眼前的路转过山腰，远处传来清晰的钟声。'
const longBody = Array.from({ length: 60 }, () => `<p>${paragraph.repeat(12)}</p>`).join('')
const shortBody = '<p>这一章只有几行字，短得不足一屏。</p>'

const fakeClock = `
window.__frames = new Map();
window.__seq = 0;
window.__vt = 0;
window.requestAnimationFrame = function (cb) {
  var id = ++window.__seq;
  window.__frames.set(id, cb);
  return id;
};
window.cancelAnimationFrame = function (id) { window.__frames.delete(id); };
window.__tick = function (dt) {
  window.__vt += dt;
  var due = Array.from(window.__frames.values());
  window.__frames.clear();
  for (var i = 0; i < due.length; i++) {
    try { due[i](window.__vt); } catch (e) { window.__frameError = String(e && e.stack || e); }
  }
};
window.__touch = function (type, y) {
  var e = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperty(e, 'touches', { value: type === 'touchend' || type === 'touchcancel' ? [] : [{ clientY: y, clientX: 200 }] });
  Object.defineProperty(e, 'changedTouches', { value: type === 'touchstart' ? [] : [{ clientY: y, clientX: 200 }] });
  document.dispatchEvent(e);
};
`

async function openPage(browser, body, options = {}) {
  const page = await browser.newPage({ viewport, hasTouch: true })
  page.on('pageerror', err => { console.error('PAGE ERROR:', err) })
  await page.setContent(`<!doctype html><html><head><style>${readerCss}</style><style id="mirad-theme">${themeCss}</style></head><body><div id="mirad-content" data-chapter="${options.chapter || 0}">${body}</div></body></html>`)
  // setContent 复用当前 document，不会重新执行 addInitScript，所以在正文就位后再装虚拟时钟。
  await page.evaluate(fakeClock)
  await page.addScriptTag({ content: readerJs })
  await page.evaluate(({ mode, chapterCount, body }) => {
    window.fetch = function () {
      return Promise.resolve({
        ok: true,
        text: function () {
          return Promise.resolve('<!doctype html><html><body><div id="mirad-content" data-chapter="1"><p>下一章正文。</p></div></body></html>')
        }
      })
    }
    window.MiradBridge = {
      onReady: function () {},
      onState: function (page, pages, offset, len, y) {
        (window.__reports = window.__reports || []).push({ at: performance.now(), page: page, pages: pages, offset: offset, y: y })
      },
      onMetrics: function () {},
      onSelection: function () {},
      onChapterChanged: function (index) { window.__lastChapter = index },
      onRequestNextChapter: function (index) { window.__requestedChapter = index },
      onAutoScrollEnd: function (index) { (window.__autoEnds = window.__autoEnds || []).push(index) }
    }
    MiradReader.init({
      mode: mode || 'SCROLL',
      gap: 40,
      pad: 20,
      colMax: 0,
      padTop: 28,
      padBottom: 32,
      anim: 'NONE',
      chapterCount: chapterCount || 3,
      windowedScroll: false
    })
    MiradReader.gotoOffset(0, false)
    document.addEventListener('touchstart', function () {
      (window.__touchStartY = window.__touchStartY || []).push(window.scrollY)
    }, { passive: true })
  }, { mode: options.mode || 'SCROLL', chapterCount: options.chapterCount || 3, body })
  await page.waitForTimeout(200)
  await page.evaluate(() => { window.__tick(16); window.__tick(16); window.scrollTo(0, 0) })
  await page.waitForTimeout(50)
  return page
}

function approx(actual, expected, tolerance, label) {
  assert(Math.abs(actual - expected) <= tolerance, `${label}: expected ${expected}±${tolerance}, got ${actual}`)
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const results = { browser: browser.version(), cases: [], failures: [] }
  const run = async (name, fn) => {
    try {
      const detail = await fn()
      results.cases.push({ name, ok: true, detail })
      console.log(`PASS ${name}`, detail ? JSON.stringify(detail) : '')
    } catch (e) {
      results.failures.push({ name, error: String(e && e.stack || e) })
      console.error(`FAIL ${name}:`, e)
    }
  }

  // 1) 60fps 下速度接近「秒/屏」。
  await run('auto-scroll-speed-60fps', async () => {
    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(() => {
      var before = MiradReader.getState()
      MiradReader.setAutoScroll(false, 30)
      window.scrollTo(0, 0)
      MiradReader.setAutoScroll(true, 30)
      for (var i = 0; i < 60; i++) window.__tick(16.6667)
      MiradReader.setAutoScroll(false, 30)
      return { y: window.scrollY, before: before }
    })
    await page.waitForTimeout(60)
    // 30 秒/屏、可读高度 700 → 约 23.33px/秒
    approx(detail.y, 23.33, 2, '60fps 1 秒位移')
    await page.close()
    return detail
  })

  // 2) 低帧率：长帧按 50ms 封顶，不会按真实 66.7ms 多走。
  await run('auto-scroll-speed-15fps-clamp', async () => {
    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(() => {
      window.scrollTo(0, 0)
      MiradReader.setAutoScroll(false, 30)
      MiradReader.setAutoScroll(true, 30)
      for (var i = 0; i < 15; i++) window.__tick(66.6667)
      MiradReader.setAutoScroll(false, 30)
      return { y: window.scrollY }
    })
    await page.waitForTimeout(60)
    // 15 帧 × 50ms = 750ms → 约 17.5px，而不是 23.3px
    assert(detail.y >= 14 && detail.y <= 21, `15fps 位移应被钳制在 ~17.5px，实际 ${detail.y}`)
    await page.close()
    return detail
  })

  // 3) 单次超长帧不跳段。
  await run('auto-scroll-long-frame-no-jump', async () => {
    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(() => {
      window.scrollTo(0, 0)
      MiradReader.setAutoScroll(false, 30)
      MiradReader.setAutoScroll(true, 30)
      window.__tick(16) // 第一帧只建立时间基准
      window.__tick(1000)
      MiradReader.setAutoScroll(false, 30)
      return { y: window.scrollY }
    })
    await page.waitForTimeout(60)
    assert(detail.y <= 2, `1000ms 长帧最多按 50ms 计算（约 1px），实际 ${detail.y}`)
    await page.close()
    return detail
  })

  // 4) 手指按下立即停止自动下滑。
  await run('touch-stops-auto-scroll', async () => {
    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(() => {
      window.scrollTo(0, 0)
      MiradReader.setAutoScroll(true, 30)
      for (var i = 0; i < 20; i++) window.__tick(16.6667)
      var moving = window.scrollY
      window.__touch('touchstart', 400)
      window.__touch('touchend', 400)
      for (var j = 0; j < 60; j++) window.__tick(16.6667)
      var after = window.scrollY
      var state = JSON.parse(MiradReader.getState())
      MiradReader.setAutoScroll(false, 30)
      return { moving: moving, after: after, autoScroll: state.autoScroll }
    })
    assert(detail.moving > 0, '触摸前自动下滑应已产生位移')
    assert(detail.after === detail.moving, `触摸后不应继续移动：${detail.moving} -> ${detail.after}`)
    assert(detail.autoScroll === false, '触摸后 JS 自动下滑循环应已停止')
    await page.close()
    return detail
  })

  // 5) 新手势取消正在进行的点按滚动动画。
  await run('touch-cancels-tap-scroll-animation', async () => {    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(() => {
      window.scrollTo(0, 0)
      MiradReader.setAutoScroll(false, 30)
      MiradReader.scrollStep(1)
      window.__tick(16.6667)
      window.__tick(120)
      var moving = window.scrollY
      window.__touch('touchstart', 400)
      window.__touch('touchend', 400)
      for (var i = 0; i < 40; i++) window.__tick(16.6667)
      return { moving: moving, after: window.scrollY }
    })
    assert(detail.moving > 0, '点按滚动应先产生位移')
    assert(detail.after === detail.moving, `触摸取消后动画不应继续：${detail.moving} -> ${detail.after}`)
    await page.close()
    return detail
  })

  // 5) 重复启动不会叠加成两个 rAF 循环；运行中调速只更新速度。
  await run('auto-scroll-single-loop-and-live-speed-change', async () => {
    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(() => {
      window.scrollTo(0, 0)
      MiradReader.setAutoScroll(false, 30)
      MiradReader.setAutoScroll(true, 30)
      MiradReader.setAutoScroll(true, 30)
      MiradReader.setAutoScroll(true, 30)
      for (var i = 0; i < 60; i++) window.__tick(16.6667)
      var afterOneSecond = window.scrollY
      MiradReader.setAutoScroll(true, 60) // 只同步速度，不新建循环
      for (var j = 0; j < 30; j++) window.__tick(16.6667)
      var afterHalfSecondAtHalfSpeed = window.scrollY
      MiradReader.setAutoScroll(false, 30)
      return { afterOneSecond: afterOneSecond, total: afterHalfSecondAtHalfSpeed }
    })
    await page.waitForTimeout(60)
    // 单个循环 1 秒约 23.3px；若重复启动叠加，会接近 45/70px。
    assert(detail.afterOneSecond >= 20 && detail.afterOneSecond <= 26,
      `重复启动不应叠加循环，1 秒位移 ${detail.afterOneSecond}`)
    // 后半秒速度减半（30→60 秒/屏）约 5.8px，总位移约 29px。
    assert(detail.total >= 25 && detail.total <= 34,
      `运行中调速应只改变速度，总位移 ${detail.total}`)
    await page.close()
    return detail
  })

  // 6) 滚动回报 200ms 节流 + 停止后最终补报；自动下滑不逐帧过桥。
  await run('scroll-report-throttled-with-final-position', async () => {
    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(async () => {
      window.__reports = []
      var scrollEvents = 0
      document.addEventListener('scroll', function () { scrollEvents++ }, true)
      var target = 0
      var afterEach = []
      for (var i = 1; i <= 6; i++) {
        target += 40
        window.scrollTo(0, target)
        await new Promise(function (r) { setTimeout(r, 60) })
        afterEach.push(window.scrollY)
      }
      await new Promise(function (r) { setTimeout(r, 350) })
      var reports = window.__reports
      return {
        count: reports.length,
        scrollEvents: scrollEvents,
        afterEach: afterEach,
        finalY: reports.length ? reports[reports.length - 1].y : -1,
        expectedY: window.scrollY,
        offsets: reports.map(function (r) { return r.offset }),
        ys: reports.map(function (r) { return r.y }),
        intervals: reports.slice(1).map(function (r, i) { return Math.round(r.at - reports[i].at) })
      }
    })
    assert(detail.count >= 1, '至少应有一次状态回报')
    assert(detail.finalY === detail.expectedY, `最终回报应使用停止后的位置：${detail.finalY} != ${detail.expectedY} ${JSON.stringify(detail)}`)
    assert(detail.count <= 5, `约 700ms 内回报次数应受 200ms 节流限制，实际 ${detail.count}`)
    assert(detail.offsets[detail.offsets.length - 1] > 0, '最终回报应包含非零字符偏移')
    await page.close()
    return detail
  })

  await run('auto-scroll-does-not-report-every-frame', async () => {
    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(async () => {
      window.__reports = []
      window.scrollTo(0, 0)
      MiradReader.setAutoScroll(true, 30)
      for (var i = 0; i < 60; i++) window.__tick(16.6667)
      await new Promise(function (r) { setTimeout(r, 250) })
      MiradReader.setAutoScroll(false, 30)
      await new Promise(function (r) { setTimeout(r, 250) })
      return { count: window.__reports.length, y: window.scrollY }
    })
    assert(detail.y > 0, '自动下滑应已移动')
    assert(detail.count <= 8, `自动下滑不应逐帧过桥，1 秒内报告 ${detail.count} 次`)
    await page.close()
    return detail
  })

  // 7) 手势开始时在「章末 96px 内」但不在 8px 内：滑到章末也不换章。
  await run('gesture-not-at-8px-boundary-does-not-advance', async () => {
    const page = await openPage(browser, longBody)
    const detail = await page.evaluate(async () => {
      var max = document.documentElement.scrollHeight - document.documentElement.clientHeight
      window.scrollTo(0, Math.max(0, max - 50))
      await new Promise(function (r) { setTimeout(r, 40) })
      window.__touch('touchstart', 600)
      window.__touch('touchend', 500)
      await new Promise(function (r) { setTimeout(r, 300) })
      window.__tick(16.6667)
      window.__tick(16.6667)
      return {
        chapter: document.getElementById('mirad-content').getAttribute('data-chapter'),
        requested: window.__requestedChapter || null,
        moved: window.scrollY
      }
    })
    assert(detail.chapter === '0', `手势开始不在边界 8px 内，不应换章；实际 chapter=${detail.chapter}`)
    await page.close()
    return detail
  })

  // 8) 手势开始时已在章末 8px 内，继续上滑 ≥48px 才换章，且一次手势只换一章。
  await run('gesture-from-8px-boundary-advances-once', async () => {
    const page = await openPage(browser, longBody, { chapter: 0, chapterCount: 3 })
    const detail = await page.evaluate(async () => {
      var max = document.documentElement.scrollHeight - document.documentElement.clientHeight
      window.scrollTo(0, max)
      await new Promise(function (r) { setTimeout(r, 50) })
      window.__touch('touchstart', 600)
      window.__touch('touchend', 500)
      await new Promise(function (r) { setTimeout(r, 80) })
      window.__tick(16.6667)
      window.__tick(16.6667)
      window.__touch('touchend', 400) // 同一次手势的重复 touchend 不应再换章
      await new Promise(function (r) { setTimeout(r, 250) })
      window.__tick(16.6667)
      window.__tick(16.6667)
      return {
        chapter: document.getElementById('mirad-content').getAttribute('data-chapter'),
        changed: window.__lastChapter || null,
        requested: window.__requestedChapter || null,
        touchStartY: window.__touchStartY || [],
        scrollY: window.scrollY,
        max: max,
        ready: JSON.parse(MiradReader.getState()).ready
      }
    })
    assert(detail.chapter === '1', `应从章末换到下一章；实际 chapter=${detail.chapter}`)
    assert(detail.scrollY === 0, `换章后应停在章首；实际 scrollY=${detail.scrollY}`)
    await page.close()
    return detail
  })

  await browser.close()
  fs.writeFileSync(path.join(outDir, 'results.json'), JSON.stringify(results, null, 2))
  if (results.failures.length) {
    console.error(`\n${results.failures.length} case(s) failed`)
    process.exitCode = 1
  } else {
    console.log(`\nAll ${results.cases.length} browser cases passed (${results.browser})`)
  }
}

main().catch(e => {
  console.error(e)
  process.exitCode = 1
})

package com.mirad.reader.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读文档 CSP 的回归保护。
 *
 * EPUB 正文不消毒就进 WebView，这一层是唯一的结构性防线；同时它又必须放行
 * 虚拟源上的脚本、样式、图片、字体，以及滚动模式补章用的 `fetch(fragmentUrl)`。
 * 任何一个方向改错，要么重新打开脚本执行面，要么让滚动模式静默失效。
 */
class ReaderResourceProviderCspTest {

    private val policy = ReaderResourceProvider.CONTENT_SECURITY_POLICY

    @Test
    fun blocksEverythingByDefault() {
        assertTrue(policy.contains("default-src 'none'"))
        assertTrue(policy.contains("object-src 'none'"))
        assertTrue(policy.contains("frame-src 'none'"))
        assertTrue(policy.contains("form-action 'none'"))
    }

    @Test
    fun scriptOnlyFromVirtualOrigin() {
        assertTrue(
            "reader.js 必须能从虚拟源加载",
            policy.contains("script-src ${ReaderResourceProvider.ORIGIN}")
        )
        assertFalse(
            "script-src 绝不能放开内联脚本，否则 EPUB 内的 <script> 仍会执行",
            policy.contains("script-src 'unsafe-inline'") || policy.contains("script-src 'self' 'unsafe-inline'")
        )
    }

    @Test
    fun allowsFragmentFetchForWindowedScroll() {
        // reader.js 的补章走 fetch(https://mirad.local/book/<id>/fragment/<i>)；
        // connect-src 若漏掉虚拟源，滚动模式会在补章时静默失败。
        assertTrue(policy.contains("connect-src ${ReaderResourceProvider.ORIGIN}"))
    }

    @Test
    fun allowsInlineStylesAndLocalAssets() {
        assertTrue("内置主题与 EPUB 自带 <style> 依赖内联样式", policy.contains("style-src 'unsafe-inline'"))
        assertTrue(policy.contains("img-src ${ReaderResourceProvider.ORIGIN}"))
        assertTrue(policy.contains("font-src ${ReaderResourceProvider.ORIGIN}"))
        assertTrue("data: 用于 EPUB 内嵌图片/字体", policy.contains("data:"))
    }

    @Test
    fun allowsBaseTagOfVirtualOrigin() {
        // 章节文档用 <base href> 把 EPUB 相对路径绝对化到虚拟源。
        assertTrue(policy.contains("base-uri 'self'"))
    }

    @Test
    fun policyIsSafeInsideHtmlAttribute() {
        // 同一份策略还会以 <meta http-equiv="Content-Security-Policy" content="..."> 注入，
        // 因此不能含双引号或 & < >，否则会破坏 head 里的标记。
        assertFalse("策略不能含双引号", policy.contains("\""))
        assertFalse("策略不能含 & < >", policy.any { it == '&' || it == '<' || it == '>' })
        assertTrue("多指令必须用分号+空格连接", policy.contains("; "))
    }
}

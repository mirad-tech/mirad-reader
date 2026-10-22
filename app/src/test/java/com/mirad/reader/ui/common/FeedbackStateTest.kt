package com.mirad.reader.ui.common

import org.junit.Assert.*
import org.junit.Test

class FeedbackStateTest {
    @Test fun repeatedTextIsANewEventAndOldTimerCannotClearIt() {
        val state = FeedbackState("reader:1")
        state.show("已保存笔记")
        val first = state.message!!
        state.show("已保存笔记")
        val second = state.message!!
        assertNotEquals(first.id, second.id)
        state.dismiss(first.id)
        assertEquals(second, state.message)
    }

    @Test fun disposedScreenRejectsLateCallback() {
        val state = FeedbackState("reader:1")
        state.show("正在处理")
        state.dispose()
        state.show("旧书的保存结果")
        assertNull(state.message)
    }

    @Test fun feedbackKindsAreTrackedProperly() {
        val state = FeedbackState("test")
        state.show("成功", FeedbackKind.SUCCESS)
        assertEquals(FeedbackKind.SUCCESS, state.message?.kind)
        state.show("错误", FeedbackKind.ERROR)
        assertEquals(FeedbackKind.ERROR, state.message?.kind)
        state.show("信息", FeedbackKind.INFO)
        assertEquals(FeedbackKind.INFO, state.message?.kind)
    }

    @Test fun dismissWithMismatchingIdDoesNothing() {
        val state = FeedbackState("test")
        state.show("提示")
        val current = state.message!!
        state.dismiss(999999L)
        assertEquals(current, state.message)
        state.dismiss(current.id)
        assertNull(state.message)
    }

    @Test fun hidesPrivateFilesystemPathsInErrors() {
        val message = publicError("无法读取 G:\\private\\book.txt 和 /data/user/0/app/books/book.txt")
        assertFalse(message.contains("private"))
        assertFalse(message.contains("/data/"))
    }

    @Test fun errorSanitizingDoesNotDamageUrlSchemes() {
        val text = "网址必须以 http:// 或 https:// 开头"
        assertEquals(text, publicError(text))
    }
}

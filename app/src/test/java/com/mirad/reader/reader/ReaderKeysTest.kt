package com.mirad.reader.reader

import com.mirad.reader.core.model.ReaderConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ReaderKeysTest {
    @After fun cleanUp() { ReaderKeys.pager = null; ReaderKeys.blockedByOverlay = false }

    @Test fun modalInputLeavesVolumeKeysToTheSystemWithoutTurningTheDocument() {
        var turns = 0
        ReaderKeys.pager = { turns += it; true }
        ReaderKeys.blockedByOverlay = true
        assertFalse(ReaderKeys.dispatch(1))
        assertEquals(0, turns)
        ReaderKeys.blockedByOverlay = false
        assertTrue(ReaderKeys.dispatch(-1))
        assertEquals(-1, turns)
    }

    @Test fun noReaderLeavesVolumeKeysToTheSystem() {
        ReaderKeys.pager = null
        assertFalse(ReaderKeys.dispatch(1))
    }

    @Test fun disabledVolumeKeySettingLeavesVolumeKeysToTheSystem() {
        // shouldHandleVolumeKey 是 handleVolumeKey 开头的接管条件：
        // 关闭音量键翻页后即使页面就绪也不接管，按键交还系统调音量。
        assertFalse(ReaderSession.shouldHandleVolumeKey(ready = true, config = ReaderConfig.DEFAULT.copy(volumeKeyPage = false)))
        assertTrue(ReaderSession.shouldHandleVolumeKey(ready = true, config = ReaderConfig.DEFAULT.copy(volumeKeyPage = true)))
        assertFalse(ReaderSession.shouldHandleVolumeKey(ready = false, config = ReaderConfig.DEFAULT))
    }
}

package com.mirad.reader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cleanupDeletesOnlyRetiredFontFilesAndNumberedCopies() {
        val fonts = temporary.newFolder("fonts")
        val retired = listOf("BarlowCondensed-Bold", "BarlowCondensed-Bold.ttf", "barlowcondensed-bold-1.OTF", "BarlowCondensed-Bold.woff2")
            .map { name -> fonts.resolve(name).apply { writeText("font") } }
        val retained = listOf("MyFont.ttf", "BarlowCondensed-BoldItalic.ttf", "BarlowCondensed-Bold.txt")
            .map { name -> fonts.resolve(name).apply { writeText("keep") } }
        val directory = fonts.resolve("BarlowCondensed-Bold.ttc").apply { mkdir() }
        val nested = directory.resolve("BarlowCondensed-Bold.ttf").apply { writeText("keep") }

        removeRetiredPreviewFonts(fonts)
        removeRetiredPreviewFonts(fonts)

        retired.forEach { assertFalse("应移除 ${it.name}", it.exists()) }
        retained.forEach { assertTrue("应保留 ${it.name}", it.readText() == "keep") }
        assertTrue(directory.isDirectory)
        assertTrue(nested.readText() == "keep")
    }
}

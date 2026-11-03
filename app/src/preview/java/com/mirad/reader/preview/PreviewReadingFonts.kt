package com.mirad.reader.preview

import com.mirad.reader.core.model.ReadingFont
import com.mirad.reader.core.model.ReadingFonts

/** 两个预览入口共用真实内置字体目录项，不依赖业务数据库或导入目录。 */
internal fun previewReadingFonts(): List<ReadingFont> =
    listOf(
        ReadingFonts.BUNDLED_WENKAI, ReadingFonts.BUNDLED_SERIF,
        ReadingFonts.BUNDLED_SANS, ReadingFonts.BUNDLED_FANGSONG
    ).map { asset ->
        ReadingFont(ReadingFonts.bundledLabel(asset.substringAfterLast('/')), assetName = asset)
    }

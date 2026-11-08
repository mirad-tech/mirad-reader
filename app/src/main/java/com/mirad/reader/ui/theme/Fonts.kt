package com.mirad.reader.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import com.mirad.reader.R

/** 独立的界面字体资源，不进入阅读字体枚举或改变阅读默认字体。 */
val MiradSerif = FontFamily(Font(R.font.mirad_ui_serif, FontWeight.Normal))

object MiradTextStyles {
    val pageTitle = MiradTypography.headlineSmall.copy(
        fontFamily = MiradSerif,
        fontWeight = FontWeight.Normal,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    )
}

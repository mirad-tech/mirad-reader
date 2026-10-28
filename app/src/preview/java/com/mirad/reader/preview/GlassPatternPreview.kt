package com.mirad.reader.preview

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirad.reader.core.model.*
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.reader.*
import com.mirad.reader.ui.shelf.*
import com.mirad.reader.ui.theme.MiradTheme

@Composable
internal fun MixedPatternPreview(
    baseBgColorHex: String = "#FFFFFF",
    blockColorHex: String = "#0000FF",
    blockStartX: Float = 2f / 8f,
    blockStartY: Float = 2f / 8f,
    blockWidthFrac: Float = 3f / 8f,
    blockHeightFrac: Float = 3f / 8f,
    textOffsetXDp: Float = 162f,
    textOffsetYDp: Float = 150f
) {
    val baseColor = remember(baseBgColorHex) {
        val raw = runCatching { android.graphics.Color.parseColor(baseBgColorHex) }
            .getOrDefault(android.graphics.Color.WHITE)
        Color(raw)
    }
    val blockColor = remember(blockColorHex) {
        val raw = runCatching { android.graphics.Color.parseColor(blockColorHex) }
            .getOrDefault(android.graphics.Color.BLUE)
        Color(raw)
    }
    val source = LocalGlassBackdrop.current
    LaunchedEffect(source, blockStartX, blockStartY, textOffsetXDp, textOffsetYDp) {
        kotlinx.coroutines.delay(2000)
        Log.i("PatternAcceptance", "stats ${source?.statsSummary()}")
    }
    Box(
        Modifier.fillMaxSize().background(baseColor),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(360.dp).onGloballyPositioned {
                val pos = it.positionOnScreen()
                Log.i("PatternAcceptance", "rect x=${pos.x} y=${pos.y} w=${it.size.width} h=${it.size.height}")
            }
        ) {
            Canvas(Modifier.fillMaxSize().glassBackdropSource()) {
                drawRect(baseColor)
                drawRect(
                    blockColor,
                    topLeft = Offset(size.width * blockStartX, size.height * blockStartY),
                    size = Size(size.width * blockWidthFrac, size.height * blockHeightFrac)
                )
            }
            GlassSurface(modifier = Modifier.fillMaxSize(), style = GlassStyle.Panel) {
                Text(
                    "I",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 14.sp),
                    modifier = Modifier.offset(x = textOffsetXDp.dp, y = textOffsetYDp.dp)
                )
            }
        }
    }
}

class PatternAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val transparency = intent.getIntExtra("transparency", 100)
        val baseBgColorHex = intent.getStringExtra("baseBgColor") ?: if (dark) "#15181A" else "#FFFFFF"
        val blockColorHex = intent.getStringExtra("blockColor") ?: if (dark) "#AAAAAA" else "#0000FF"
        val blockStartX = intent.getFloatExtra("blockStartX", 2f / 8f)
        val blockStartY = intent.getFloatExtra("blockStartY", 2f / 8f)
        val blockWidthFrac = intent.getFloatExtra("blockWidthFrac", 3f / 8f)
        val blockHeightFrac = intent.getFloatExtra("blockHeightFrac", 3f / 8f)
        val textOffsetXDp = intent.getFloatExtra("textOffsetX", 162f)
        val textOffsetYDp = intent.getFloatExtra("textOffsetY", 150f)
        enableEdgeToEdge()
        setContent {
            MiradTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(clarityPercent = transparency)) {
                    GlassBackdropHost {
                        MixedPatternPreview(
                            baseBgColorHex = baseBgColorHex,
                            blockColorHex = blockColorHex,
                            blockStartX = blockStartX,
                            blockStartY = blockStartY,
                            blockWidthFrac = blockWidthFrac,
                            blockHeightFrac = blockHeightFrac,
                            textOffsetXDp = textOffsetXDp,
                            textOffsetYDp = textOffsetYDp
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColorContrastPreview(backdropColorHex: String) {
    val parsedColor = remember(backdropColorHex) {
        val raw = runCatching { android.graphics.Color.parseColor(backdropColorHex) }
            .getOrDefault(android.graphics.Color.BLUE)
        androidx.compose.ui.graphics.Color(raw)
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource()
                .background(parsedColor)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 64.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            GlassSurface(
                style = GlassStyle.Toolbar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "工具栏主文字可读性检验",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "工具栏次要文字与状态说明 100%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            GlassSurface(
                style = GlassStyle.Panel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "面板主文字对比度检验",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "面板次要文字：高饱和背景下的可读性保护（不低于 4.5:1）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "底部区域次要文字可读性采样",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

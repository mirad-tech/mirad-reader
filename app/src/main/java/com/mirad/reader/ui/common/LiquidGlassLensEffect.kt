package com.mirad.reader.ui.common

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 从 Compose [Outline] 提取逐角圆角半径（px）；复杂任意路径返回 null 走稳定回退。
 */
internal fun extractGlassCornerRadii(outline: Outline): GlassCornerRadii? = when (outline) {
    is Outline.Rounded -> {
        val r = outline.roundRect
        GlassCornerRadii(
            topLeft = r.topLeftCornerRadius.x,
            topRight = r.topRightCornerRadius.x,
            bottomRight = r.bottomRightCornerRadius.x,
            bottomLeft = r.bottomLeftCornerRadius.x
        )
    }
    is Outline.Rectangle -> GlassCornerRadii(0f, 0f, 0f, 0f)
    is Outline.Generic -> null
}

/**
 * API 33+ 统一单 Pass AGSL 透镜渲染引擎（参考 Liquid-Glass-Android 2.0 架构）。
 *
 * 在单个着色器内完成：
 * 1. 逐角圆角矩形 SDF（支持贴屏平边外扩）与 1.5px 抗锯齿覆盖率；
 * 2. SDF 数值梯度屏幕空间外法线 + 逆幂斜面剖面（`falloff = 2.0`）；
 * 3. 沿法线向内压缩折射 + 可选 3-tap 法线方向柔化 + 法线 R/G/B 光谱色散；
 * 4. 非线性 Vibrancy 饱和度曲线 + 既定角色底色 Tint；
 * 5. 共享法线场的 iOS 26 双瓣定向亮线（30°/210°）与纸白亮背景自适应对比。
 */
@RequiresApi(33)
internal class LiquidGlassLensEngine {
    companion object {
        private const val TAG = "LiquidGlassLensEngine"

        @Volatile
        private var shaderBroken = false

        fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderBroken

        private const val LENS_AGSL = """
            uniform shader content;
            uniform float  margin;
            uniform float2 viewSize;
            uniform float4 shape1L;
            uniform float4 radii1;
            uniform float  bevel;
            uniform float  refractPx;
            uniform float  falloff;
            uniform float  rimSoft;
            uniform float  dispersion;
            uniform float2 lightDir;
            uniform float  specStrength;
            uniform float  rimBandMax;
            uniform float  hairCenter;
            uniform float  hairHalfW;
            uniform float  vibrancy;
            uniform float4 tintColor;
            uniform float  press;
            uniform float2 sampleLo;
            uniform float2 sampleHi;

            float sdRoundedBox4(float2 p, float2 b, float4 r) {
                float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
                float2 q = abs(p) - b + rx;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
            }

            half4 main(float2 coord) {
                float2 p = coord - float2(margin, margin);
                if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {
                    return half4(0.0);
                }
                float d = sdRoundedBox4(p - shape1L.xy, shape1L.zw, radii1);
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

                // SDF 数值梯度 → 屏幕空间外法线
                float2 n = float2(
                    sdRoundedBox4(p + float2(1.0, 0.0) - shape1L.xy, shape1L.zw, radii1) -
                        sdRoundedBox4(p - float2(1.0, 0.0) - shape1L.xy, shape1L.zw, radii1),
                    sdRoundedBox4(p + float2(0.0, 1.0) - shape1L.xy, shape1L.zw, radii1) -
                        sdRoundedBox4(p - float2(0.0, 1.0) - shape1L.xy, shape1L.zw, radii1)
                );
                float nLen = length(n);
                if (nLen > 0.0001) {
                    n = n / nLen;
                } else {
                    n = float2(0.0, -1.0);
                }

                // 逆幂（引力透镜）厚度剖面：t=0 边缘 -> 1，t=1 平坦内部 -> 0
                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float slope;
                if (falloff > 0.001) {
                    float gB = pow(5.0, -falloff);
                    slope = clamp((pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB), 0.0, 1.0);
                } else {
                    float edge = 1.0 - t;
                    slope = edge * edge;
                }

                // 沿法线向内压缩折射（默认 iOS 26 风格），按压/拖拽动态增强
                float refr = refractPx * (1.0 + 0.6 * press);
                float2 offset = -n * (slope * refr);

                // 沿法线方向 R/G/B 光谱色散并严格钳制到录制安全区
                float2 cR = clamp(coord + offset * (1.0 - dispersion * slope), sampleLo, sampleHi);
                float2 cG = clamp(coord + offset, sampleLo, sampleHi);
                float2 cB = clamp(coord + offset * (1.0 + dispersion * slope), sampleLo, sampleHi);

                float3 col;
                if (rimSoft > 0.01 && slope > 0.001) {
                    float2 sm = n * (rimSoft * slope);
                    float2 cR1 = clamp(cR - sm, sampleLo, sampleHi);
                    float2 cR2 = clamp(cR + sm, sampleLo, sampleHi);
                    float2 cG1 = clamp(cG - sm, sampleLo, sampleHi);
                    float2 cG2 = clamp(cG + sm, sampleLo, sampleHi);
                    float2 cB1 = clamp(cB - sm, sampleLo, sampleHi);
                    float2 cB2 = clamp(cB + sm, sampleLo, sampleHi);
                    half4 sR0 = content.eval(cR);
                    half4 sR1 = content.eval(cR1);
                    half4 sR2 = content.eval(cR2);
                    half4 sG0 = content.eval(cG);
                    half4 sG1 = content.eval(cG1);
                    half4 sG2 = content.eval(cG2);
                    half4 sB0 = content.eval(cB);
                    half4 sB1 = content.eval(cB1);
                    half4 sB2 = content.eval(cB2);
                    col = float3(
                        float(sR0.r + sR1.r + sR2.r) / max(float(sR0.a + sR1.a + sR2.a), 0.001),
                        float(sG0.g + sG1.g + sG2.g) / max(float(sG0.a + sG1.a + sG2.a), 0.001),
                        float(sB0.b + sB1.b + sB2.b) / max(float(sB0.a + sB1.a + sB2.a), 0.001)
                    );
                } else {
                    half4 sR = content.eval(cR);
                    half4 sG = content.eval(cG);
                    half4 sB = content.eval(cB);
                    col = float3(
                        float(sR.r) / max(float(sR.a), 0.001),
                        float(sG.g) / max(float(sG.a), 0.001),
                        float(sB.b) / max(float(sB.a), 0.001)
                    );
                }
                col = clamp(col, float3(0.0), float3(1.0));

                // 非线性 Vibrancy 饱和度曲线：低饱和多提，高饱和与极亮高光保护
                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                if (vibrancy <= 1.0) {
                    col = mix(float3(lum), col, vibrancy);
                } else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                    float amount = 1.0 + (vibrancy - 1.0) * mix(0.3, 1.0, room * hl);
                    col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
                }

                // 锁定角色底色叠加（位于高光之前，避免盖灰边缘亮线）
                col = mix(col, tintColor.rgb, tintColor.a);

                // 共享法线双瓣定向镜面高光（迎光侧 + 背光侧内壁反射，侧向归零）
                float facing = dot(n, -lightDir);
                float lobeF = (facing > 0.0) ? pow(facing, 4.5) : 0.0;
                float lobeB = (facing < 0.0) ? pow(-facing, 4.5) : 0.0;

                float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);
                float hair = clamp(1.0 - abs(d + hairCenter) / max(hairHalfW, 0.5), 0.0, 1.0) * cov;
                float glowStart = hairCenter + hairHalfW;
                float glowIn = clamp((-d - hairCenter) / max(hairHalfW, 0.5), 0.0, 1.0);
                float glowT = clamp(1.0 - (-d - glowStart) / max(bandW, 0.001), 0.0, 1.0);
                float glow = glowIn * ((glowT > 0.0) ? pow(glowT, 1.5) : 0.0) * cov;

                float rimLobes = lobeF + lobeB;
                float pressAtten = 1.0 - 0.35 * press;
                float spec = (hair * 0.68 * rimLobes + glow * 0.11 * lobeF) * specStrength * pressAtten;

                // 浅色纸白背景自适应：极亮区域平滑收敛出细腻折射对比边，保持轮廓清晰
                float rimLum = dot(col, float3(0.2126, 0.7152, 0.0722));
                float brightBg = smoothstep(0.74, 0.96, rimLum);
                float3 lit = clamp(col + float3(spec), float3(0.0), float3(1.0));
                float edgeContrast = (hair * (0.14 * rimLobes + 0.05) + glow * 0.03 * lobeF) * specStrength * pressAtten;
                float3 brightRim = col * (1.0 - edgeContrast);
                col = mix(lit, brightRim, brightBg * 0.72);

                col = clamp(col, float3(0.0), float3(1.0));
                return half4(half3(col * cov), half(cov));
            }
        """
    }

    private var shader: RuntimeShader? = null
    private var lastUniforms: GlassLensUniforms? = null
    private var cachedEffect: RenderEffect? = null
    private var lastBlurRadius = -1f
    private var cachedBlur: RenderEffect? = null

    fun effect(uniforms: GlassLensUniforms): RenderEffect? {
        if (shaderBroken) return null
        if (uniforms == lastUniforms && cachedEffect != null) {
            return cachedEffect
        }
        return try {
            val sh = shader ?: RuntimeShader(LENS_AGSL).also { shader = it }
            sh.setFloatUniform("margin", uniforms.paddingPx)
            sh.setFloatUniform("viewSize", uniforms.widthPx, uniforms.heightPx)
            sh.setFloatUniform("shape1L", uniforms.lensCX, uniforms.lensCY, uniforms.lensHW, uniforms.lensHH)
            sh.setFloatUniform("radii1", uniforms.radiusTL, uniforms.radiusTR, uniforms.radiusBR, uniforms.radiusBL)
            sh.setFloatUniform("bevel", uniforms.bevelPx)
            sh.setFloatUniform("refractPx", uniforms.refractPx)
            sh.setFloatUniform("falloff", uniforms.falloff)
            sh.setFloatUniform("rimSoft", uniforms.rimSoftPx)
            sh.setFloatUniform("dispersion", uniforms.dispersion)
            sh.setFloatUniform("lightDir", uniforms.lightX, uniforms.lightY)
            sh.setFloatUniform("specStrength", uniforms.specStrength)
            sh.setFloatUniform("rimBandMax", uniforms.rimBandMaxPx)
            sh.setFloatUniform("hairCenter", uniforms.hairCenterPx)
            sh.setFloatUniform("hairHalfW", uniforms.hairHalfWidthPx)
            sh.setFloatUniform("vibrancy", uniforms.vibrancy)
            sh.setFloatUniform("tintColor", uniforms.tintR, uniforms.tintG, uniforms.tintB, uniforms.tintA)
            sh.setFloatUniform("press", uniforms.press)
            sh.setFloatUniform("sampleLo", uniforms.sampleLoX, uniforms.sampleLoY)
            sh.setFloatUniform("sampleHi", uniforms.sampleHiX, uniforms.sampleHiY)

            val lens = RenderEffect.createRuntimeShaderEffect(sh, "content")
            val combined = if (uniforms.blurRadiusPx > 0.01f) {
                val blur = if (uniforms.blurRadiusPx == lastBlurRadius && cachedBlur != null) {
                    cachedBlur!!
                } else {
                    RenderEffect.createBlurEffect(
                        uniforms.blurRadiusPx,
                        uniforms.blurRadiusPx,
                        Shader.TileMode.CLAMP
                    ).also {
                        lastBlurRadius = uniforms.blurRadiusPx
                        cachedBlur = it
                    }
                }
                RenderEffect.createChainEffect(lens, blur)
            } else {
                lens
            }
            lastUniforms = uniforms
            cachedEffect = combined
            combined
        } catch (t: Throwable) {
            Log.e(TAG, "Lens AGSL build failed, falling back permanently", t)
            shaderBroken = true
            clear()
            null
        }
    }

    fun clear() {
        shader = null
        lastUniforms = null
        cachedEffect = null
        lastBlurRadius = -1f
        cachedBlur = null
    }
}

/**
 * API 31–32 或 AGSL 降级路径的轻量双瓣定向边缘轮廓（无死板全周边框，仅贴边单层渐变）。
 */
internal fun DrawScope.drawFallbackDualLobeRim(
    outline: Outline,
    isDark: Boolean,
    opacity: Float,
    flatEdges: GlassFlatEdges = GlassFlatEdges.None
) {
    if (opacity >= 0.98f || size.width <= 1f || size.height <= 1f) return
    val opticalPresence = if (opacity > 0.75f) ((1f - opacity) / 0.25f).coerceIn(0f, 1f) else 1f
    if (opticalPresence <= 0.01f) return

    val strokeWidth = 2.dp.toPx()
    val effectiveOutline = if (flatEdges.hasAny && outline is Outline.Rounded) {
        val r = outline.roundRect
        val pad = strokeWidth * 2f
        Outline.Rounded(
            RoundRect(
                left = r.left - if (flatEdges.left) pad else 0f,
                top = r.top - if (flatEdges.top) pad else 0f,
                right = r.right + if (flatEdges.right) pad else 0f,
                bottom = r.bottom + if (flatEdges.bottom) pad else 0f,
                topLeftCornerRadius = if (flatEdges.top || flatEdges.left) CornerRadius.Zero else r.topLeftCornerRadius,
                topRightCornerRadius = if (flatEdges.top || flatEdges.right) CornerRadius.Zero else r.topRightCornerRadius,
                bottomRightCornerRadius = if (flatEdges.bottom || flatEdges.right) CornerRadius.Zero else r.bottomRightCornerRadius,
                bottomLeftCornerRadius = if (flatEdges.bottom || flatEdges.left) CornerRadius.Zero else r.bottomLeftCornerRadius
            )
        )
    } else {
        outline
    }

    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val halfProj = cx * GLASS_LIGHT_DEFAULT_X + cy * GLASS_LIGHT_DEFAULT_Y
    val dx = halfProj * GLASS_LIGHT_DEFAULT_X
    val dy = halfProj * GLASS_LIGHT_DEFAULT_Y

    val peakAlpha = (if (isDark) 0.34f else 0.12f) * opticalPresence
    val backAlpha = (if (isDark) 0.26f else 0.08f) * opticalPresence
    val baseColor = if (isDark) Color.White else Color(0xFF191D1B)

    val brush = Brush.linearGradient(
        0.0f to baseColor.copy(alpha = peakAlpha),
        0.22f to baseColor.copy(alpha = peakAlpha * 0.35f),
        0.42f to Color.Transparent,
        0.58f to Color.Transparent,
        0.78f to baseColor.copy(alpha = backAlpha * 0.35f),
        1.0f to baseColor.copy(alpha = backAlpha),
        start = Offset(cx - dx, cy - dy),
        end = Offset(cx + dx, cy + dy)
    )
    drawOutline(outline = effectiveOutline, brush = brush, style = Stroke(width = strokeWidth))
}

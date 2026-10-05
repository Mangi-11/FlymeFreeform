package io.github.mangi.flymefreeform.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 扇形条目的绘制配色；“更多”数值取自同模式下 ColorOS 17 工具栏溢出按钮的实机测量。 */
@Immutable
internal data class RadialItemColors(
    val iconShadeAlpha: Float,
    val morePlateTop: Color,
    val morePlateBottom: Color,
    val moreRim: Color,
    val moreDot: Color,
    val moreShadow: Color,
    val moreShadowSpreadFraction: Float,
    val moreShadowOffsetFraction: Float,
)

private val LightRadialItemColors =
    RadialItemColors(
        iconShadeAlpha = 0f,
        morePlateTop = Color(0xFFF7F7F8),
        morePlateBottom = Color(0xFFF7F7F8),
        moreRim = Color.White,
        moreDot = Color(0xFF181818),
        moreShadow = Color.Black.copy(alpha = 0.05f),
        moreShadowSpreadFraction = 0.25f,
        moreShadowOffsetFraction = 0.03f,
    )

// 深色下按钮外沿是向下的浅色微光而不是阴影；应用图标压暗以免在黑色遮罩上刺眼。
private val DarkRadialItemColors =
    RadialItemColors(
        iconShadeAlpha = 0x1F / 255f,
        morePlateTop = Color(0xFF1F1F1F),
        morePlateBottom = Color(0xFF282828),
        moreRim = Color.White.copy(alpha = 0.12f),
        moreDot = Color(0xFFE9E9E9),
        moreShadow = Color.White.copy(alpha = 0.08f),
        moreShadowSpreadFraction = 0.12f,
        moreShadowOffsetFraction = 0.06f,
    )

internal val LocalRadialItemColors = staticCompositionLocalOf { LightRadialItemColors }

@Composable
internal fun CornerOverlayTheme(content: @Composable () -> Unit) {
    // 深浅色只在这里解析一次，Miuix 配色与扇形配色共用同一结果。
    val dark = isSystemInDarkTheme()
    val controller =
        remember(dark) {
            ThemeController(
                colorSchemeMode = ColorSchemeMode.System,
                lightColors =
                    lightColorScheme(
                        surface = Color(0xFFF8F8FA),
                        onSurface = Color(0xFF232326),
                    ),
                darkColors =
                    darkColorScheme(
                        surface = Color(0xFF242427),
                        onSurface = Color(0xFFF2F2F4),
                    ),
                isDark = dark,
            )
        }
    CompositionLocalProvider(LocalRadialItemColors provides if (dark) DarkRadialItemColors else LightRadialItemColors) {
        MiuixTheme(controller = controller, content = content)
    }
}

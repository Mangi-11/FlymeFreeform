package io.github.mangi.flymefreeform.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
internal fun CornerOverlayTheme(content: @Composable () -> Unit) {
    val controller =
        remember {
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
            )
        }
    MiuixTheme(controller = controller, content = content)
}

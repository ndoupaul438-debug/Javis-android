package com.javis.ui

import android.content.Context
import androidx.compose.ui.graphics.Color

enum class JavisThemeId(
    val title: String,
    val description: String
) {
    OBSIDIAN_LUXE(
        "OBSIDIAN LUXE",
        "Titanium black with electric blue"
    ),
    BLACK_DIAMOND(
        "BLACK DIAMOND",
        "Glass black with blue-violet energy"
    ),
    J_CORE(
        "J CORE",
        "Pure minimalist Javis identity"
    ),
    NEURAL_COMMAND(
        "NEURAL COMMAND",
        "Futuristic AI command center"
    ),
    STEALTH(
        "STEALTH",
        "Ultra-minimal black-on-black"
    ),
    JAVIS_SIGNATURE(
        "JAVIS SIGNATURE",
        "Dynamic orb-focused identity"
    )
}

data class JavisThemePalette(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val text: Color,
    val muted: Color,
    val orbGlow: Color,
    val border: Color
)

fun paletteFor(theme: JavisThemeId): JavisThemePalette {
    return when (theme) {
        JavisThemeId.OBSIDIAN_LUXE -> JavisThemePalette(
            Color(0xFF05070B),
            Color(0xFF0C1017),
            Color(0xFF151B24),
            Color(0xFF45C7FF),
            Color(0xFF7C8CFF),
            Color(0xFFB9C7D8),
            Color(0xFFF5F8FC),
            Color(0xFF8793A5),
            Color(0xFF45C7FF),
            Color(0xFF2B394A)
        )

        JavisThemeId.BLACK_DIAMOND -> JavisThemePalette(
            Color(0xFF030308),
            Color(0xFF0B0B13),
            Color(0xFF141421),
            Color(0xFF4CC9FF),
            Color(0xFF8B5CFF),
            Color(0xFFC5B7FF),
            Color(0xFFF8F8FF),
            Color(0xFF8C8CA5),
            Color(0xFF765CFF),
            Color(0xFF29243D)
        )

        JavisThemeId.J_CORE -> JavisThemePalette(
            Color(0xFF080A0E),
            Color(0xFF101318),
            Color(0xFF171B22),
            Color(0xFF58C8FF),
            Color(0xFF58C8FF),
            Color(0xFFE5EEF5),
            Color(0xFFF7FAFC),
            Color(0xFF8E99A8),
            Color(0xFF58C8FF),
            Color(0xFF29313B)
        )

        JavisThemeId.NEURAL_COMMAND -> JavisThemePalette(
            Color(0xFF02060A),
            Color(0xFF071118),
            Color(0xFF0B1B24),
            Color(0xFF00D9FF),
            Color(0xFF00FFA8),
            Color(0xFF7CFFDC),
            Color(0xFFEFFFFB),
            Color(0xFF739A9B),
            Color(0xFF00D9FF),
            Color(0xFF16414B)
        )

        JavisThemeId.STEALTH -> JavisThemePalette(
            Color(0xFF030303),
            Color(0xFF080808),
            Color(0xFF0D0D0D),
            Color(0xFFE6E6E6),
            Color(0xFF8E8E8E),
            Color(0xFFBDBDBD),
            Color(0xFFF4F4F4),
            Color(0xFF6F6F6F),
            Color(0xFFBDBDBD),
            Color(0xFF202020)
        )

        JavisThemeId.JAVIS_SIGNATURE -> JavisThemePalette(
            Color(0xFF04060C),
            Color(0xFF0A0E18),
            Color(0xFF12182A),
            Color(0xFF42D9FF),
            Color(0xFF7A63FF),
            Color(0xFF55F2C2),
            Color(0xFFF5FAFF),
            Color(0xFF8290A7),
            Color(0xFF42D9FF),
            Color(0xFF28364F)
        )
    }
}

object JavisThemeStore {
    private const val PREFS = "javis_visual_preferences"
    private const val KEY_THEME = "theme"

    fun load(context: Context): JavisThemeId {
        val value = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, JavisThemeId.BLACK_DIAMOND.name)

        return runCatching {
            JavisThemeId.valueOf(value ?: JavisThemeId.BLACK_DIAMOND.name)
        }.getOrDefault(JavisThemeId.BLACK_DIAMOND)
    }

    fun save(context: Context, theme: JavisThemeId) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.name)
            .apply()
    }
}

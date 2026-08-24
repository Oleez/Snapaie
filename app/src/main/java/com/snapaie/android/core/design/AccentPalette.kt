package com.snapaie.android.core.design

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The accent the whole app is tinted with.
 *
 * One choice drives the primary, the secondary and the glass tint together, because the
 * three have to agree — letting someone pick them separately is how an app ends up looking
 * broken rather than personalised. Each entry is a triad that was chosen to sit well on
 * both the dark and the light ground.
 */
enum class AccentPalette(
    val id: String,
    val label: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
) {
    Mint("mint", "Mint", Color(0xFF6EE7C7), Color(0xFFFFD66B), Color(0xFFAFC7FF)),
    Ocean("ocean", "Ocean", Color(0xFF5AC8FA), Color(0xFF64D2FF), Color(0xFFA5B4FC)),
    Violet("violet", "Violet", Color(0xFFA78BFA), Color(0xFFF0ABFC), Color(0xFF93C5FD)),
    Sunset("sunset", "Sunset", Color(0xFFFF9F6B), Color(0xFFFFC46B), Color(0xFFFF8FA3)),
    Rose("rose", "Rose", Color(0xFFFF8FA3), Color(0xFFFFC2D1), Color(0xFFC4B5FD)),
    Lime("lime", "Lime", Color(0xFFB6F09C), Color(0xFFE7F98C), Color(0xFF9CE7C4)),
    Graphite("graphite", "Graphite", Color(0xFFD7DEE3), Color(0xFFAEB9C2), Color(0xFF8FA3B0)),
    ;

    /** The wordmark and header sweep, so the brand carries the chosen accent too. */
    val brandBrush: Brush get() = Brush.linearGradient(listOf(primary, tertiary, secondary))

    /** A soft wash for glass tinting and screen backgrounds. */
    val glow: Color get() = primary.copy(alpha = 0.16f)

    companion object {
        fun fromStored(value: String?): AccentPalette =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) } ?: Mint
    }
}

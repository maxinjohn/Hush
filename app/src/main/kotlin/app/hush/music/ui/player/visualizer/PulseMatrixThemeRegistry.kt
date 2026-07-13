package app.hush.music.ui.player.visualizer

import androidx.compose.ui.graphics.Color

enum class PulseMatrixThemeId(val storageKey: String, val displayName: String) {
    NEON("neon", "Neon"),
    AMBER("amber", "Amber"),
    CYAN("cyan", "Cyan"),
    EMERALD("emerald", "Emerald"),
    CRIMSON("crimson", "Crimson"),
    VIOLET("violet", "Violet"),
    ICE("ice", "Ice"),
    AURORA("aurora", "Aurora"),
}

data class PulseMatrixThemeStyle(
    val id: PulseMatrixThemeId,
    val barColors: List<Color>,
    val peakCapColor: Color = Color.White,
    val glowEnabled: Boolean = true,
    val barAlpha: Float = 0.85f,
    val peakCapAlpha: Float = 0.9f,
    val bottomScrimAlpha: Float = 0.15f,
    val segmentBrightness: Float = 1.0f,
)

object PulseMatrixThemeRegistry {
    private val themes = mapOf(
        PulseMatrixThemeId.NEON to PulseMatrixThemeStyle(
            id = PulseMatrixThemeId.NEON,
            barColors = listOf(
                Color(0xFFFF00FF),
                Color(0xFFFF0088),
                Color(0xFFFF0044),
                Color(0xFFFF0000),
                Color(0xFFFF4400),
                Color(0xFFFF8800),
                Color(0xFFFFCC00),
                Color(0xFFFFFF00),
                Color(0xFFCCFF00),
                Color(0xFF88FF00),
                Color(0xFF44FF00),
                Color(0xFF00FF00),
                Color(0xFF00FF88),
                Color(0xFF00FFFF),
                Color(0xFF0088FF),
                Color(0xFF0000FF),
            ),
            peakCapColor = Color(0xFFFFFFFF),
        ),
        PulseMatrixThemeId.AMBER to PulseMatrixThemeStyle(
            id = PulseMatrixThemeId.AMBER,
            barColors = listOf(
                Color(0xFFFFFFE0),
                Color(0xFFFFFFC0),
                Color(0xFFFFFF00),
                Color(0xFFFFDD00),
                Color(0xFFFFBB00),
                Color(0xFFFF9900),
                Color(0xFFFF7700),
                Color(0xFFFF5500),
                Color(0xFFFF4400),
                Color(0xFFFF3300),
                Color(0xFFFF2200),
                Color(0xFFFF1100),
                Color(0xFFFF0000),
                Color(0xFFDD2200),
                Color(0xFFBB4400),
                Color(0xFF884400),
            ),
            peakCapColor = Color(0xFFFFFFFF),
        ),
        PulseMatrixThemeId.CYAN to PulseMatrixThemeStyle(
            id = PulseMatrixThemeId.CYAN,
            barColors = listOf(
                Color(0xFFE0FFFF),
                Color(0xFFAAFFFF),
                Color(0xFF66FFFF),
                Color(0xFF00FFFF),
                Color(0xFF00E6FF),
                Color(0xFF00CCFF),
                Color(0xFF00B3FF),
                Color(0xFF0099FF),
                Color(0xFF0080FF),
                Color(0xFF0066FF),
                Color(0xFF0055FF),
                Color(0xFF0044FF),
                Color(0xFF0033FF),
                Color(0xFF0022FF),
                Color(0xFF0011FF),
                Color(0xFF0000FF),
            ),
            peakCapColor = Color(0xFFFFFFFF),
        ),
        PulseMatrixThemeId.EMERALD to PulseMatrixThemeStyle(
            id = PulseMatrixThemeId.EMERALD,
            barColors = listOf(
                Color(0xFFCCFFCC),
                Color(0xFFAAFFAA),
                Color(0xFF88FF88),
                Color(0xFF66FF66),
                Color(0xFF44FF44),
                Color(0xFF22FF22),
                Color(0xFF00FF00),
                Color(0xFF00DD00),
                Color(0xFF00BB00),
                Color(0xFF009900),
                Color(0xFF007700),
                Color(0xFF006600),
                Color(0xFF005500),
                Color(0xFF004400),
                Color(0xFF003300),
                Color(0xFF002200),
            ),
            peakCapColor = Color(0xFFAAFFAA),
        ),
        PulseMatrixThemeId.CRIMSON to PulseMatrixThemeStyle(
            id = PulseMatrixThemeId.CRIMSON,
            barColors = listOf(
                Color(0xFFFFCCCC),
                Color(0xFFFFAAAA),
                Color(0xFFFF8888),
                Color(0xFFFF6666),
                Color(0xFFFF4444),
                Color(0xFFFF2222),
                Color(0xFFFF0000),
                Color(0xFFDD0000),
                Color(0xFFBB0000),
                Color(0xFFAA0000),
                Color(0xFF990000),
                Color(0xFF880000),
                Color(0xFF770000),
                Color(0xFF660000),
                Color(0xFF550000),
                Color(0xFF440000),
            ),
            peakCapColor = Color(0xFFFFAAAA),
        ),
        PulseMatrixThemeId.VIOLET to PulseMatrixThemeStyle(
            id = PulseMatrixThemeId.VIOLET,
            barColors = listOf(
                Color(0xFFEECCFF),
                Color(0xFFDDBBFF),
                Color(0xFFCCAAFF),
                Color(0xFFBB99FF),
                Color(0xFFAA88FF),
                Color(0xFF9977FF),
                Color(0xFF8866FF),
                Color(0xFF7755FF),
                Color(0xFF6644FF),
                Color(0xFF5533FF),
                Color(0xFF4422FF),
                Color(0xFF3311FF),
                Color(0xFF3300DD),
                Color(0xFF3300BB),
                Color(0xFF330099),
                Color(0xFF330077),
            ),
            peakCapColor = Color(0xFFCCAAFF),
        ),
        PulseMatrixThemeId.ICE to PulseMatrixThemeStyle(
            id = PulseMatrixThemeId.ICE,
            barColors = listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF0F8FF),
                Color(0xFFE0EEFF),
                Color(0xFFD0E0FF),
                Color(0xFFC0D0FF),
                Color(0xFFB0C0FF),
                Color(0xFFA0B0FF),
                Color(0xFF90A0FF),
                Color(0xFF8090FF),
                Color(0xFF7080FF),
                Color(0xFF6070FF),
                Color(0xFF5060FF),
                Color(0xFF4050FF),
                Color(0xFF3040FF),
                Color(0xFF2030FF),
                Color(0xFF1020FF),
            ),
            peakCapColor = Color(0xFFFFFFFF),
        ),
        PulseMatrixThemeId.AURORA to PulseMatrixThemeStyle(
            id = PulseMatrixThemeId.AURORA,
            barColors = listOf(
                Color(0xFF00FF88),
                Color(0xFF22FF66),
                Color(0xFF44FF44),
                Color(0xFF66FF22),
                Color(0xFF88FF00),
                Color(0xFFAAEE00),
                Color(0xFFCCDD00),
                Color(0xFFEECC00),
                Color(0xFFFFBB00),
                Color(0xFFFF9900),
                Color(0xFFFF7700),
                Color(0xFFFF5500),
                Color(0xFFFF3344),
                Color(0xFFFF1166),
                Color(0xFFFF0088),
                Color(0xFFFF00AA),
            ),
            peakCapColor = Color(0xFFFFFFFF),
        ),
    )

    fun getStyle(themeId: PulseMatrixThemeId): PulseMatrixThemeStyle {
        return themes[themeId] ?: themes[PulseMatrixThemeId.AURORA]!!
    }

    fun parseStorageKey(value: String?): PulseMatrixThemeId {
        return PulseMatrixThemeId.entries.firstOrNull { it.storageKey == value }
            ?: PulseMatrixThemeId.AURORA
    }

    fun fromLegacyName(name: String): PulseMatrixThemeId {
        return PulseMatrixThemeId.entries.firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: PulseMatrixThemeId.AURORA
    }
}

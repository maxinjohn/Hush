package app.hush.music.ui.player.visualizer

object PulseMatrixSettings {
    var enabled: Boolean = false
        private set

    var theme: PulseMatrixTheme = PulseMatrixTheme.AURORA
        private set

    var miniPlayerEnabled: Boolean = true
        private set

    var intensityLevel: IntensityLevel = IntensityLevel.NORMAL
        private set

    var peakHoldEnabled: Boolean = true
        private set

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun setTheme(theme: PulseMatrixTheme) {
        this.theme = theme
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        this.miniPlayerEnabled = enabled
    }

    fun setIntensityLevel(level: IntensityLevel) {
        this.intensityLevel = level
        intensityGainMultiplier = when (level) {
            IntensityLevel.LOW -> 0.9f
            IntensityLevel.NORMAL -> 1.35f
            IntensityLevel.HIGH -> 1.8f
        }
    }

    fun setPeakHoldEnabled(enabled: Boolean) {
        this.peakHoldEnabled = enabled
    }

    var intensityGainMultiplier: Float = 1.35f
        private set

    enum class IntensityLevel {
        LOW, NORMAL, HIGH
    }
}
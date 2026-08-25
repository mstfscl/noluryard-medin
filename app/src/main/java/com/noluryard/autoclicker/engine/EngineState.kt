package com.noluryard.autoclicker.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class StopReason {
    MANUAL,
    CLICK_LIMIT,
    TIME_LIMIT,
    SCREEN_OFF,
    VOLUME_KEY,
    SERVICE_LOST,
    ERROR,
}

/** Termal durumun sadelestirilmis hali; UI ve kontrolcu ayni dili konussun diye. */
enum class ThermalLevel { UNKNOWN, NORMAL, WARM, HOT, CRITICAL }

data class RunStats(
    val running: Boolean = false,
    /** Basla'ya basildi ama baslangic gecikmesi doluyor. */
    val countingDown: Boolean = false,
    val countdownMsLeft: Long = 0L,
    val clicks: Int = 0,
    val missed: Int = 0,
    val elapsedMs: Long = 0L,
    val clickLimit: Int = 0,
    val timeLimitSec: Int = 0,
    val achievedCps: Float = 0f,
    val targetCps: Float = 0f,
    val ceilingCps: Float = 0f,
    val thermal: ThermalLevel = ThermalLevel.UNKNOWN,
    val cpuLoad: Float = -1f,
    val jankRatio: Float = 0f,
) {
    /** 0f..1f, sinirsizsa null. */
    val clickProgress: Float?
        get() = if (clickLimit > 0) (clicks.toFloat() / clickLimit).coerceIn(0f, 1f) else null

    val timeProgress: Float?
        get() = if (timeLimitSec > 0) (elapsedMs.toFloat() / (timeLimitSec * 1000f)).coerceIn(0f, 1f) else null
}

data class CalibrationState(
    val running: Boolean = false,
    val progress: Float = 0f,
    /** O ana kadar dogrulanmis en yuksek CPS. */
    val bestCps: Int = 0,
    val stepLabel: String = "",
    val finishedAtLeastOnce: Boolean = false,
)

/**
 * Motorun tum canli durumu. AccessibilityService, OverlayService ve Compose UI
 * ayni akislari dinler; boylece ana uygulama kapali olsa da balon dogru veriyi gosterir.
 */
object EngineState {

    private val _stats = MutableStateFlow(RunStats())
    val stats = _stats.asStateFlow()

    private val _calibration = MutableStateFlow(CalibrationState())
    val calibration = _calibration.asStateFlow()

    /** Motor durdugunda tetiklenir: titresim + bildirim burayi dinler. */
    private val _stopEvents = MutableSharedFlow<StopReason>(extraBufferCapacity = 8)
    val stopEvents = _stopEvents.asSharedFlow()

    /** Kullaniciya gosterilecek tek seferlik hata/uyari mesajlari. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    internal fun updateStats(block: (RunStats) -> RunStats) = _stats.update(block)

    internal fun resetStats(clickLimit: Int, timeLimitSec: Int, ceiling: Float, target: Float) {
        _stats.value = RunStats(
            running = true,
            clickLimit = clickLimit,
            timeLimitSec = timeLimitSec,
            ceilingCps = ceiling,
            targetCps = target,
        )
    }

    internal fun updateCalibration(block: (CalibrationState) -> CalibrationState) =
        _calibration.update(block)

    internal fun emitStop(reason: StopReason) {
        _stats.update { it.copy(running = false, countingDown = false) }
        _stopEvents.tryEmit(reason)
    }

    fun emitMessage(text: String) {
        _messages.tryEmit(text)
    }
}

package com.noluryard.autoclicker.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.Display
import java.io.File

/**
 * Cihazin o anki yukunu uc bagimsiz kaynaktan olcer. Kontrolcu bunlari
 * "hedefi dusurmeli miyim" karari icin kullanir.
 *
 *  1) Termal durum   – PowerManager.getCurrentThermalStatus() (API 29+)
 *  2) CPU yuku       – /proc/stat idle/total orani
 *  3) Kare atlama    – Choreographer ile olculen frame araligi
 *
 * Ucu de "en iyi caba" ile calisir: okunamayan kaynak sessizce devre disi kalir,
 * cunku bir olcum kaynagini kaybetmek tiklamayi durdurmak icin sebep degil.
 */
class DeviceLoadMonitor(context: Context) : Choreographer.FrameCallback {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    // ---- termal ----
    @Volatile
    private var thermalRaw: Int = -1

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalRaw = status
    }

    // ---- kare atlama ----
    private var lastFrameNs = 0L
    private var framesInWindow = 0
    private var jankyFramesInWindow = 0

    @Volatile
    private var jankRatio = 0f

    /**
     * Ekranin gercek yenileme periyodu. 60 Hz varsayimi 120 Hz panellerde
     * her kareyi "atlanmis" gosterirdi, o yuzden gercek deger okunuyor.
     */
    private val frameIntervalNs: Long = run {
        val dm = appContext.getSystemService(DisplayManager::class.java)
        val hz = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        val safeHz = if (hz < 20f) 60f else hz
        (1_000_000_000.0 / safeHz).toLong()
    }

    // ---- CPU ----
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L

    @Volatile
    private var cpuLoad = -1f

    /** Bu cihazda /proc/stat okunamiyorsa bir daha denemeyelim. */
    private var procStatReadable = true

    val cpuCount: Int = Runtime.getRuntime().availableProcessors()

    fun start() {
        if (running) return
        running = true
        thermalRaw = readThermalNow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.addThermalStatusListener(thermalListener) }
        }
        // Choreographer, Looper'i olan bir thread'e bagli olmak zorunda.
        mainHandler.post {
            lastFrameNs = 0L
            framesInWindow = 0
            jankyFramesInWindow = 0
            Choreographer.getInstance().postFrameCallback(this)
        }
        sampleCpu() // ilk ornek: sonraki cagrida fark hesaplanabilsin
    }

    fun stop() {
        if (!running) return
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.removeThermalStatusListener(thermalListener) }
        }
        mainHandler.post { Choreographer.getInstance().removeFrameCallback(this) }
        jankRatio = 0f
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNs != 0L) {
            val delta = frameTimeNanos - lastFrameNs
            framesInWindow++
            // 1.5 kare esigi: tek karelik dalgalanma normal, 1.5 kareyi asan
            // aralik gercek bir atlamadir (Android'in kendi jank tanimiyla ayni mantik).
            if (delta > frameIntervalNs * 3 / 2) jankyFramesInWindow++
            if (framesInWindow >= JANK_WINDOW_FRAMES) {
                jankRatio = jankyFramesInWindow.toFloat() / framesInWindow
                framesInWindow = 0
                jankyFramesInWindow = 0
            }
        }
        lastFrameNs = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun readThermalNow(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.currentThermalStatus ?: -1 }.getOrDefault(-1)
        } else {
            -1
        }

    fun thermalLevel(): ThermalLevel {
        val raw = if (thermalRaw >= 0) thermalRaw else readThermalNow()
        if (raw < 0) return ThermalLevel.UNKNOWN
        return when {
            raw <= PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NORMAL
            raw == PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
            raw == PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.HOT
            else -> ThermalLevel.CRITICAL
        }
    }

    /**
     * /proc/stat ilk satiri: cpu user nice system idle iowait irq softirq steal ...
     * Iki ornek arasindaki (total-idle)/total orani = yuk.
     *
     * Bazi ROM'larda SELinux bu dosyayi ucuncu parti uygulamalara kapatir;
     * o durumda -1 doner ve kontrolcu bu sinyali hic kullanmaz.
     */
    fun sampleCpu(): Float {
        if (!procStatReadable) return -1f
        val line = runCatching {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        }.getOrNull()
        if (line == null || !line.startsWith("cpu ")) {
            procStatReadable = false
            cpuLoad = -1f
            return -1f
        }
        val values = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 5) return cpuLoad
        val idle = values[3] + values[4] // idle + iowait
        val total = values.sum()
        val hadBaseline = lastCpuTotal > 0L
        val dTotal = total - lastCpuTotal
        val dIdle = idle - lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idle
        // Ilk ornekte fark yok; sayaclar geri sarmissa (dTotal <= 0) eski degeri koru.
        if (!hadBaseline || dTotal <= 0L) return cpuLoad
        cpuLoad = ((dTotal - dIdle).toFloat() / dTotal).coerceIn(0f, 1f)
        return cpuLoad
    }

    fun cpuLoadOrUnknown(): Float = cpuLoad

    fun jankRatio(): Float = jankRatio

    /** Kare atlama orani bu esigin ustundeyse sistem zorlaniyor demektir. */
    fun isJanky(): Boolean = jankRatio > JANK_LIMIT

    private companion object {
        /** ~1 saniyelik pencere (60 Hz'de 60 kare). */
        const val JANK_WINDOW_FRAMES = 60

        /**
         * %20 kare atlama esigi: oyunlarda %5-10 dalgalanma normaldir,
         * %20 uzeri gozle gorulur takilma demektir ve hiz artirmayi durdurmak icin
         * yeterli bir isarettir.
         */
        const val JANK_LIMIT = 0.20f
    }
}

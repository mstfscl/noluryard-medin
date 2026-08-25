package com.noluryard.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.noluryard.autoclicker.data.ClickPattern
import com.noluryard.autoclicker.data.ClickPoint
import com.noluryard.autoclicker.data.ClickerSettings
import com.noluryard.autoclicker.data.SettingsBounds
import com.noluryard.autoclicker.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

/**
 * Tiklama motoru.
 *
 * Neden root/adb yok: AccessibilityService.dispatchGesture() sistemin resmi jest
 * enjeksiyon yolu. `adb shell input tap` her cagrida yeni bir JVM baslattigi icin
 * ~200-300 ms gecikme uretir (yani ~3-5 CPS tavani). dispatchGesture ise dogrudan
 * InputManager'a gider.
 *
 * Kritik kisit: sistem ayni anda servis basina TEK jest calistirir. Yeni bir jest
 * gonderilirse devam eden jest IPTAL edilir (onCancelled) ve o tiklama kaybolur.
 * Bu yuzden ayni anda en fazla [MAX_IN_FLIGHT] = 1 jest tutulur; ulasilabilen CPS
 * dogrudan "jest gidis-donus suresi"ne baglidir ve cihazdan cihaza degisir.
 * Adaptif kontrolcunun var olma sebebi tam olarak budur.
 */
class ClickEngine(
    private val service: AccessibilityService,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    private val loadMonitor = DeviceLoadMonitor(service.applicationContext)

    private var runJob: Job? = null
    private var controllerJob: Job? = null
    private var tickerJob: Job? = null
    private var calibrationJob: Job? = null

    private val inFlight = AtomicInteger(0)
    private val completed = AtomicInteger(0)
    private val cancelled = AtomicInteger(0)
    private val rejected = AtomicInteger(0)

    /** Son 1 saniyedeki tamamlanma zaman damgalari; achievedCps buradan gelir. */
    private val completionTimes = ArrayDeque<Long>()
    private val completionLock = Any()

    @Volatile
    private var currentTargetCps: Double = 10.0

    @Volatile
    private var ceilingCps: Double = SettingsBounds.MAX_CPS.toDouble()

    val isRunning: Boolean get() = runJob?.isActive == true

    private val gestureCallback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            inFlight.decrementAndGet()
            completed.incrementAndGet()
            val now = SystemClock.uptimeMillis()
            synchronized(completionLock) {
                completionTimes.addLast(now)
                pruneLocked(now)
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            inFlight.decrementAndGet()
            cancelled.incrementAndGet()
        }
    }

    // ------------------------------------------------------------------
    // Baslat / durdur
    // ------------------------------------------------------------------

    fun start() {
        if (isRunning || EngineState.calibration.value.running) return
        finished.set(false)
        runJob = scope.launch {
            try {
                runLoop(settingsRepository.current())
            } catch (t: Throwable) {
                // runLoop kendi finally'sinde zaten finish() cagiriyor; buraya
                // sadece dongu HENUZ baslamadan (ornegin ayarlar okunurken)
                // olusan hatalar duser.
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Tiklama baslatilamadi", t)
                    EngineState.emitMessage("Tiklama baslatilamadi: ${t.message ?: t::class.java.simpleName}")
                }
                finish(if (t is kotlinx.coroutines.CancellationException) StopReason.MANUAL else StopReason.ERROR)
            }
        }
    }

    fun stop(reason: StopReason = StopReason.MANUAL) {
        val job = runJob ?: return
        if (!job.isActive) return
        pendingStopReason = reason
        job.cancel()
    }

    fun shutdown() {
        stop(StopReason.SERVICE_LOST)
        calibrationJob?.cancel()
        loadMonitor.stop()
    }

    @Volatile
    private var pendingStopReason: StopReason? = null

    /** finish() hem normal cikista hem hata yolunda cagrilabilir; iki kez calismasin. */
    private val finished = AtomicBoolean(false)

    // ------------------------------------------------------------------
    // Ana dongu
    // ------------------------------------------------------------------

    private suspend fun runLoop(settings: ClickerSettings) {
        finished.set(false)
        resetCounters()
        pendingStopReason = null

        val points = resolvePoints(settings)
        ceilingCps = if (settings.autoSpeed) {
            settings.effectiveCeiling()
        } else {
            settings.targetCps.toDouble()
        }
        currentTargetCps = ceilingCps

        EngineState.resetStats(
            clickLimit = settings.clickLimit,
            timeLimitSec = settings.timeLimitSec,
            ceiling = ceilingCps.toFloat(),
            target = currentTargetCps.toFloat(),
        )

        // --- baslangic gecikmesi: kullanici oyuna gecebilsin ---
        if (settings.startDelaySec > 0) {
            val endAt = SystemClock.elapsedRealtime() + settings.startDelaySec * 1000L
            while (currentCoroutineContext().isActive) {
                val left = endAt - SystemClock.elapsedRealtime()
                if (left <= 0) break
                EngineState.updateStats { it.copy(countingDown = true, countdownMsLeft = left) }
                delay(minOf(left, 100L))
            }
            EngineState.updateStats { it.copy(countingDown = false, countdownMsLeft = 0L) }
        }

        loadMonitor.start()
        val startedAt = SystemClock.elapsedRealtime()

        tickerJob = scope.launch { statsTicker(settings, startedAt) }
        controllerJob = scope.launch { controlLoop(settings, startedAt) }

        var index = 0
        var reason = StopReason.MANUAL
        // Kayma birikmesin diye mutlak zaman cizelgesi kullaniliyor (nextAt),
        // her adimda "simdi + interval" demek yavas yavas geri kalmaya yol acar.
        var nextAt = SystemClock.uptimeMillis()

        try {
            while (currentCoroutineContext().isActive) {
                val done = completed.get()

                // 1) Tiklama limiti: ucusta olan jestleri de sayiyoruz ki hedefi asmayalim.
                if (settings.clickLimit > 0 && done + inFlight.get() >= settings.clickLimit) {
                    reason = StopReason.CLICK_LIMIT
                    break
                }
                // 2) Sure limiti.
                if (settings.timeLimitSec > 0 &&
                    SystemClock.elapsedRealtime() - startedAt >= settings.timeLimitSec * 1000L
                ) {
                    reason = StopReason.TIME_LIMIT
                    break
                }

                val base = nextPoint(points, index, settings.pattern)
                index++
                dispatchClick(
                    x = base.x + randomOffset(settings.positionJitterPx),
                    y = base.y + randomOffset(settings.positionJitterPx),
                    durationMs = settings.touchDurationMs.toLong(),
                )

                val intervalMs = 1000.0 / max(currentTargetCps, 0.5) + randomOffset(settings.jitterMs)
                nextAt += intervalMs.toLong().coerceAtLeast(1L)
                val sleep = nextAt - SystemClock.uptimeMillis()
                if (sleep > 0) {
                    delay(sleep)
                } else {
                    // Cizelgenin gerisine dustuk: acigi kapatmaya calisip spiral yapmak
                    // yerine cizelgeyi simdiye sifirliyoruz. Kaybedilen tiklamalar zaten
                    // achievedCps'e yansiyacak ve kontrolcu hedefi dusurecek.
                    nextAt = SystemClock.uptimeMillis()
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "Tiklama dongusu hata verdi", t)
            EngineState.emitMessage("Tiklama durdu: ${t.message ?: t::class.java.simpleName}")
            reason = StopReason.ERROR
        } finally {
            // Sure/limit dolarak ciktiysak reason dolu; cancel() ile ciktiysak
            // stop() cagrisindaki sebep gecerli.
            finish(pendingStopReason ?: reason)
        }
    }

    private fun finish(reason: StopReason) {
        if (!finished.compareAndSet(false, true)) return
        controllerJob?.cancel()
        tickerJob?.cancel()
        loadMonitor.stop()
        EngineState.updateStats {
            it.copy(
                running = false,
                countingDown = false,
                clicks = completed.get(),
                missed = cancelled.get() + rejected.get(),
                achievedCps = 0f,
            )
        }
        EngineState.emitStop(reason)
        pendingStopReason = null
    }

    // ------------------------------------------------------------------
    // Istatistik ve kontrolcu
    // ------------------------------------------------------------------

    /** UI/balon icin hizli guncelleme. Kontrol karari burada verilmez. */
    private suspend fun statsTicker(settings: ClickerSettings, startedAt: Long) {
        while (currentCoroutineContext().isActive) {
            EngineState.updateStats {
                it.copy(
                    clicks = completed.get(),
                    missed = cancelled.get() + rejected.get(),
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    achievedCps = achievedCps().toFloat(),
                    targetCps = currentTargetCps.toFloat(),
                    ceilingCps = ceilingCps.toFloat(),
                )
            }
            delay(UI_TICK_MS)
        }
    }

    /**
     * Kapali cevrim hiz kontrolcusu.
     *
     * Esiklerin gerekcesi:
     *  - 0.85 (dusur): jest gidis-donusunde dogal olarak %5-10 dalgalanma var.
     *    0.90 esigi bu gurultude bile tetiklenip gereksiz yere yavaslatirdi.
     *    0.85'in altina dusmek "cihaz gercekten yetismiyor" demektir.
     *  - 0.97 (artir): neredeyse kusursuz takip. Bu kadar yuksek tutuluyor cunku
     *    hedefi erken artirmak sistemi doygunluga itip salinim (yukselt-dusur-yukselt)
     *    yaratir.
     *  - Asimetrik adim (%10 asagi / %5 yukari): TCP'nin AIMD mantigi. Hizli geri
     *    cekil, yavas tirman -> tavana yaklasirken ustune cikmadan oturur.
     *  - 500 ms: achievedCps 1 saniyelik pencereden hesaplaniyor. Olcum penceresinin
     *    yarisindan daha sik karar vermek henuz olusmamis veriye tepki vermek olur
     *    ve kontrolcuyu titretir (Nyquist mantigi).
     */
    private suspend fun controlLoop(settings: ClickerSettings, startedAt: Long) {
        while (currentCoroutineContext().isActive) {
            delay(CONTROL_INTERVAL_MS)

            val achieved = achievedCps()
            val thermal = loadMonitor.thermalLevel()
            val cpu = loadMonitor.sampleCpu()
            val jank = loadMonitor.jankRatio()

            EngineState.updateStats {
                it.copy(thermal = thermal, cpuLoad = cpu, jankRatio = jank)
            }

            if (!settings.autoSpeed) continue

            val target = currentTargetCps
            val ratio = if (target > 0.0) achieved / target else 1.0

            var next = when {
                // Termal acil durum: cihaz kizmissa oran ne olursa olsun geri cekil.
                thermal == ThermalLevel.CRITICAL -> target * THERMAL_CRITICAL_FACTOR
                thermal == ThermalLevel.HOT -> target * THERMAL_HOT_FACTOR
                ratio < DOWN_RATIO -> target * DOWN_FACTOR
                ratio > UP_RATIO && thermal <= ThermalLevel.NORMAL && !loadMonitor.isJanky() ->
                    target * UP_FACTOR
                else -> target
            }

            // CPU sinyali varsa ve tam doygunsa, hiz artisini engelle.
            if (cpu >= 0f && cpu > CPU_SATURATION && next > target) next = target

            currentTargetCps = next.coerceIn(
                SettingsBounds.CONTROLLER_FLOOR_CPS,
                ceilingCps.coerceAtLeast(SettingsBounds.CONTROLLER_FLOOR_CPS),
            )
        }
    }

    // ------------------------------------------------------------------
    // Kalibrasyon
    // ------------------------------------------------------------------

    /**
     * ~3 saniyelik artan hiz testi. Her basamakta [CALIBRATION_STEP_MS] boyunca
     * hedef CPS'te jest gonderilir; gerceklesen/hedef orani [CALIBRATION_PASS_RATIO]
     * altina dusen ilk basamak cihaz tavanidir ve bir onceki basamak kaydedilir.
     *
     * onCalibrationStep: UI/kalkan icin ilerleme (0f..1f).
     */
    fun calibrate(onProgress: (Float, Int) -> Unit = { _, _ -> }) {
        if (isRunning || EngineState.calibration.value.running) return
        calibrationJob = scope.launch {
            EngineState.updateCalibration {
                it.copy(running = true, progress = 0f, bestCps = 0, stepLabel = "")
            }
            var best = SettingsBounds.CONTROLLER_FLOOR_CPS.toInt()
            try {
                val settings = settingsRepository.current()
                val target = resolvePoints(settings).first()
                loadMonitor.start()
                for ((i, cps) in CALIBRATION_STEPS.withIndex()) {
                    EngineState.updateCalibration {
                        it.copy(stepLabel = "$cps CPS", progress = i.toFloat() / CALIBRATION_STEPS.size)
                    }
                    onProgress(i.toFloat() / CALIBRATION_STEPS.size, best)
                    val ratio = burst(cps, target)
                    if (ratio >= CALIBRATION_PASS_RATIO) {
                        best = cps
                        EngineState.updateCalibration { it.copy(bestCps = best) }
                    } else {
                        break
                    }
                    // Termal olarak zorlanmaya basladiysak daha yukari zorlamanin anlami yok.
                    if (loadMonitor.thermalLevel() >= ThermalLevel.HOT) break
                }
                settingsRepository.update { it.copy(maxSafeCps = best) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "Kalibrasyon hatasi", t)
                EngineState.emitMessage("Kalibrasyon tamamlanamadi: ${t.message}")
            } finally {
                loadMonitor.stop()
                EngineState.updateCalibration {
                    it.copy(
                        running = false,
                        progress = 1f,
                        bestCps = best,
                        stepLabel = "",
                        finishedAtLeastOnce = true,
                    )
                }
                onProgress(1f, best)
            }
        }
    }

    /** Tek bir kalibrasyon basamagi. Doner: gerceklesen / beklenen. */
    private suspend fun burst(cps: Int, point: ClickPoint): Double {
        resetCounters()
        val intervalMs = (1000.0 / cps).toLong().coerceAtLeast(1L)
        val endAt = SystemClock.uptimeMillis() + CALIBRATION_STEP_MS
        var nextAt = SystemClock.uptimeMillis()

        while (currentCoroutineContext().isActive && SystemClock.uptimeMillis() < endAt) {
            dispatchClick(point.x, point.y, CALIBRATION_TOUCH_MS)
            nextAt += intervalMs
            val sleep = nextAt - SystemClock.uptimeMillis()
            if (sleep > 0) delay(sleep) else nextAt = SystemClock.uptimeMillis()
        }
        // Ucusta kalan son jestin sonucunu bekle, aksi halde her basamakta
        // sistematik olarak 1 tiklama eksik sayardik.
        delay(CALIBRATION_DRAIN_MS)

        val expected = cps * CALIBRATION_STEP_MS / 1000.0
        return if (expected <= 0) 0.0 else completed.get() / expected
    }

    // ------------------------------------------------------------------
    // Jest gonderimi
    // ------------------------------------------------------------------

    private fun dispatchClick(x: Float, y: Float, durationMs: Long) {
        // Ayni anda birden fazla jest = oncekinin iptali. Onceki bitmediyse
        // bu turu atliyoruz; bu bir "kacirilan tiklama" olarak sayilir ve
        // kontrolcuye "yavasla" sinyali olarak geri doner.
        if (inFlight.get() >= MAX_IN_FLIGHT) {
            rejected.incrementAndGet()
            return
        }

        val bounds = screenBounds
        val safeX = x.coerceIn(1f, bounds.first - 2f)
        val safeY = y.coerceIn(1f, bounds.second - 2f)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(1L, MAX_STROKE_MS),
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        // Sayaci gondermeden ONCE artiriyoruz: geri cagri baska bir thread'de
        // gelirse sayac negatife dusmesin.
        inFlight.incrementAndGet()
        val accepted = try {
            service.dispatchGesture(gesture, gestureCallback, null)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture basarisiz", t)
            false
        }
        if (!accepted) {
            inFlight.decrementAndGet()
            rejected.incrementAndGet()
        }
    }

    // ------------------------------------------------------------------
    // Yardimcilar
    // ------------------------------------------------------------------

    private fun resetCounters() {
        inFlight.set(0)
        completed.set(0)
        cancelled.set(0)
        rejected.set(0)
        synchronized(completionLock) { completionTimes.clear() }
    }

    private fun pruneLocked(now: Long) {
        while (completionTimes.isNotEmpty() && now - completionTimes.peekFirst() > CPS_WINDOW_MS) {
            completionTimes.pollFirst()
        }
    }

    /** Son 1 saniyede gercekten tamamlanan tiklama sayisi. */
    private fun achievedCps(): Double {
        val now = SystemClock.uptimeMillis()
        return synchronized(completionLock) {
            pruneLocked(now)
            completionTimes.size.toDouble()
        }
    }

    private fun nextPoint(points: List<ClickPoint>, index: Int, pattern: ClickPattern): ClickPoint {
        if (points.size == 1) return points[0]
        return when (pattern) {
            ClickPattern.SEQUENTIAL -> points[index % points.size]
            ClickPattern.RANDOM -> points[Random.nextInt(points.size)]
        }
    }

    private fun randomOffset(magnitude: Int): Float {
        if (magnitude <= 0) return 0f
        return Random.nextInt(-magnitude, magnitude + 1).toFloat()
    }

    /** Hedef secilmemisse ekran merkezine tikla. */
    private fun resolvePoints(settings: ClickerSettings): List<ClickPoint> {
        val chosen = settings.points.take(settings.pointCount)
        if (chosen.isNotEmpty()) return chosen
        val (w, h) = screenBounds
        return listOf(ClickPoint(w / 2f, h / 2f))
    }

    @Suppress("DEPRECATION")
    private val screenBounds: Pair<Float, Float>
        get() {
            val wm = service.getSystemService(WindowManager::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                b.width().toFloat() to b.height().toFloat()
            } else {
                val metrics = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
            }
        }

    companion object {
        private const val TAG = "ClickEngine"

        /** Sistem servis basina tek jest calistirir; 1'den buyugu iptal demektir. */
        const val MAX_IN_FLIGHT = 1

        /** achievedCps olcum penceresi. */
        const val CPS_WINDOW_MS = 1000L

        /** Kontrolcu karar araligi (olcum penceresinin yarisi). */
        const val CONTROL_INTERVAL_MS = 500L

        /** UI/balon guncelleme araligi. 100 ms goze akici gelir, CPU'ya yuk olmaz. */
        const val UI_TICK_MS = 100L

        const val DOWN_RATIO = 0.85
        const val UP_RATIO = 0.97
        const val DOWN_FACTOR = 0.90
        const val UP_FACTOR = 1.05
        const val THERMAL_HOT_FACTOR = 0.80
        const val THERMAL_CRITICAL_FACTOR = 0.60

        /** CPU %92 ustunde ise hiz artirmayi engelle. */
        const val CPU_SATURATION = 0.92f

        /** GestureDescription cok uzun stroke kabul etmez; guvenli ust sinir. */
        const val MAX_STROKE_MS = 200L

        val CALIBRATION_STEPS = intArrayOf(10, 20, 40, 60, 80)

        /** 5 basamak x 600 ms = 3.0 sn (istenen ~3 sn butcesi). */
        const val CALIBRATION_STEP_MS = 600L

        /** Son jestin geri cagrisi icin kucuk bir bosaltma payi. */
        const val CALIBRATION_DRAIN_MS = 60L

        /** Kalibrasyon touch suresi kisa tutulur: olculen sey dokunus degil, IPC tavani. */
        const val CALIBRATION_TOUCH_MS = 1L

        /**
         * %90: bir basamagin "gecti" sayilmasi icin gereken oran.
         * Kullanicinin istedigi esik. Altina dusen ilk basamak cihaz tavanidir.
         */
        const val CALIBRATION_PASS_RATIO = 0.90
    }
}

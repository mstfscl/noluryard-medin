package com.noluryard.autoclicker.engine

import android.content.Context
import com.noluryard.autoclicker.overlay.OverlayService

/**
 * UI (Compose ekrani, balon, bildirim aksiyonu) ile motor arasindaki tek giris noktasi.
 * Boylece "once overlay'i ayaga kaldir, sonra motoru baslat" sirasi her yerde ayni olur.
 */
object ClickerController {

    fun serviceReady(): Boolean = ClickerAccessibilityService.isConnected()

    /**
     * Tiklamayi baslatir. Overlay servisi de ayaga kaldirilir; boylece ana uygulama
     * kapansa bile foreground service sayesinde islem yasamaya devam eder.
     */
    fun start(context: Context): Boolean {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            EngineState.emitMessage("Erisilebilirlik servisi acik degil. Izin adimini tamamla.")
            return false
        }
        OverlayService.start(context)
        service.engine.start()
        return true
    }

    fun stop(reason: StopReason = StopReason.MANUAL) {
        ClickerAccessibilityService.instance?.engine?.stop(reason)
    }

    fun toggle(context: Context) {
        if (EngineState.stats.value.let { it.running || it.countingDown }) stop() else start(context)
    }

    /**
     * Kalibrasyon. Gercek jestler gonderildigi icin once tam ekran "kalkan"
     * penceresi aciliyor; test dokunuslari alttaki uygulamaya gitmesin.
     */
    fun calibrate(context: Context): Boolean {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            EngineState.emitMessage("Kalibrasyon icin once erisilebilirlik servisini ac.")
            return false
        }
        OverlayService.startCalibration(context)
        service.engine.calibrate()
        return true
    }
}

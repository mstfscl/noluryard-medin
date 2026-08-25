package com.noluryard.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.noluryard.autoclicker.AutoClickerApp
import com.noluryard.autoclicker.data.ClickerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Jestleri gonderen servis. Ekran icerigi okunmaz, hicbir olay kaydedilmez.
 *
 * Bu servis ana uygulamadan bagimsiz yasar: kullanici uygulamayi kapatsa da
 * sistem servisi ayakta tutar, dolayisiyla balon ve tiklama devam eder.
 */
class ClickerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var engine: ClickEngine
        private set

    /** onKeyEvent ana thread'de gelir; DataStore okumak icin cok gec olur, bu yuzden onbellek. */
    @Volatile
    private var cachedSettings: ClickerSettings = ClickerSettings()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val repo = AutoClickerApp.settingsRepository(this)
        engine = ClickEngine(this, repo, scope)
        instance = this

        repo.settings
            .onEach { cachedSettings = it }
            .launchIn(scope)

        Log.i(TAG, "Erisilebilirlik servisi baglandi")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Kasitli olarak bos: bu uygulama ekran icerigini dinlemez.
    }

    override fun onInterrupt() {
        engineOrNull()?.stop(StopReason.SERVICE_LOST)
    }

    /**
     * Acil durdurma: ses kisma tusu.
     *
     * Sadece tiklama CALISIRKEN tusu tuketiyoruz. Bosta iken tuketseydik
     * kullanici uygulamayi acik unuttugunda telefonun sesini hic ayarlayamazdi.
     * ACTION_UP de tuketilmeli, aksi halde sistem ses panelini yine acar.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!cachedSettings.volumeKeyStop) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val active = EngineState.stats.value.let { it.running || it.countingDown }
        if (!active) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            scope.launch { engineOrNull()?.stop(StopReason.VOLUME_KEY) }
        }
        return true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        engineOrNull()?.shutdown()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        engineOrNull()?.shutdown()
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun engineOrNull(): ClickEngine? = if (::engine.isInitialized) engine else null

    companion object {
        private const val TAG = "ClickerA11yService"

        @Volatile
        var instance: ClickerAccessibilityService? = null
            private set

        /** Servis sistem tarafindan baglanmis ve jest gondermeye hazir mi. */
        fun isConnected(): Boolean = instance != null

        /**
         * Kullanici servisi Ayarlar'dan acti mi.
         * instance != null olmasini beklemeden, izin ekraninda canli durum gostermek icin.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, ClickerAccessibilityService::class.java)
            val enabledRaw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledRaw)
            for (entry in splitter) {
                val component = ComponentName.unflattenFromString(entry) ?: continue
                if (component == expected) return true
            }
            return false
        }
    }
}

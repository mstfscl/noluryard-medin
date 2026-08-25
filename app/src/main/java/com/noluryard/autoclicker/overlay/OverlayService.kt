package com.noluryard.autoclicker.overlay

import android.animation.ValueAnimator
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.noluryard.autoclicker.AutoClickerApp
import com.noluryard.autoclicker.R
import com.noluryard.autoclicker.data.ClickPoint
import com.noluryard.autoclicker.data.ClickerSettings
import com.noluryard.autoclicker.engine.ClickerController
import com.noluryard.autoclicker.engine.EngineState
import com.noluryard.autoclicker.engine.RunStats
import com.noluryard.autoclicker.engine.StopReason
import com.noluryard.autoclicker.util.Formatting
import com.noluryard.autoclicker.util.Haptics
import com.noluryard.autoclicker.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Yuzen balonu tasiyan foreground service.
 *
 * Neden foreground service: kullanici ana uygulamadan cikip oyuna gectiginde
 * process arka plana duser. Foreground service olmadan sistem process'i
 * dakikalar icinde oldurur ve tiklama sessizce durur. Bildirim ayni zamanda
 * "Durdur" acil cikisini de tasir.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private val repo by lazy { AutoClickerApp.settingsRepository(this) }

    // ---- balon ----
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var collapsed: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var collapsedText: TextView? = null
    private var counters: TextView? = null
    private var speed: TextView? = null
    private var progressClicks: ProgressBar? = null
    private var progressTime: ProgressBar? = null
    private var btnStart: Button? = null

    private var isCollapsed = false

    // ---- ek pencereler ----
    private var pickerView: View? = null
    private var shieldView: View? = null

    private var settings: ClickerSettings = ClickerSettings()
    private var lastStats: RunStats = RunStats()

    /** Ekran kapaninca otomatik durdurma. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (!settings.stopOnScreenOff) return
            if (EngineState.stats.value.let { it.running || it.countingDown }) {
                ClickerController.stop(StopReason.SCREEN_OFF)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)

        startInForeground(RunStats())

        repo.settings
            .onEach { newSettings ->
                settings = newSettings
                applyBubblePositionIfNeeded(newSettings)
            }
            .launchIn(scope)

        EngineState.stats
            .onEach { stats ->
                lastStats = stats
                renderStats(stats)
                updateNotification(stats)
            }
            .launchIn(scope)

        EngineState.stopEvents
            .onEach { reason -> onEngineStopped(reason) }
            .launchIn(scope)

        EngineState.calibration
            .onEach { state ->
                updateShield(state.progress, state.stepLabel)
                if (!state.running) hideShield()
            }
            .launchIn(scope)

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CLICKING -> ClickerController.start(this)
            ACTION_STOP_CLICKING -> ClickerController.stop(StopReason.MANUAL)
            ACTION_SHOW_PICKER -> showPicker()
            ACTION_SHOW_SHIELD -> showShield()
            ACTION_SHUTDOWN -> {
                ClickerController.stop(StopReason.MANUAL)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureBubble()
        // START_STICKY: sistem bellek baskisiyla oldururse servisi geri getirsin.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        removeView(pickerView); pickerView = null
        removeView(shieldView); shieldView = null
        removeView(bubbleView); bubbleView = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground
    // ------------------------------------------------------------------

    private fun startInForeground(stats: RunStats) {
        val notification = Notifications.buildForeground(this, stats)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ID_FOREGROUND, notification)
        }
    }

    private fun updateNotification(stats: RunStats) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(Notifications.ID_FOREGROUND, Notifications.buildForeground(this, stats))
        }
    }

    private fun onEngineStopped(reason: StopReason) {
        // Manuel durdurmada titretmiyoruz: kullanici zaten ekrana bakiyor ve
        // her durdurmada titremek rahatsiz edici olur.
        if (reason != StopReason.MANUAL) {
            Haptics.shortBuzz(this)
            Notifications.showStopped(this, reason, lastStats)
        }
        renderStats(EngineState.stats.value)
    }

    // ------------------------------------------------------------------
    // Balon
    // ------------------------------------------------------------------

    private fun ensureBubble() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(this)) {
            EngineState.emitMessage("Diğer uygulamaların üzerinde gösterme izni verilmedi.")
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // FLAG_NOT_FOCUSABLE: klavye odagi almayiz, boylece alttaki oyun
            // dokunuslari ve tuslari almaya devam eder. Balon yine de
            // dokunulabilir (FLAG_NOT_TOUCHABLE KOYMUYORUZ).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.bubbleX >= 0) settings.bubbleX else 0
            y = if (settings.bubbleY >= 0) settings.bubbleY else screenHeight() / 3
        }

        collapsed = view.findViewById(R.id.collapsed)
        panel = view.findViewById(R.id.panel)
        collapsedText = view.findViewById(R.id.collapsedText)
        counters = view.findViewById(R.id.counters)
        speed = view.findViewById(R.id.speed)
        progressClicks = view.findViewById(R.id.progressClicks)
        progressTime = view.findViewById(R.id.progressTime)
        btnStart = view.findViewById(R.id.btnStart)

        view.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener { setCollapsed(true) }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            ClickerController.stop(StopReason.MANUAL)
            stopSelf()
        }
        btnStart?.setOnClickListener { ClickerController.toggle(this) }
        view.findViewById<Button>(R.id.btnTargets).setOnClickListener { showPicker() }

        attachDragBehaviour(view, params)

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.e(TAG, "Balon eklenemedi", it)
                EngineState.emitMessage("Balon gösterilemedi: ${it.message}")
                return
            }

        bubbleView = view
        bubbleParams = params
        setCollapsed(false)
        renderStats(EngineState.stats.value)
    }

    /**
     * Surukleme + kenara yapisma.
     *
     * touchSlop esigi: bu mesafenin altindaki hareket "tiklama" sayilir, ustu
     * "surukleme". Sistemin kendi degeri kullaniliyor ki parmagi titreyen
     * kullanicida buton basmak imkansizlasmasin.
     */
    private fun attachDragBehaviour(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = (startY + dy.roundToInt())
                            .coerceIn(0, (screenHeight() - v.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when {
                        dragging -> snapToEdge(view, params)
                        // Suruklenmediyse bu bir dokunustur. Butonlar zaten olayi
                        // kendileri tuketir ve buraya hic gelmez; buraya sadece
                        // kucuk daireye veya panelin bos alanina yapilan dokunus duser.
                        isCollapsed && event.actionMasked == MotionEvent.ACTION_UP ->
                            setCollapsed(false)
                    }
                    v.performClick()
                    true
                }

                else -> false
            }
        }
    }

    /** En yakin kenara yapistir. */
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val maxX = (screenWidth() - view.width).coerceAtLeast(0)
        val targetX = if (params.x + view.width / 2 < screenWidth() / 2) 0 else maxX
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = SNAP_DURATION_MS
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }.start()

        scope.launch {
            repo.update { it.copy(bubbleX = targetX, bubbleY = params.y) }
        }
    }

    private fun applyBubblePositionIfNeeded(newSettings: ClickerSettings) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        if (newSettings.bubbleX < 0 || newSettings.bubbleY < 0) return
        if (params.x == newSettings.bubbleX && params.y == newSettings.bubbleY) return
        params.x = newSettings.bubbleX
        params.y = newSettings.bubbleY
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun setCollapsed(value: Boolean) {
        isCollapsed = value
        collapsed?.visibility = if (value) View.VISIBLE else View.GONE
        panel?.visibility = if (value) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------
    // Canli gosterim
    // ------------------------------------------------------------------

    private fun renderStats(stats: RunStats) {
        val active = stats.running || stats.countingDown

        collapsed?.setBackgroundResource(
            if (active) R.drawable.bg_bubble_active else R.drawable.bg_bubble
        )
        collapsedText?.text = when {
            stats.countingDown -> Formatting.seconds(stats.countdownMsLeft)
            else -> stats.clicks.toString()
        }

        counters?.text = if (stats.countingDown) {
            "Başlıyor: ${Formatting.seconds(stats.countdownMsLeft)} sn"
        } else {
            Formatting.counterLine(stats)
        }

        speed?.text = Formatting.speedLine(stats)

        progressClicks?.let { bar ->
            val progress = stats.clickProgress
            bar.visibility = if (progress != null) View.VISIBLE else View.GONE
            if (progress != null) bar.progress = (progress * 1000).toInt()
        }
        progressTime?.let { bar ->
            val progress = stats.timeProgress
            bar.visibility = if (progress != null) View.VISIBLE else View.GONE
            if (progress != null) bar.progress = (progress * 1000).toInt()
        }

        btnStart?.apply {
            text = getString(if (active) R.string.overlay_stop else R.string.overlay_start)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (active) R.drawable.ic_stop else R.drawable.ic_play, 0, 0, 0,
            )
        }
    }

    // ------------------------------------------------------------------
    // Hedef secici
    // ------------------------------------------------------------------

    private fun showPicker() {
        if (pickerView != null) return
        if (!Settings.canDrawOverlays(this)) return
        ClickerController.stop(StopReason.MANUAL)

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_picker, null)
        val canvas = view.findViewById<TargetCanvasView>(R.id.canvas)
        val hint = view.findViewById<TextView>(R.id.pickerHint)

        canvas.maxPoints = settings.pointCount
        canvas.setPoints(settings.points)
        hint.text = getString(R.string.picker_hint, settings.pointCount)

        view.findViewById<Button>(R.id.btnPickerClear).setOnClickListener { canvas.clearPoints() }
        view.findViewById<Button>(R.id.btnPickerCancel).setOnClickListener { hidePicker() }
        view.findViewById<Button>(R.id.btnPickerDone).setOnClickListener {
            val chosen: List<ClickPoint> = canvas.currentPoints()
            scope.launch { repo.update { it.copy(points = chosen) } }
            hidePicker()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // LAYOUT_IN_SCREEN + LAYOUT_NO_LIMITS: pencere sistem cubuklarinin da
            // altini kaplasin ki secilen nokta ile gercek ekran koordinati ortussun.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

        runCatching { windowManager.addView(view, params) }
            .onSuccess { pickerView = view }
            .onFailure { Log.e(TAG, "Hedef secici acilamadi", it) }
    }

    private fun hidePicker() {
        removeView(pickerView)
        pickerView = null
    }

    // ------------------------------------------------------------------
    // Kalibrasyon kalkani
    // ------------------------------------------------------------------

    private fun showShield() {
        if (shieldView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_shield, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager.addView(view, params) }
            .onSuccess { shieldView = view }
            .onFailure { Log.e(TAG, "Kalkan acilamadi", it) }
    }

    private fun updateShield(progress: Float, stepLabel: String) {
        val view = shieldView ?: return
        view.findViewById<ProgressBar>(R.id.shieldProgress).progress = (progress * 100).toInt()
        if (stepLabel.isNotEmpty()) {
            view.findViewById<TextView>(R.id.shieldBody).text =
                getString(R.string.calibrating_body) + "  ($stepLabel)"
        }
    }

    private fun hideShield() {
        removeView(shieldView)
        shieldView = null
    }

    // ------------------------------------------------------------------
    // Yardimcilar
    // ------------------------------------------------------------------

    private fun removeView(view: View?) {
        if (view == null) return
        runCatching { windowManager.removeView(view) }
    }

    private fun overlayType(): Int =
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @Suppress("DEPRECATION")
    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            windowManager.defaultDisplay.let { d ->
                val m = android.util.DisplayMetrics(); d.getRealMetrics(m); m.widthPixels
            }
        }

    @Suppress("DEPRECATION")
    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            windowManager.defaultDisplay.let { d ->
                val m = android.util.DisplayMetrics(); d.getRealMetrics(m); m.heightPixels
            }
        }

    companion object {
        private const val TAG = "OverlayService"

        const val ACTION_START_CLICKING = "com.noluryard.autoclicker.START"
        const val ACTION_STOP_CLICKING = "com.noluryard.autoclicker.STOP"
        const val ACTION_SHOW_PICKER = "com.noluryard.autoclicker.PICKER"
        const val ACTION_SHOW_SHIELD = "com.noluryard.autoclicker.SHIELD"
        const val ACTION_SHUTDOWN = "com.noluryard.autoclicker.SHUTDOWN"

        const val SNAP_DURATION_MS = 180L

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startPicker(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW_PICKER)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startCalibration(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW_SHIELD)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_SHUTDOWN)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

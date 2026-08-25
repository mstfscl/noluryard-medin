package com.noluryard.autoclicker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "clicker_settings")

/**
 * DataStore Preferences uzerine ince bir sarmalayici.
 * Motor ve UI ayni ornegi kullanir (bkz. [com.noluryard.autoclicker.AutoClickerApp]).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val TARGET_CPS = intPreferencesKey("target_cps")
        val AUTO_SPEED = booleanPreferencesKey("auto_speed")
        val MAX_SAFE_CPS = intPreferencesKey("max_safe_cps")
        val JITTER_MS = intPreferencesKey("jitter_ms")
        val POS_JITTER_PX = intPreferencesKey("pos_jitter_px")
        val TOUCH_MS = intPreferencesKey("touch_ms")
        val START_DELAY = intPreferencesKey("start_delay")
        val POINT_COUNT = intPreferencesKey("point_count")
        val PATTERN = stringPreferencesKey("pattern")
        val POINTS = stringPreferencesKey("points")
        val CLICK_LIMIT = intPreferencesKey("click_limit")
        val TIME_LIMIT = intPreferencesKey("time_limit")
        val STOP_SCREEN_OFF = booleanPreferencesKey("stop_screen_off")
        val VOLUME_STOP = booleanPreferencesKey("volume_stop")
        val BUBBLE_X = intPreferencesKey("bubble_x")
        val BUBBLE_Y = intPreferencesKey("bubble_y")
    }

    val settings: Flow<ClickerSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): ClickerSettings = settings.first()

    suspend fun update(transform: (ClickerSettings) -> ClickerSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings()).sanitized()
            prefs[Keys.TARGET_CPS] = next.targetCps
            prefs[Keys.AUTO_SPEED] = next.autoSpeed
            prefs[Keys.MAX_SAFE_CPS] = next.maxSafeCps
            prefs[Keys.JITTER_MS] = next.jitterMs
            prefs[Keys.POS_JITTER_PX] = next.positionJitterPx
            prefs[Keys.TOUCH_MS] = next.touchDurationMs
            prefs[Keys.START_DELAY] = next.startDelaySec
            prefs[Keys.POINT_COUNT] = next.pointCount
            prefs[Keys.PATTERN] = next.pattern.name
            prefs[Keys.POINTS] = encodePoints(next.points)
            prefs[Keys.CLICK_LIMIT] = next.clickLimit
            prefs[Keys.TIME_LIMIT] = next.timeLimitSec
            prefs[Keys.STOP_SCREEN_OFF] = next.stopOnScreenOff
            prefs[Keys.VOLUME_STOP] = next.volumeKeyStop
            prefs[Keys.BUBBLE_X] = next.bubbleX
            prefs[Keys.BUBBLE_Y] = next.bubbleY
        }
    }

    private fun Preferences.toSettings(): ClickerSettings {
        val defaults = ClickerSettings()
        return ClickerSettings(
            targetCps = this[Keys.TARGET_CPS] ?: defaults.targetCps,
            autoSpeed = this[Keys.AUTO_SPEED] ?: defaults.autoSpeed,
            maxSafeCps = this[Keys.MAX_SAFE_CPS] ?: defaults.maxSafeCps,
            jitterMs = this[Keys.JITTER_MS] ?: defaults.jitterMs,
            positionJitterPx = this[Keys.POS_JITTER_PX] ?: defaults.positionJitterPx,
            touchDurationMs = this[Keys.TOUCH_MS] ?: defaults.touchDurationMs,
            startDelaySec = this[Keys.START_DELAY] ?: defaults.startDelaySec,
            pointCount = this[Keys.POINT_COUNT] ?: defaults.pointCount,
            pattern = runCatching { ClickPattern.valueOf(this[Keys.PATTERN] ?: "") }
                .getOrDefault(defaults.pattern),
            points = decodePoints(this[Keys.POINTS]),
            clickLimit = this[Keys.CLICK_LIMIT] ?: defaults.clickLimit,
            timeLimitSec = this[Keys.TIME_LIMIT] ?: defaults.timeLimitSec,
            stopOnScreenOff = this[Keys.STOP_SCREEN_OFF] ?: defaults.stopOnScreenOff,
            volumeKeyStop = this[Keys.VOLUME_STOP] ?: defaults.volumeKeyStop,
            bubbleX = this[Keys.BUBBLE_X] ?: defaults.bubbleX,
            bubbleY = this[Keys.BUBBLE_Y] ?: defaults.bubbleY,
        ).sanitized()
    }

    /** Bozuk/eski kayitlar motoru kilitlemesin diye her okuma ve yazmada sinirlar uygulanir. */
    private fun ClickerSettings.sanitized(): ClickerSettings = copy(
        targetCps = targetCps.coerceIn(SettingsBounds.MIN_CPS, SettingsBounds.MAX_CPS),
        maxSafeCps = maxSafeCps.coerceIn(0, SettingsBounds.MAX_CPS),
        jitterMs = jitterMs.coerceIn(0, SettingsBounds.MAX_JITTER_MS),
        positionJitterPx = positionJitterPx.coerceIn(0, SettingsBounds.MAX_POSITION_JITTER_PX),
        touchDurationMs = touchDurationMs.coerceIn(SettingsBounds.MIN_TOUCH_MS, SettingsBounds.MAX_TOUCH_MS),
        startDelaySec = startDelaySec.coerceIn(0, SettingsBounds.MAX_START_DELAY_SEC),
        pointCount = pointCount.coerceIn(1, SettingsBounds.MAX_POINTS),
        clickLimit = clickLimit.coerceIn(0, SettingsBounds.MAX_CLICK_LIMIT),
        timeLimitSec = timeLimitSec.coerceIn(0, SettingsBounds.MAX_TIME_LIMIT_SEC),
        points = points.take(SettingsBounds.MAX_POINTS),
    )

    private fun encodePoints(points: List<ClickPoint>): String =
        points.joinToString(";") { "${it.x},${it.y}" }

    private fun decodePoints(raw: String?): List<ClickPoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { chunk ->
            val parts = chunk.split(',')
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
            ClickPoint(x, y)
        }
    }
}

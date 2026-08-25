package com.noluryard.autoclicker.util

import android.content.Context
import com.noluryard.autoclicker.R
import com.noluryard.autoclicker.engine.RunStats
import com.noluryard.autoclicker.engine.StopReason
import java.util.Locale

/** Balon, bildirim ve ana ekranda ayni metinler kullanilsin diye tek yer. */
object Formatting {

    private const val INFINITY = "∞"

    fun seconds(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

    /** "247 / 500  •  3.2 sn / 10 sn" */
    fun counterLine(stats: RunStats): String {
        val clicks = if (stats.clickLimit > 0) {
            "${stats.clicks} / ${stats.clickLimit}"
        } else {
            "${stats.clicks} / $INFINITY"
        }
        val time = if (stats.timeLimitSec > 0) {
            "${seconds(stats.elapsedMs)} sn / ${stats.timeLimitSec} sn"
        } else {
            "${seconds(stats.elapsedMs)} sn / $INFINITY"
        }
        return "$clicks  •  $time"
    }

    fun speedLine(stats: RunStats): String {
        val achieved = String.format(Locale.US, "%.1f", stats.achievedCps)
        val target = String.format(Locale.US, "%.0f", stats.targetCps)
        val thermal = when (stats.thermal) {
            com.noluryard.autoclicker.engine.ThermalLevel.NORMAL -> "normal"
            com.noluryard.autoclicker.engine.ThermalLevel.WARM -> "ılık"
            com.noluryard.autoclicker.engine.ThermalLevel.HOT -> "sıcak"
            com.noluryard.autoclicker.engine.ThermalLevel.CRITICAL -> "kritik"
            com.noluryard.autoclicker.engine.ThermalLevel.UNKNOWN -> "-"
        }
        val cpu = if (stats.cpuLoad >= 0f) " · CPU %${(stats.cpuLoad * 100).toInt()}" else ""
        return "$achieved / $target CPS · $thermal$cpu"
    }

    fun notificationLine(context: Context, stats: RunStats): String = when {
        stats.countingDown -> "Başlıyor: ${seconds(stats.countdownMsLeft)} sn"
        stats.running -> counterLine(stats)
        else -> context.getString(R.string.notif_idle_title)
    }

    fun stopTitle(reason: StopReason): String = when (reason) {
        StopReason.MANUAL -> "Durduruldu"
        StopReason.CLICK_LIMIT -> "Tıklama limiti doldu"
        StopReason.TIME_LIMIT -> "Süre doldu"
        StopReason.SCREEN_OFF -> "Ekran kapandı, durduruldu"
        StopReason.VOLUME_KEY -> "Ses tuşuyla durduruldu"
        StopReason.SERVICE_LOST -> "Servis kapandı"
        StopReason.ERROR -> "Hata nedeniyle durdu"
    }

    fun stopBody(stats: RunStats): String {
        val missed = if (stats.missed > 0) " (${stats.missed} atlandı)" else ""
        return "${stats.clicks} tıklama · ${seconds(stats.elapsedMs)} sn$missed"
    }
}

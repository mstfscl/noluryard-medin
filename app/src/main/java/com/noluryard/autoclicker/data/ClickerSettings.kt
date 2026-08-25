package com.noluryard.autoclicker.data

/** Cok noktali tiklamada noktalarin hangi sirayla kullanilacagi. */
enum class ClickPattern { SEQUENTIAL, RANDOM }

/** Ekran uzerinde mutlak piksel konumu (display koordinatlari). */
data class ClickPoint(val x: Float, val y: Float)

/**
 * Tum kullanici ayarlari. DataStore'da saklanir.
 *
 * Sinir degerleri [SettingsBounds] icinde; hem UI hem motor ayni sinirlari kullanir.
 */
data class ClickerSettings(
    /** Kullanicinin istedigi hedef CPS. Otomatik mod acikken bu bir TAVAN gorevi gorur. */
    val targetCps: Int = 10,

    /** Kapali cevrim hiz kontrolcusu acik mi. */
    val autoSpeed: Boolean = true,

    /** Kalibrasyonla olculen cihaz tavani. 0 = henuz kalibre edilmedi. */
    val maxSafeCps: Int = 0,

    /** Her tiklamaya eklenen +/- rastgele gecikme (ms). Bot tespitini zorlastirir. */
    val jitterMs: Int = 0,

    /** Her tiklamada hedef noktaya eklenen +/- rastgele piksel sapmasi. */
    val positionJitterPx: Int = 0,

    /** Parmagin ekranda kaldigi sure (ms). Bazi oyunlar cok kisa dokunusu yok sayar. */
    val touchDurationMs: Int = 10,

    /** Basla dendikten sonra beklenecek sure (sn) – oyuna gecmek icin. */
    val startDelaySec: Int = 0,

    /** Kac hedef nokta kullanilacak (1..4). */
    val pointCount: Int = 1,

    val pattern: ClickPattern = ClickPattern.SEQUENTIAL,

    /** Secilmis hedefler. Bos ise ekran merkezi kullanilir. */
    val points: List<ClickPoint> = emptyList(),

    /** 0 = sinirsiz. Aksi halde bu kadar tiklamadan sonra otomatik durur. */
    val clickLimit: Int = 0,

    /** 0 = sinirsiz. Aksi halde bu kadar saniye sonra otomatik durur. */
    val timeLimitSec: Int = 0,

    val stopOnScreenOff: Boolean = true,
    val volumeKeyStop: Boolean = true,

    /** Balonun son konumu (px). -1 = henuz konumlandirilmadi. */
    val bubbleX: Int = -1,
    val bubbleY: Int = -1,
) {
    /** Otomatik mod acikken motorun cikamayacagi ust sinir. */
    fun effectiveCeiling(): Double {
        val calibrated = if (maxSafeCps > 0) maxSafeCps else SettingsBounds.MAX_CPS
        return minOf(calibrated, targetCps).toDouble()
            .coerceAtLeast(SettingsBounds.MIN_CPS.toDouble())
    }
}

object SettingsBounds {
    const val MIN_CPS = 1
    const val MAX_CPS = 100

    /** Kontrolcunun inebilecegi taban. Altina inmek pratikte "durmus" demek olur. */
    const val CONTROLLER_FLOOR_CPS = 5.0

    const val MAX_JITTER_MS = 50
    const val MAX_POSITION_JITTER_PX = 20
    const val MIN_TOUCH_MS = 1
    const val MAX_TOUCH_MS = 50
    const val MAX_START_DELAY_SEC = 5
    const val MAX_POINTS = 4

    const val MAX_CLICK_LIMIT = 100_000
    const val MAX_TIME_LIMIT_SEC = 3_600

    val CLICK_LIMIT_PRESETS = listOf(0, 10, 50, 100, 500, 1000)
    val TIME_LIMIT_PRESETS = listOf(0, 5, 10, 30, 60, 300)
}

package com.noluryard.autoclicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.noluryard.autoclicker.data.ClickPoint
import kotlin.math.hypot

/**
 * Hedef secme tuvali. Dokunulan yerlere numarali nisangah koyar.
 * Var olan bir nisangaha tekrar dokunmak onu siler (ayri bir "sil" moduna gerek kalmasin).
 */
class TargetCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var maxPoints: Int = 1
    var onPointsChanged: ((List<ClickPoint>) -> Unit)? = null

    private val points = mutableListOf<ClickPoint>()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#4CC2FF")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#664CC2FF")
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setPoints(initial: List<ClickPoint>) {
        points.clear()
        points.addAll(initial.take(maxPoints))
        invalidate()
        onPointsChanged?.invoke(points.toList())
    }

    fun clearPoints() {
        points.clear()
        invalidate()
        onPointsChanged?.invoke(emptyList())
    }

    fun currentPoints(): List<ClickPoint> = points.toList()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true

        // rawX/rawY ekran koordinati; motor da ekran koordinatiyla jest gonderdigi
        // icin ikisi ayni uzayda olmali.
        val screenX = event.rawX
        val screenY = event.rawY

        val existing = points.indexOfFirst { hypot(it.x - screenX, it.y - screenY) < HIT_RADIUS }
        when {
            existing >= 0 -> points.removeAt(existing)
            points.size < maxPoints -> points.add(ClickPoint(screenX, screenY))
            else -> {
                // Kota dolu: en eskisini dusur, yenisini ekle (kullanici tekrar tekrar
                // "temizle"ye basmak zorunda kalmasin).
                points.removeAt(0)
                points.add(ClickPoint(screenX, screenY))
            }
        }
        invalidate()
        onPointsChanged?.invoke(points.toList())
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val origin = IntArray(2)
        getLocationOnScreen(origin)

        points.forEachIndexed { index, point ->
            val cx = point.x - origin[0]
            val cy = point.y - origin[1]
            canvas.drawCircle(cx, cy, RADIUS, fillPaint)
            canvas.drawCircle(cx, cy, RADIUS, ringPaint)
            canvas.drawLine(cx - RADIUS, cy, cx + RADIUS, cy, crossPaint)
            canvas.drawLine(cx, cy - RADIUS, cx, cy + RADIUS, crossPaint)
            canvas.drawText(
                "${index + 1}",
                cx,
                cy - RADIUS - 12f,
                labelPaint,
            )
        }
    }

    private companion object {
        const val RADIUS = 46f
        const val HIT_RADIUS = 60f
    }
}

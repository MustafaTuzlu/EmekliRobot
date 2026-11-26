package com.emekli.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.emekli.robot.core.UsageBuckets

/** Basit yığılmış çubuk grafik (gönderilen + alınan). Kütüphane yok. Çubuğa dokununca `onSelect` çağrılır. */
class UsageChartView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    private var data: List<UsageBuckets.Bucket> = emptyList()
    private var selected = -1
    var onSelect: ((UsageBuckets.Bucket?) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.brand_green) }
    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.brand_navy) }
    private val selPaint = Paint().apply { color = 0x2217895A }
    private val axis = Paint().apply { color = 0xFFBBBBBB.toInt(); strokeWidth = 1 * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); textSize = 10 * d; textAlign = Paint.Align.CENTER }
    private val maxText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); textSize = 10 * d }

    fun setData(list: List<UsageBuckets.Bucket>) {
        data = list
        selected = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return
        val left = 4 * d; val right = width - 4 * d
        val top = 18 * d; val bottom = height - 22 * d
        val max = (data.maxOf { it.total }).coerceAtLeast(1L)
        canvas.drawText(UsageBuckets.formatBytes(max), left, 12 * d, maxText)
        canvas.drawLine(left, bottom, right, bottom, axis)

        val slot = (right - left) / data.size
        val barW = slot * 0.68f
        val every = if (data.size > 14) 3 else if (data.size > 8) 2 else 1
        data.forEachIndexed { i, b ->
            val cx = left + slot * i + slot / 2
            if (i == selected) canvas.drawRect(cx - slot / 2, top, cx + slot / 2, bottom, selPaint)
            val hTx = (b.tx.toFloat() / max) * (bottom - top)
            val hRx = (b.rx.toFloat() / max) * (bottom - top)
            canvas.drawRect(cx - barW / 2, bottom - hRx, cx + barW / 2, bottom, rxPaint)
            canvas.drawRect(cx - barW / 2, bottom - hRx - hTx, cx + barW / 2, bottom - hRx, txPaint)
            if (i % every == 0) canvas.drawText(b.label, cx, bottom + 14 * d, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (data.isEmpty()) return false
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val slot = (width - 8 * d) / data.size
            val i = ((e.x - 4 * d) / slot).toInt().coerceIn(0, data.size - 1)
            if (i != selected) {
                selected = i
                invalidate()
                onSelect?.invoke(data[i])
            }
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onTouchEvent(e)
    }
}

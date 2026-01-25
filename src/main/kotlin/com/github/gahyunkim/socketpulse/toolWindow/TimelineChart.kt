package com.github.gahyunkim.socketpulse.toolWindow

import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent
import kotlin.math.max

class TimelineChart(
    private val store: EventTimelineStore,
    private val windowMs: Long = 30_000L,
    private val rowsProvider: () -> List<String>, // 표시할 이벤트 row 목록
) : JComponent() {

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D

        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val now = System.currentTimeMillis()

        val rows = rowsProvider()
        if (rows.isEmpty()) return

        val rowH = max(1, h / rows.size)

        // 각 row에 이벤트 스파이크 찍기
        rows.forEachIndexed { rowIdx, eventName ->
            val yTop = rowIdx * rowH
            val yBottom = (yTop + rowH - 1)

            val times = store.snapshot(eventName, now)
            for (t in times) {
                val age = now - t
                if (age < 0 || age > windowMs) continue

                val ratio = age.toDouble() / windowMs.toDouble() // 0=방금, 1=window 끝
                val x = (w - 1) - (ratio * (w - 1)).toInt()      // 오른쪽이 현재

                g2.drawLine(x, yTop + 2, x, yBottom - 2)
            }
        }
    }
}

package com.github.gahyunkim.socketpulse.toolWindow

import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThroughputChart(val store: ByteRateStore, val windowSec: Int) : JBPanel<ThroughputChart>() {
    private var selectionStart: Int? = null
    private var selectionEnd: Int? = null
    var onRangeSelected: ((Long, Long) -> Unit)? = null

    init {
        val adapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                selectionStart = e.x
                selectionEnd = e.x
                repaint()
            }
            override fun mouseDragged(e: MouseEvent) {
                selectionEnd = e.x
                repaint()
            }
            override fun mouseReleased(e: MouseEvent) {
                if (selectionStart != null && selectionEnd != null) {
                    val range = calculateTimeRange(selectionStart!!, selectionEnd!!)
                    onRangeSelected?.invoke(range.first, range.second)
                }
            }
        }
        addMouseListener(adapter)
        addMouseMotionListener(adapter)
    }

    private fun calculateTimeRange(x1: Int, x2: Int): Pair<Long, Long> {
        val startX = minOf(x1, x2).coerceAtLeast(0)
        val endX = maxOf(x1, x2).coerceAtMost(width)
        val now = System.currentTimeMillis()
        val startTime = now - (windowSec * 1000L) * (width - startX) / width
        val endTime = now - (windowSec * 1000L) * (width - endX) / width
        return startTime to endTime
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // 1. 배경 및 격자(Grid) - 연하게 수정
        g2.color = Color(30, 30, 30)
        g2.fillRect(0, 0, w, h)
        g2.color = Color(45, 45, 45)
        for (i in 0 until h step 20) g2.drawLine(0, i, w, i)
        for (i in 0 until w step 50) g2.drawLine(i, 0, i, h)

        // 2. 데이터 가져오기 (다시 snapshot을 써야 왼쪽으로 흐릅니다)
        val (rxData, txData) = store.snapshot()
        val points = rxData.size
        val xStep = w.toDouble() / (points - 1).coerceAtLeast(1)

        if (points > 0) {
            val maxVal = rxData.maxOrNull()?.coerceAtLeast(txData.maxOrNull() ?: 0L) ?: 100L
            val finalMax = maxVal.coerceAtLeast(100L)

            val xPoints = IntArray(points)
            val yRxPoints = IntArray(points)
            val yTxPoints = IntArray(points)

            for (i in 0 until points) {
                xPoints[i] = (i * xStep).toInt()
                yRxPoints[i] = h - (rxData[i] * h / finalMax).toInt()
                yTxPoints[i] = h - (txData[i] * h / finalMax).toInt()
            }

            // RX 그래프 (파란색)
            g2.color = Color(64, 128, 255)
            g2.stroke = BasicStroke(2f)
            g2.drawPolyline(xPoints, yRxPoints, points)

            // RX 영역 채우기
            g2.color = Color(64, 128, 255, 40)
            val fillX = xPoints + intArrayOf(xPoints.last(), xPoints.first())
            val fillY = yRxPoints + intArrayOf(h, h)
            g2.fillPolygon(fillX, fillY, points + 2)

            // TX 그래프 (보라색 점선)
            g2.color = Color(150, 64, 255, 180)
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(5f), 0f)
            g2.drawPolyline(xPoints, yTxPoints, points)

            // 실시간 수치 표시 (우측 상단)
            g2.color = Color.WHITE
            g2.font = JBUI.Fonts.smallFont()
            g2.drawString("Receiving: ${rxData.last()} B/s", w - 240, 20)
            g2.drawString("Sending: ${txData.last()} B/s", w - 120, 20)
        }

        // 3. X축 시간 라벨
        g2.color = Color.GRAY
        val nowMs = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("ss.SSS", Locale.KOREA)
        for (i in 0 until windowSec step 5) {
            val x = (i * xStep).toInt()
            val labelStr = timeFmt.format(Date(nowMs - (windowSec - 1 - i) * 1000L))
            g2.drawString(labelStr, x + 2, h - 5)
            g2.drawLine(x, h - 15, x, h)
        }

        // 4. 드래그 하이라이트
        selectionStart?.let { start ->
            selectionEnd?.let { end ->
                val startX = minOf(start, end)
                val rectW = kotlin.math.abs(start - end)
                g2.color = Color(64, 128, 255, 60)
                g2.fillRect(startX, 0, rectW, h)
            }
        }
    }
}
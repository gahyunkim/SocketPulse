package com.github.gahyunkim.socketpulse.toolWindow

import com.intellij.ui.components.JBPanel
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

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
                // 선택 영역을 유지하고 싶지 않다면 아래 주석을 해제하세요
                // selectionStart = null; selectionEnd = null; repaint()
            }
        }
        addMouseListener(adapter)
        addMouseMotionListener(adapter)
    }

    private fun calculateTimeRange(x1: Int, x2: Int): Pair<Long, Long> {
        val startX = minOf(x1, x2).coerceAtLeast(0)
        val endX = maxOf(x1, x2).coerceAtMost(width)
        val now = System.currentTimeMillis()

        // X좌표 비율에 따른 시간 역산
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

        // 1. 데이터 가져오기 (메서드 이름과 타입 수정)
        val (rxData, txData) = store.snapshot()
        val points = rxData.size // 보통 windowSec와 같음 (30)

        if (points > 0) {
            // 최대값 계산 (RX와 TX 중 가장 큰 값 기준, 최소 100바이트)
            val maxVal = (rxData.maxOrNull() ?: 0L).coerceAtLeast(txData.maxOrNull() ?: 0L).coerceAtLeast(100L)

            val xStep = w.toDouble() / (points - 1).coerceAtLeast(1)
            val xPoints = IntArray(points)
            val yPoints = IntArray(points)

            // RX 그래프 그리기 (파란색)
            for (i in 0 until points) {
                xPoints[i] = (i * xStep).toInt()
                yPoints[i] = h - (rxData[i] * h / maxVal).toInt()
            }

            g2.color = Color(64, 128, 255)
            g2.stroke = BasicStroke(2f)
            g2.drawPolyline(xPoints, yPoints, points)

            // RX 영역 채우기
            g2.color = Color(64, 128, 255, 40)
            val fillX = xPoints + intArrayOf(xPoints.last(), xPoints.first())
            val fillY = yPoints + intArrayOf(h, h)
            g2.fillPolygon(fillX, fillY, points + 2)

            // TX 그래프 추가로 그리기 (선택사항 - 보라색 점선)
            val yTxPoints = IntArray(points)
            for (i in 0 until points) {
                yTxPoints[i] = h - (txData[i] * h / maxVal).toInt()
            }
            g2.color = Color(150, 64, 255, 180)
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(5f), 0f)
            g2.drawPolyline(xPoints, yTxPoints, points)
        }

        // 2. 드래그 영역 하이라이트
        selectionStart?.let { start ->
            selectionEnd?.let { end ->
                val startX = minOf(start, end)
                val rectW = kotlin.math.abs(start - end)
                g2.color = Color(64, 128, 255, 60) // 좀 더 연하게 조정
                g2.fillRect(startX, 0, rectW, h)
                g2.color = Color(64, 128, 255, 150)
                g2.drawRect(startX, 0, rectW, h)
            }
        }
    }

    fun clearSelection() {
        selectionStart = null
        selectionEnd = null
        repaint()
    }
}
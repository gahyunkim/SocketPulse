package com.github.gahyunkim.socketpulse.toolWindow

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class EventTimelineStore(
    private val windowMs: Long = 30_000L, // 최근 30초
    private val maxPerEvent: Int = 5_000  // 이벤트 하나에 너무 쌓이지 않게
) {
    private val map = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    fun record(eventName: String, atMs: Long = System.currentTimeMillis()) {
        val deque = map.computeIfAbsent(eventName) { ConcurrentLinkedDeque() }
        deque.addLast(atMs)

        // 대충 상한 유지
        while (deque.size > maxPerEvent) deque.pollFirst()

        // 오래된 것 정리
        pruneOld(eventName, deque, atMs)
    }

    fun snapshot(eventName: String, nowMs: Long = System.currentTimeMillis()): List<Long> {
        val deque = map[eventName] ?: return emptyList()
        pruneOld(eventName, deque, nowMs)
        return deque.toList()
    }

    fun snapshotTopEvents(nowMs: Long = System.currentTimeMillis(), topN: Int = 6): List<Pair<String, Int>> {
        // 최근 window 내에서 많이 발생한 이벤트 topN 뽑기 (간단 버전)
        val counts = mutableListOf<Pair<String, Int>>()
        for ((name, deque) in map.entries) {
            pruneOld(name, deque, nowMs)
            counts += name to deque.size
        }
        return counts.sortedByDescending { it.second }.take(topN)
    }

    private fun pruneOld(eventName: String, deque: ConcurrentLinkedDeque<Long>, nowMs: Long) {
        val threshold = nowMs - windowMs
        while (true) {
            val first = deque.peekFirst() ?: break
            if (first >= threshold) break
            deque.pollFirst()
        }
        // 비면 맵에서 정리(선택)
        if (deque.isEmpty()) map.remove(eventName, deque)
    }
}

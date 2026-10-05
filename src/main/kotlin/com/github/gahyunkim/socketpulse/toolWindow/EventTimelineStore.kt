package com.github.gahyunkim.socketpulse.toolWindow

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 이벤트 이름별 발생 시각을 동시성 컬렉션에 보관합니다.
 * 복합 정리·집계 연산은 원자적이지 않으며 시각 순서대로 기록하는 것을 전제로 합니다.
 * @property windowMs 보관 기간(밀리초).
 * @property maxPerEvent 이벤트별 최대 기록 수. 양수로 지정합니다.
 */
class EventTimelineStore(
    private val windowMs: Long = 30_000L, // 최근 30초
    private val maxPerEvent: Int = 5_000  // 이벤트 하나에 너무 쌓이지 않게
) {
    private val map = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    /**
     * [eventName]의 [atMs]를 기록하고 개수 상한과 보관 기간을 적용합니다.
     */
    fun record(eventName: String, atMs: Long = System.currentTimeMillis()) {
        val deque = map.computeIfAbsent(eventName) { ConcurrentLinkedDeque() }
        deque.addLast(atMs)

        // 대충 상한 유지
        while (deque.size > maxPerEvent) deque.pollFirst()

        // 오래된 것 정리
        pruneOld(eventName, deque, atMs)
    }

    /**
     * [nowMs] 기준 오래된 기록을 정리한 뒤 [eventName]의 시각 목록을 복사합니다.
     */
    fun snapshot(eventName: String, nowMs: Long = System.currentTimeMillis()): List<Long> {
        val deque = map[eventName] ?: return emptyList()
        pruneOld(eventName, deque, nowMs)
        return deque.toList()
    }

    /**
     * 최근 구간의 발생 횟수 내림차순으로 최대 [topN]개 이벤트를 반환합니다.
     */
    fun snapshotTopEvents(nowMs: Long = System.currentTimeMillis(), topN: Int = 6): List<Pair<String, Int>> {
        // 최근 window 내에서 많이 발생한 이벤트 topN 뽑기 (간단 버전)
        val counts = mutableListOf<Pair<String, Int>>()
        for ((name, deque) in map.entries) {
            pruneOld(name, deque, nowMs)
            counts += name to deque.size
        }
        return counts.sortedByDescending { it.second }.take(topN)
    }

    /**
     * 기간 이전 기록을 큐 앞에서 제거하고 빈 큐는 맵에서 제거합니다.
     */
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

package com.github.gahyunkim.socketpulse.toolWindow

class ByteRateStore(
    private val windowSec: Int = 30
) {
    // ThroughputChart에서 접근할 수 있도록 private 제거
    data class Bucket(var sec: Long, var rx: Long, var tx: Long)

    private val buckets = Array(windowSec) { Bucket(0L, 0, 0) }

    // ✅ 차트가 원본 배열에 접근할 수 있도록 추가
    fun getRawBuckets(): Array<Bucket> = buckets

    @Synchronized
    fun addRx(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(bytes, true, nowMs)

    @Synchronized
    fun addTx(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(bytes, false, nowMs)

    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): Pair<LongArray, LongArray> {
        val nowSec = nowMs / 1000
        val rxArr = LongArray(windowSec)
        val txArr = LongArray(windowSec)

        for (i in 0 until windowSec) {
            val sec = nowSec - (windowSec - 1 - i)
            val idx = (sec % windowSec).toInt()
            val b = buckets[idx]
            if (b.sec == sec) {
                rxArr[i] = b.rx
                txArr[i] = b.tx
            } else {
                rxArr[i] = 0
                txArr[i] = 0
            }
        }
        return rxArr to txArr
    }

    private fun add(bytes: Long, isRx: Boolean, nowMs: Long) {
        val sec = nowMs / 1000
        val idx = (sec % windowSec).toInt()
        val b = buckets[idx]
        if (b.sec != sec) {
            buckets[idx] = Bucket(sec, 0, 0)
        }
        val cur = buckets[idx]
        if (isRx) cur.rx += bytes else cur.tx += bytes
    }
}
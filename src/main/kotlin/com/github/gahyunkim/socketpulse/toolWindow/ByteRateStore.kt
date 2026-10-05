package com.github.gahyunkim.socketpulse.toolWindow

/**
 * 초 단위 원형 버퍼에 RX/TX 바이트를 누적합니다.
 * @property windowSec 보관할 초 수. 양수로 지정합니다.
 */
class ByteRateStore(
    private val windowSec: Int = 30
) {
    // ThroughputChart에서 접근할 수 있도록 private 제거
    /**
     * 초별 누적값입니다. sec는 Unix 시각(초), rx와 tx는 바이트 수입니다.
     */
    data class Bucket(var sec: Long, var rx: Long, var tx: Long)

    private val buckets = Array(windowSec) { Bucket(0L, 0, 0) }

    // ✅ 차트가 원본 배열에 접근할 수 있도록 추가
    /**
     * 내부 배열을 복사 없이 반환합니다. 동기화되지 않으며 변경 시 저장소에도 반영됩니다.
     */
    fun getRawBuckets(): Array<Bucket> = buckets

    /**
     * [nowMs]가 속한 초에 수신 [bytes]를 누적합니다. 호출은 동기화됩니다.
     */
    @Synchronized
    fun addRx(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(bytes, true, nowMs)

    /**
     * [nowMs]가 속한 초에 송신 [bytes]를 누적합니다. 호출은 동기화됩니다.
     */
    @Synchronized
    fun addTx(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(bytes, false, nowMs)

    /**
     * [nowMs] 기준 오래된 초부터 현재 초까지 RX/TX 배열을 복사해 반환합니다.
     * 기록이 없는 초는 0입니다. 반환 쌍의 첫 배열은 RX, 두 번째는 TX입니다.
     */
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

    /**
     * 다른 초의 버킷은 초기화하고 해당 방향의 바이트 수를 더합니다.
     */
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

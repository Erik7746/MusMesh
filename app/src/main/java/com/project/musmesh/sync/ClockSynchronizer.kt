package com.project.musmesh.sync

import kotlin.math.abs

data class ClockSample(val t1: Long, val t2: Long, val t3: Long, val t4: Long) {
    val rtt get() = (t4 - t1) - (t3 - t2)
    /** Remote/node minus server. */
    val offset get() = ((t2 - t1) + (t3 - t4)) / 2
}

class ClockSynchronizer {
    private val samples = ArrayDeque<ClockSample>()
    var updatedAt = 0L; private set
    val ready get() = samples.size >= 3
    val bestRtt get() = samples.minOfOrNull { it.rtt } ?: Long.MAX_VALUE
    val offset: Long get() {
        val best = samples.sortedBy { it.rtt }.take(5).map { it.offset }.sorted()
        return best.getOrElse(best.size / 2) { 0L }
    }
    fun add(sample: ClockSample): Boolean {
        if (sample.t4 < sample.t1 || sample.t3 < sample.t2 || sample.rtt !in 0..1_500_000_000) return false
        if (samples.size >= 3 && sample.rtt > bestRtt * 3 + 20_000_000) return false
        samples.addLast(sample); while (samples.size > 20) samples.removeFirst()
        updatedAt = sample.t4
        return true
    }
    companion object {
        fun toLocal(serverNanos: Long, nodeMinusServer: Long, outputOffsetMs: Int = 0) = serverNanos + nodeMinusServer - outputOffsetMs * 1_000_000L
    }
}

data class DriftCorrection(val speed: Float = 1f, val seekMs: Long? = null)
class DriftCorrector(private val hardLimitMs: Long = 250, private val cooldownMs: Long = 5000) {
    private var lastCorrection = -cooldownMs
    fun correct(actualMs: Long, expectedMs: Long, nowMs: Long): DriftCorrection {
        if (nowMs - lastCorrection < cooldownMs) return DriftCorrection()
        val error = expectedMs - actualMs
        if (abs(error) < 25) return DriftCorrection()
        lastCorrection = nowMs
        return if (abs(error) > hardLimitMs) DriftCorrection(seekMs = expectedMs.coerceAtLeast(0))
        else DriftCorrection(speed = if (error > 0) 1.005f else 0.995f)
    }
}

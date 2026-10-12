package com.project.musmesh.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.PriorityQueue

data class LinkMetrics(val mtu: Int = 23, val queued: Int = 0, val pendingBytes: Int = 0, val rttMs: Long = 0,
    val sent: Long = 0, val retries: Long = 0, val errors: Long = 0, val bytes: Long = 0)

/** Confined to the transport dispatcher. GATT acceptance only starts an operation; its callback ends it. */
class BleSendQueue(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val write: (ByteArray) -> Boolean,
    private val disconnect: () -> Unit
) {
    private data class Item(val priority: Int, val order: Long, val bytes: ByteArray, val result: CompletableDeferred<Boolean>)
    private val queue = PriorityQueue<Item>(compareBy<Item> { it.priority }.thenBy { it.order })
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val callbacks = Channel<Boolean>(1)
    private var order = 0L
    private var pendingBytes = 0
    private var active: Item? = null
    private var closed = false
    var metrics = LinkMetrics(); private set
    private val worker = scope.launch {
        for (ignored in wake) {
            while (queue.isNotEmpty() && !closed) {
                val item = queue.remove(); active = item
                var success = false
                for (attempt in 0..3) {
                    val start = nowMs()
                    if (!runCatching { write(item.bytes) }.getOrDefault(false)) {
                        metrics = metrics.copy(errors = metrics.errors + 1, retries = metrics.retries + 1)
                        delay(40L * (attempt + 1)); continue
                    }
                    val callback = withTimeoutOrNull(5000) { callbacks.receive() }
                    if (callback == null) {
                        // A missing callback makes operation ownership ambiguous. Close this link generation.
                        item.result.complete(false); close(); disconnect(); return@launch
                    }
                    if (callback) {
                        success = true
                        metrics = metrics.copy(rttMs = nowMs() - start, sent = metrics.sent + 1, bytes = metrics.bytes + item.bytes.size)
                        break
                    }
                    metrics = metrics.copy(errors = metrics.errors + 1, retries = metrics.retries + 1)
                    delay(40L * (attempt + 1))
                }
                pendingBytes -= item.bytes.size; active = null; item.result.complete(success); updateMetrics()
                if (!success) { close(); disconnect(); return@launch }
            }
        }
    }
    private fun updateMetrics() { metrics = metrics.copy(queued = queue.size + if (active == null) 0 else 1, pendingBytes = pendingBytes) }
    suspend fun send(chunks: List<ByteArray>, priority: Int): Boolean {
        val bytes = chunks.sumOf { it.size }
        val byteLimit = if (priority < 2) 65536 else 49152
        val itemLimit = if (priority < 2) 1024 else 768
        if (closed || pendingBytes + bytes > byteLimit || queue.size + chunks.size > itemLimit) return false
        val results = chunks.map { chunk -> CompletableDeferred<Boolean>().also { queue.add(Item(priority, order++, chunk, it)) } }
        pendingBytes += bytes; updateMetrics(); wake.trySend(Unit)
        return try { results.awaitAll().all { it } } finally {
            // Do not cancel the active ATT operation; only its callback can release it.
            val pending = queue.filter { it.result in results }
            pending.forEach { queue.remove(it); pendingBytes -= it.bytes.size; it.result.complete(false) }
            updateMetrics()
        }
    }
    fun callback(success: Boolean) { if (active != null && !closed) callbacks.trySend(success) }
    fun cancelData() {
        val pending = queue.filter { it.priority == 2 }
        pending.forEach { queue.remove(it); pendingBytes -= it.bytes.size; it.result.complete(false) }; updateMetrics()
    }
    fun close() {
        if (closed) return
        closed = true; active?.result?.complete(false); queue.forEach { it.result.complete(false) }
        queue.clear(); pendingBytes = 0; active = null; updateMetrics(); wake.close(); worker.cancel()
    }
}

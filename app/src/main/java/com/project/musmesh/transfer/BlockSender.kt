package com.project.musmesh.transfer

import com.project.musmesh.protocol.PacketType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

class BlockSender(private val nowMs: () -> Long, private val send: suspend (PacketType, ByteArray) -> Boolean) {
    val acknowledgements = Channel<BlockAck>(64)
    var retries = 0; private set
    var confirmedFragments = 0; private set
    var sentFragments = 0L; private set
    suspend fun sendBlock(manifest: MusicManifest, block: Int, data: ByteArray, chunk: Int): Boolean {
        val count = (data.size + chunk - 1) / chunk
        confirmedFragments = 0
        val window = AckWindow(count)
        var lastProbe = -10000L
        var probes = 0
        while (currentCoroutineContext().isActive) {
            while (true) {
                val ack = acknowledgements.tryReceive().getOrNull() ?: break
                if (ack.transfer != manifest.id || ack.block != block || ack.count != count) continue
                if (ack.fragments.isEmpty && !ack.persisted) window.resetAfterNack()
                window.acknowledge(ack.fragments, nowMs())
                confirmedFragments = window.acknowledged.cardinality()
                if (ack.persisted) return true
            }
            val now = nowMs()
            val due = window.due(now).ifEmpty {
                if (window.acknowledged.cardinality() == count && now - lastProbe >= window.timeoutMs) {
                    check(probes++ < 8) { "No llegó confirmación persistida del bloque" }
                    lastProbe = now; listOf(window.probeIndex())
                } else emptyList()
            }
            for (index in due) {
                val bytes = data.copyOfRange(index * chunk, minOf(data.size, (index + 1) * chunk))
                window.sent(index, nowMs()); retries = window.retries
                sentFragments++
                val fragment = BlockFragment(manifest.id, block, index, count, chunk, manifest.blockHashes[block], bytes)
                if (!send(PacketType.BLOCK_DATA, fragment.encode())) return false
            }
            delay(10)
        }
        return false
    }
}

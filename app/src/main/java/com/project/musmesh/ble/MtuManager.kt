package com.project.musmesh.ble

import com.project.musmesh.protocol.ProtocolConstants
import java.nio.ByteBuffer
import java.util.BitSet

object MtuManager {
    const val LINK_HEADER = 8
    fun attCapacity(mtu: Int) = (mtu.coerceIn(23, 517) - 3).coerceAtMost(512)
    fun fragmentPayload(mtu: Int): Int = (attCapacity(mtu) - LINK_HEADER - ProtocolConstants.FRAME_OVERHEAD - ProtocolConstants.TRANSFER_HEADER).coerceIn(16, 512)
    // Below the envelope size (including MTU 23), LinkFramer carries the bounded frame in multiple ATT operations.
}

object LinkFramer {
    fun split(frame: ByteArray, mtu: Int, messageId: Int): List<ByteArray> {
        require(frame.size in 1..ProtocolConstants.MAX_FRAME)
        val chunk = MtuManager.attCapacity(mtu) - MtuManager.LINK_HEADER
        return (frame.indices step chunk).map { offset ->
            val size = minOf(chunk, frame.size - offset)
            ByteBuffer.allocate(MtuManager.LINK_HEADER + size).putInt(messageId).putShort(offset.toShort())
                .putShort(frame.size.toShort()).put(frame, offset, size).array()
        }
    }
}

class LinkAssembler {
    private data class Assembly(val data: ByteArray, val received: BitSet, var updatedMs: Long)
    private val assemblies = linkedMapOf<Int, Assembly>()
    private val completed = linkedMapOf<Int, Long>()
    fun receive(bytes: ByteArray, nowMs: Long): ByteArray? {
        assemblies.entries.removeAll { nowMs - it.value.updatedMs > 15_000 }
        completed.entries.removeAll { nowMs - it.value > 15_000 }
        if (bytes.size !in 9..512) return null
        val b = ByteBuffer.wrap(bytes); val id = b.int; val offset = b.short.toInt() and 65535; val total = b.short.toInt() and 65535
        if (id in completed) return null
        val size = b.remaining()
        if (total !in 1..ProtocolConstants.MAX_FRAME || offset + size > total) return null
        val entry = assemblies[id] ?: run {
            if (assemblies.size >= 4) return null
            Assembly(ByteArray(total), BitSet(total), nowMs).also { assemblies[id] = it }
        }
        if (entry.data.size != total) return null
        // Reject overlapping inconsistent bytes rather than combining messages.
        for (i in 0 until size) if (entry.received[offset + i] && entry.data[offset + i] != bytes[8 + i]) { assemblies.remove(id); return null }
        b.get(entry.data, offset, size); entry.received.set(offset, offset + size); entry.updatedMs = nowMs
        return if (entry.received.cardinality() == total) {
            assemblies.remove(id); completed[id] = nowMs
            while (completed.size > 256) completed.remove(completed.keys.first())
            entry.data
        } else null
    }
}

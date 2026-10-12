package com.project.musmesh.transfer

import com.project.musmesh.protocol.*
import java.util.BitSet
import java.util.UUID

data class BlockFragment(val transfer: UUID, val block: Int, val index: Int, val count: Int, val chunkSize: Int, val hash: ByteArray, val data: ByteArray) {
    fun encode() = WireWriter().uuid(transfer).short(block).short(index).short(count).short(chunkSize).raw(hash).raw(data).build()
    companion object {
        fun decode(bytes: ByteArray): BlockFragment? {
            if (bytes.size <= ProtocolConstants.TRANSFER_HEADER) return null
            return decodePayload(bytes) {
                val id = uuid(); val block = short(); val index = short(); val count = short(); val chunk = short(); val hash = raw(32)
                require(block < 160 && count in 1..ProtocolConstants.MAX_FRAGMENTS && index < count && chunk in 16..512)
                val data = raw(bytes.size - ProtocolConstants.TRANSFER_HEADER)
                require(data.size in 1..chunk)
                BlockFragment(id, block, index, count, chunk, hash, data)
            }
        }
    }
}

data class BlockAck(val transfer: UUID, val block: Int, val count: Int, val fragments: BitSet, val persisted: Boolean) {
    fun encode() = WireWriter().uuid(transfer).short(block).short(count).byte(if (persisted) 1 else 0).blob(fragments.toByteArray()).build()
    companion object {
        fun decode(bytes: ByteArray) = decodePayload(bytes) {
            val id = uuid(); val block = short(); val count = short(); val durable = byte(); val bits = BitSet.valueOf(blob(256))
            require(block < 160 && count in 1..ProtocolConstants.MAX_FRAGMENTS && bits.length() <= count && durable in 0..1)
            require(durable == 0 || bits.cardinality() == count)
            BlockAck(id, block, count, bits, durable == 1)
        }
    }
}

data class ResumeRequest(val transfer: UUID, val hash: ByteArray, val blocks: BitSet) {
    fun encode() = WireWriter().uuid(transfer).raw(hash).blob(blocks.toByteArray()).build()
    companion object { fun decode(bytes: ByteArray) = decodePayload(bytes) { ResumeRequest(uuid(), raw(32), BitSet.valueOf(blob(20))) } }
}

class BlockReceiver(private val repository: TransferRepository) {
    private var partial: Partial? = null
    private var cached: StoredTransfer? = null
    private data class Partial(val first: BlockFragment, val bytes: ByteArray, val received: BitSet)
    @Synchronized fun discardPartial() { partial = null; cached = null }
    @Synchronized fun receive(fragment: BlockFragment): BlockAck? {
        val stored = cached?.takeIf { it.manifest.id == fragment.transfer }
            ?: repository.load(fragment.transfer)?.also { cached = it } ?: return null
        val manifest = stored.manifest
        if (fragment.block !in 0 until manifest.blockCount) return null
        val size = manifest.blockSize(fragment.block)
        if (fragment.count != (size + fragment.chunkSize - 1) / fragment.chunkSize ||
            !fragment.hash.contentEquals(manifest.blockHashes[fragment.block])) return null
        val offset = fragment.index * fragment.chunkSize
        if (fragment.data.size != minOf(fragment.chunkSize, size - offset)) return null
        if (stored.completed[fragment.block]) return BlockAck(fragment.transfer, fragment.block, fragment.count,
            BitSet().apply { set(0, fragment.count) }, true)
        var p = partial
        if (p == null || p.first.transfer != fragment.transfer || p.first.block != fragment.block || p.first.chunkSize != fragment.chunkSize) {
            p = Partial(fragment, ByteArray(size), BitSet(fragment.count)); partial = p
        }
        fragment.data.copyInto(p.bytes, offset); p.received.set(fragment.index)
        val done = p.received.cardinality() == fragment.count
        if (done) {
            if (!FileVerifier.matches(p.bytes, manifest.blockHashes[fragment.block])) { partial = null; return BlockAck(fragment.transfer, fragment.block, fragment.count, BitSet(), false) }
            cached = repository.writeBlock(fragment.transfer, fragment.block, p.bytes)
            partial = null
        }
        return BlockAck(fragment.transfer, fragment.block, fragment.count, p.received.clone() as BitSet, done)
    }
}

/** Tracks receiver ACKs, never GATT callbacks. Retry timestamps are measured by a monotonic clock. */
class AckWindow(val count: Int, private val maxRetries: Int = 8, initialWindow: Int = 16) {
    val acknowledged = BitSet(count)
    private val sentAt = LongArray(count) { -1 }
    private val attempts = IntArray(count)
    var window = initialWindow.coerceIn(1, 32); private set
    var rttMs = 300L; private set
    var retries = 0; private set
    val timeoutMs get() = (rttMs * 4 + 300).coerceIn(800, 8000)
    init { require(count in 1..ProtocolConstants.MAX_FRAGMENTS) }
    fun acknowledge(bits: BitSet, now: Long) {
        require(bits.length() <= count)
        for (i in 0 until count) if (bits[i] && !acknowledged[i] && sentAt[i] >= 0) {
            if (attempts[i] == 1) rttMs = ((rttMs * 3 + (now - sentAt[i]).coerceAtLeast(1)) / 4).coerceIn(20, 2000)
            acknowledged.set(i)
        }
        if (bits.cardinality() > 0) window = (window + 1).coerceAtMost(32)
    }
    fun due(now: Long): List<Int> {
        val outstanding = (0 until count).count { !acknowledged[it] && sentAt[it] >= 0 && now - sentAt[it] < timeoutMs }
        val available = (window - outstanding).coerceAtLeast(0)
        return (0 until count).filter { !acknowledged[it] && (sentAt[it] < 0 || now - sentAt[it] >= timeoutMs) }.take(available)
    }
    fun sent(index: Int, now: Long) {
        require(index in 0 until count)
        check(attempts[index] <= maxRetries) { "Se agotaron los reintentos; transferencia pausada" }
        if (attempts[index]++ > 0) { retries++; window = (window / 2).coerceAtLeast(2) }
        sentAt[index] = now
    }
    fun probeIndex() = count - 1 // ACK of the persisted block may itself have been lost.
    fun resetAfterNack() { acknowledged.clear(); sentAt.fill(-1); window = minOf(window, 16) }
}

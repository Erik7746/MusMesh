package com.project.musmesh.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID

class WireWriter {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)
    fun byte(value: Int) = apply { output.writeByte(value) }
    fun short(value: Int) = apply { output.writeShort(value) }
    fun int(value: Int) = apply { output.writeInt(value) }
    fun long(value: Long) = apply { output.writeLong(value) }
    fun uuid(value: UUID) = long(value.mostSignificantBits).long(value.leastSignificantBits)
    fun raw(value: ByteArray) = apply { output.write(value) }
    fun blob(value: ByteArray) = short(value.size).raw(value)
    fun text(value: String, max: Int = 160) = blob(value.toByteArray(Charsets.UTF_8).take(max).toByteArray())
    fun build() = bytes.toByteArray()
}

class WireReader(bytes: ByteArray) {
    private val input = ByteBuffer.wrap(bytes)
    fun byte() = input.get().toInt() and 255
    fun short() = input.short.toInt() and 65535
    fun int() = input.int
    fun long() = input.long
    fun uuid() = UUID(long(), long())
    fun raw(size: Int): ByteArray {
        require(size in 0..input.remaining())
        return ByteArray(size).also(input::get)
    }
    fun blob(max: Int) = raw(short().also { require(it <= max) })
    fun text(max: Int = 160) = blob(max).toString(Charsets.UTF_8).filter { !it.isISOControl() }
    fun end() { require(!input.hasRemaining()) }
}

inline fun <T> decodePayload(bytes: ByteArray, block: WireReader.() -> T): T? = runCatching {
    require(bytes.size <= ProtocolConstants.MAX_PAYLOAD)
    WireReader(bytes).run { block().also { end() } }
}.getOrNull()

data class ControlPacket(val transferId: UUID, val sequence: Long, val atNanos: Long, val positionMs: Long, val playing: Boolean) {
    fun encode() = WireWriter().uuid(transferId).long(sequence).long(atNanos).long(positionMs).byte(if (playing) 1 else 0).build()
    companion object {
        fun decode(bytes: ByteArray) = decodePayload(bytes) {
            val id = uuid(); val sequence = long(); val at = long(); val pos = long(); val play = byte()
            require(sequence > 0 && at >= 0 && pos in 0..86_400_000 && play in 0..1)
            ControlPacket(id, sequence, at, pos, play == 1)
        }
    }
}

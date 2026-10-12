package com.project.musmesh.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class MeshPacket(
    val type: PacketType,
    val packetId: Long,
    val sender: Long,
    val destination: Long,
    val room: UUID,
    val session: Long,
    val sequence: Long,
    val ttl: Int = ProtocolConstants.MAX_HOPS,
    val payload: ByteArray = byteArrayOf()
)

/** Replaceable authenticated envelope; Noise can implement this boundary later. */
interface FrameAuthenticator {
    fun tag(bytes: ByteArray): ByteArray
    fun verify(bytes: ByteArray, tag: ByteArray) = MessageDigest.isEqual(tag(bytes), tag)
}

class RoomAuthenticator(code: String) : FrameAuthenticator {
    private val key = MessageDigest.getInstance("SHA-256").digest(("MusMesh/v1/" + code).toByteArray())
    override fun tag(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(bytes).copyOf(ProtocolConstants.AUTH_SIZE)
    }
}

object Ids {
    private val random = SecureRandom()
    val EMPTY_ROOM = UUID(0, 0)
    fun node(): Long = (random.nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
    fun code(): String = (100000 + random.nextInt(900000)).toString()
}

class PacketCodec(private val auth: FrameAuthenticator) {
    fun encode(packet: MeshPacket): ByteArray {
        require(packet.payload.size <= ProtocolConstants.MAX_PAYLOAD)
        require(packet.sender > 0 && packet.session > 0 && packet.sequence > 0 && packet.packetId > 0)
        require(packet.ttl in 0..ProtocolConstants.MAX_HOPS)
        val buffer = ByteBuffer.allocate(ProtocolConstants.FRAME_OVERHEAD + packet.payload.size)
        buffer.putShort(ProtocolConstants.MAGIC.toShort()).put(ProtocolConstants.VERSION.toByte())
            .put(packet.type.wire.toByte()).putLong(packet.packetId).putLong(packet.sender)
            .putLong(packet.destination).putLong(packet.room.mostSignificantBits).putLong(packet.room.leastSignificantBits)
            .putLong(packet.session).putLong(packet.sequence).put(packet.ttl.toByte())
            .putShort(packet.payload.size.toShort()).put(packet.payload)
        val crc = CRC32().apply { update(buffer.array(), 0, buffer.position()) }.value.toInt()
        buffer.putInt(crc)
        buffer.put(auth.tag(buffer.array().copyOf(buffer.position())))
        return buffer.array()
    }

    /** No remote length is used for allocation until the complete envelope is bounded. */
    fun decode(bytes: ByteArray): MeshPacket? {
        if (bytes.size !in ProtocolConstants.FRAME_OVERHEAD..ProtocolConstants.MAX_FRAME) return null
        return runCatching {
            val b = ByteBuffer.wrap(bytes)
            if (b.short.toInt() != ProtocolConstants.MAGIC || b.get().toInt() != ProtocolConstants.VERSION) return null
            val type = PacketType.fromWire(b.get().toInt() and 255) ?: return null
            val id = b.long; val sender = b.long; val destination = b.long
            val room = UUID(b.long, b.long); val session = b.long; val sequence = b.long
            val ttl = b.get().toInt() and 255; val length = b.short.toInt() and 65535
            if (length > ProtocolConstants.MAX_PAYLOAD || bytes.size != ProtocolConstants.FRAME_OVERHEAD + length ||
                ttl > ProtocolConstants.MAX_HOPS || id <= 0 || sender <= 0 || destination < 0 || session <= 0 || sequence <= 0) return null
            val crcEnd = ProtocolConstants.HEADER_SIZE + length
            val crc = CRC32().apply { update(bytes, 0, crcEnd) }.value.toInt()
            if (ByteBuffer.wrap(bytes, crcEnd, 4).int != crc) return null
            if (!auth.verify(bytes.copyOf(crcEnd + 4), bytes.copyOfRange(crcEnd + 4, bytes.size))) return null
            MeshPacket(type, id, sender, destination, room, session, sequence, ttl,
                bytes.copyOfRange(ProtocolConstants.HEADER_SIZE, crcEnd))
        }.getOrNull()
    }
}

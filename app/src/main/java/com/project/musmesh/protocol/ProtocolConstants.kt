package com.project.musmesh.protocol

object ProtocolConstants {
    const val MAGIC = 0x4D4D
    const val VERSION = 1
    const val HEADER_SIZE = 63
    const val CRC_SIZE = 4
    const val AUTH_SIZE = 16
    const val FRAME_OVERHEAD = HEADER_SIZE + CRC_SIZE + AUTH_SIZE
    const val MAX_PAYLOAD = 6144
    const val MAX_FRAME = MAX_PAYLOAD + FRAME_OVERHEAD
    const val BLOCK_SIZE = 32 * 1024
    const val MAX_FILE_SIZE = 5 * 1024 * 1024
    const val MAX_NODES = 8
    const val MAX_HOPS = 4
    const val MAX_FRAGMENTS = 2048
    const val TRANSFER_HEADER = 56
    const val BROADCAST = 0L
}

enum class PacketType(val wire: Int, val priority: Int = 1) {
    HELLO(1), HELLO_ACK(2), ROOM_JOIN(3), ROOM_ACCEPT(4, 0), ROOM_REJECT(5),
    PEER_INFO(6), TOPOLOGY_UPDATE(7, 0), PARENT_ASSIGN(8, 0), PING(9), PONG(10),
    MUSIC_OFFER(11, 2), MUSIC_ACCEPT(12), MUSIC_REJECT(13), BLOCK_DATA(14, 2),
    BLOCK_ACK(15), BLOCK_NACK(16), RESUME_REQUEST(17), TRANSFER_CANCEL(18, 0),
    TRANSFER_COMPLETE(19), FILE_VERIFIED(20), FILE_INVALID(21),
    CLOCK_SYNC_REQUEST(22), CLOCK_SYNC_RESPONSE(23), PLAYER_READY(24),
    PLAY_AT(25, 0), PAUSE_AT(26, 0), SEEK_AT(27, 0), STOP(28, 0), ERROR(29),
    COMMAND_ACK(30), PLAYBACK_STATUS(31), TRANSFER_PROGRESS(32);

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wire == value }
    }
}

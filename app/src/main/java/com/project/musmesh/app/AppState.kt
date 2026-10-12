package com.project.musmesh.app

import com.project.musmesh.mesh.RoomSnapshot
import com.project.musmesh.protocol.ControlPacket
import com.project.musmesh.transfer.*

enum class Role { SERVER, NODE }
data class PeerPlayback(val ready: Boolean = false, val offsetMs: Double = 0.0, val rttMs: Double = 0.0,
    val lastSeenMs: Long = 0, val commandAck: Long = 0, val dispatchSkewMs: Double? = null, val positionMs: Long = 0)

data class AppState(
    val role: Role? = null,
    val code: String = "",
    val room: RoomSnapshot = RoomSnapshot(),
    val nodeId: Long = 0,
    val manifest: MusicManifest? = null,
    val transferState: TransferState = TransferState.IDLE,
    val completedBlocks: Int = 0,
    val progress: Map<Long, TransferProgress> = emptyMap(),
    val playback: Map<Long, PeerPlayback> = emptyMap(),
    val localReady: Boolean = false,
    val clockReady: Boolean = false,
    val offsetMs: Double = 0.0,
    val rttMs: Double = 0.0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val command: ControlPacket? = null,
    val outputOffsetMs: Int = 0,
    val busy: Boolean = false,
    val distributing: Boolean = false,
    val status: String = "Música local, juntos",
    val error: String? = null
) {
    val canPlay: Boolean get() = role == Role.SERVER && localReady && !busy && room.peers.values.filter { it.id != nodeId && it.required }
        .all { room.tree.path(it.id).isNotEmpty() && playback[it.id]?.ready == true }
}

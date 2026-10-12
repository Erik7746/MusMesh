package com.project.musmesh.sync

import com.project.musmesh.audio.MusicPlayer
import com.project.musmesh.protocol.ControlPacket
import com.project.musmesh.protocol.PacketType
import kotlinx.coroutines.*

/** Main-dispatcher scheduler with elapsedRealtimeNanos deadlines, never wall-clock deadlines. */
class PlaybackScheduler(private val scope: CoroutineScope, private val player: MusicPlayer, private val now: () -> Long,
    private val executed: (ControlPacket, Long) -> Unit) {
    private var job: Job? = null
    var lastSequence = 0L; private set
    var scheduled: ControlPacket? = null; private set
    fun schedule(type: PacketType, command: ControlPacket, offsetNanos: Long, outputOffsetMs: Int): Boolean {
        if (command.sequence <= lastSequence || !player.ready) return false
        val local = ClockSynchronizer.toLocal(command.atNanos, offsetNanos, outputOffsetMs)
        if (local < now() - 100_000_000L || local > now() + 30_000_000_000L) return false
        lastSequence = command.sequence; job?.cancel(); scheduled = command
        if (type == PacketType.PLAY_AT && !player.playing) { player.pause(); player.seek(command.positionMs) }
        job = scope.launch {
            while (isActive) {
                val remaining = local - now()
                if (remaining <= 0) break
                delay(if (remaining > 10_000_000L) (remaining / 1_000_000L - 5).coerceAtMost(100) else 1)
            }
            when (type) {
                PacketType.STOP -> player.stop()
                PacketType.PAUSE_AT -> { player.pause(); player.seek(command.positionMs) }
                PacketType.SEEK_AT -> { player.seek(command.positionMs); if (command.playing) player.play() else player.pause() }
                PacketType.PLAY_AT -> player.play()
                else -> return@launch
            }
            scheduled = null; executed(command, now() - local)
        }
        return true
    }
    fun cancel(resetSequence: Boolean = false) { job?.cancel(); job = null; scheduled = null; if (resetSequence) lastSequence = 0 }
}

package com.project.musmesh.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.project.musmesh.audio.*
import com.project.musmesh.data.*
import com.project.musmesh.mesh.*
import com.project.musmesh.protocol.*
import com.project.musmesh.sync.*
import com.project.musmesh.transfer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

class AppCoordinator(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = AppDatabase(context)
    private val _state = MutableStateFlow(AppState(nodeId = database.nodeId, outputOffsetMs = database.outputOffsetMs))
    val state = _state.asStateFlow()
    private var router: MeshRouter? = null
    private var transfer: TransferManager? = null
    private var player: MusicPlayer? = null
    private var scheduler: PlaybackScheduler? = null
    private var sessionJob: Job? = null
    private var prepareJob: Job? = null
    private var importJob: Job? = null
    private var preparedId: UUID? = null
    private var preparingId: UUID? = null
    private var activeRoom: SavedRoom? = null
    private var clockUpdated = 0L
    private var offsetNanos = 0L
    private val clocks = mutableMapOf<Long, ClockSynchronizer>()
    private val pendingClock = mutableMapOf<Long, Long>()
    private var commandSequence = 0L
    private var authority: ControlPacket? = null
    private var authorityType = PacketType.STOP
    private val pendingCommands = mutableMapOf<Long, Pair<PacketType, ControlPacket>>()
    private val commandAttempts = mutableMapOf<Long, Int>()
    private val lastRepair = mutableMapOf<Long, Long>()
    private val progressSamples = mutableMapOf<Long, Pair<Int, Long>>()
    private val drift = DriftCorrector()
    private var correctionJob: Job? = null
    private var roomGeneration = 0
    private val monotonic: () -> Long = SystemClock::elapsedRealtimeNanos
    private fun nowMs() = SystemClock.elapsedRealtime()

    fun reportError(message: String) { _state.update { it.copy(error = message) } }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun hasSavedRoom() = database.room() != null
    fun start(server: Boolean, code: String? = null, restore: Boolean = false) {
        if (restore && router != null) return
        val saved = if (restore) database.room() else null
        val room = saved ?: if (server) SavedRoom(true, Ids.code(), UUID.randomUUID(), database.nodeId)
        else SavedRoom(false, code?.trim().orEmpty(), Ids.EMPTY_ROOM, 0)
        if (!room.code.matches(Regex("[0-9]{6}"))) { reportError("Introduce un código de seis cifras"); return }
        stop(false); val generation = ++roomGeneration
        activeRoom = room
        val role = if (room.server) Role.SERVER else Role.NODE
        _state.value = AppState(role, room.code, nodeId = database.nodeId, outputOffsetMs = database.outputOffsetMs,
            transferState = TransferState.DISCOVERING, status = if (room.server) "Sala abierta · esperando nodos" else "Buscando la sala por BLE")
        player = LocalMusicPlayer(context, ::reportError)
        scheduler = PlaybackScheduler(scope, requireNotNull(player), monotonic) { command, skew ->
            val state = if (command.playing) TransferState.PLAYING else TransferState.READY
            _state.update { it.copy(playing = command.playing, transferState = state, status = if (command.playing) "Reproducción sincronizada" else "Reproductor en pausa") }
            scope.launch { sendCommandAck(command, true, skew) }
        }
        router = MeshRouter(context, scope, database.nodeId, Ids.node(), room.server, room.code, room.room, room.root,
            ::handlePacket, { id, root ->
                activeRoom = requireNotNull(activeRoom).copy(room = id, root = root)
                scope.launch(Dispatchers.IO) { database.saveRoom(requireNotNull(activeRoom)) }
            }, ::reportError, if (restore) database.peers() else emptyList())
        transfer = TransferManager(scope, database.nodeId, database.transfers, ::nowMs,
            { type, destination, payload -> router?.send(type, destination, payload) ?: false },
            { router?.fragmentSize(it) }, ::stored,
            { progress -> _state.update { it.copy(progress = it.progress + (progress.id to progress)) } }, ::prepare, ::reportError)
        sessionJob = scope.launch {
            withContext(Dispatchers.IO) { database.saveRoom(room) }
            router?.start()
            launch {
                router?.state?.collect { snapshot ->
                    if (generation != roomGeneration) return@collect
                    val old = _state.value.room
                    for ((id, peer) in snapshot.peers) if (old.peers[id]?.session != peer.session) {
                        clocks.remove(id); pendingClock.remove(id)
                        _state.update { it.copy(playback = it.playback - id) }
                        if (id == snapshot.root && role == Role.NODE) { scheduler?.cancel(true); player?.pause(); authority = null; clockUpdated = 0; _state.update { it.copy(clockReady = false, playing = false) } }
                    }
                    _state.update { it.copy(room = snapshot, status = if (it.manifest == null) {
                        if (snapshot.admitted) "Sala conectada · ${snapshot.peers.size}/8 dispositivos" else "Buscando y solicitando acceso a la sala"
                    } else it.status) }
                    transfer?.setRoom(snapshot)
                    database.savePeers(snapshot.peers.values, snapshot.tree.parents)
                }
            }
            if (restore) {
                val id = database.transferId()
                if (id != null) {
                    val savedTransfer = withContext(Dispatchers.IO) { database.transfers.load(id) }
                    if (savedTransfer != null && savedTransfer.state != TransferState.CANCELED) transfer?.use(savedTransfer, true)
                }
            }
            while (isActive) { tick(); delay(1000) }
        }
    }
    private fun stored(stored: StoredTransfer) {
        if (_state.value.manifest?.id != stored.manifest.id) {
            prepareJob?.cancel(); preparingId = null; preparedId = null; scheduler?.cancel(true); player?.stop()
            _state.update { it.copy(localReady = false, playing = false, command = null, playback = emptyMap(), progress = emptyMap()) }
        }
        database.setTransfer(stored.manifest.id)
        _state.update { old -> old.copy(manifest = stored.manifest, completedBlocks = stored.completed.cardinality(),
            transferState = if (old.transferState in setOf(TransferState.SCHEDULED, TransferState.PLAYING) && stored.verified) old.transferState else stored.state,
            status = when (stored.state) {
                TransferState.READY -> "Archivo verificado · preparando audio"
                TransferState.CANCELED -> "Transferencia cancelada"
                TransferState.FAILED -> "Archivo corrupto"
                else -> "${stored.completed.cardinality()}/${stored.manifest.blockCount} bloques persistidos"
            }) }
    }
    fun selectMusic(uri: Uri) {
        if (_state.value.role != Role.SERVER || importJob?.isActive == true) return
        importJob = scope.launch {
            _state.update { it.copy(busy = true, error = null, status = "Comprobando y copiando archivo local") }
            var imported: ImportedTrack? = null
            try {
                imported = LocalMusicPicker(context).import(uri)
                val saved = withContext(Dispatchers.IO) { database.transfers.importFile(imported.file, imported.manifest) }
                val oldId = transfer?.current?.manifest?.id
                if (oldId != null) router?.send(PacketType.TRANSFER_CANCEL, 0, WireWriter().uuid(oldId).build())
                scheduler?.cancel(true); player?.stop(); preparedId = null; authority = null; pendingCommands.clear()
                _state.update { it.copy(playback = emptyMap(), progress = emptyMap(), localReady = false, distributing = false, playing = false, command = null) }
                transfer?.use(saved, false)
                withContext(Dispatchers.IO) { database.transfers.cleanup(saved.manifest.id) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(userFileError(e)) }
            finally { withContext(NonCancellable + Dispatchers.IO) { imported?.file?.delete() }; _state.update { it.copy(busy = false) } }
        }
    }
    private fun userFileError(error: Exception): String = when (error) {
        is IllegalArgumentException -> error.message?.takeIf { it.startsWith("Archivo demasiado") || it.startsWith("Espacio insuficiente") || it.startsWith("Formato no compatible") || it.startsWith("No se admiten") || it.startsWith("Selecciona") } ?: "Archivo no compatible o metadatos inválidos"
        is SecurityException -> "No hay permiso para leer este archivo local"
        else -> "No se pudo abrir o copiar el archivo local"
    }
    private fun prepare(stored: StoredTransfer) {
        if (!stored.verified || stored.state == TransferState.CANCELED) return
        if (preparedId == stored.manifest.id && player?.ready == true) { scope.launch { sendReady() }; return }
        if (prepareJob?.isActive == true && preparingId == stored.manifest.id) return
        prepareJob?.cancel(); preparingId = stored.manifest.id
        val generation = roomGeneration
        prepareJob = scope.launch {
            try {
                // Recheck on disk after process restart and immediately before giving a path to the decoder.
                check(withContext(Dispatchers.IO) { FileVerifier.verify(database.transfers.file(stored.manifest.id), stored.manifest) })
                if (generation != roomGeneration || transfer?.current?.manifest?.id != stored.manifest.id || transfer?.current?.verified != true) return@launch
                player?.prepare(database.transfers.file(stored.manifest.id))
                preparedId = stored.manifest.id
                _state.update { it.copy(localReady = true, transferState = TransferState.READY, status = "Canción preparada · esperando sincronización") }
                sendReady()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(localReady = false, transferState = TransferState.FAILED) }; reportError("Archivo corrupto o decoder no compatible") }
        }
    }
    fun distribute() {
        if (_state.value.role != Role.SERVER || transfer?.current == null) return
        transfer?.distribute(); _state.update { it.copy(distributing = true, status = "Distribuyendo bloques por el árbol") }
    }
    fun cancelTransfer() {
        prepareJob?.cancel(); preparingId = null
        scheduler?.cancel(); player?.stop(); preparedId = null; transfer?.cancel(); router?.cancelData()
        _state.update { it.copy(distributing = false, localReady = false, playing = false, command = null, transferState = TransferState.CANCELED) }
    }
    fun setRequired(id: Long, required: Boolean) { router?.setRequired(id, required) }
    fun setOutputOffset(value: Int) { database.outputOffsetMs = value; _state.update { it.copy(outputOffsetMs = database.outputOffsetMs) } }

    private suspend fun tick() {
        val state = _state.value; val mesh = router ?: return
        mesh.setTransferring(com.project.musmesh.service.ForegroundPolicy.needsCpu(state))
        _state.update { it.copy(positionMs = player?.positionMs ?: 0, playing = player?.playing == true) }
        transfer?.reportProgress()
        if (state.role == Role.SERVER) {
            val now = monotonic()
            for (peer in state.room.peers.values.filter { it.id != database.nodeId }) {
                if (state.room.tree.path(peer.id).isEmpty()) continue
                val clock = clocks[peer.id]
                if (clock?.ready != true || now - clock.updatedAt > 10_000_000_000L) {
                    pendingClock[peer.id] = now
                    mesh.send(PacketType.CLOCK_SYNC_REQUEST, peer.id, WireWriter().long(now).build())
                }
                authority?.let { command ->
                    if (scheduler?.scheduled == null) mesh.send(PacketType.PLAYBACK_STATUS, peer.id,
                        WireWriter().uuid(command.transferId).long(command.sequence).long(monotonic()).long(player?.positionMs ?: 0).byte(if (player?.playing == true) 1 else 0).build())
                }
            }
            _state.update { s -> s.copy(playback = s.playback.mapValues { (_, p) -> if (nowMs() - p.lastSeenMs > 15000) p.copy(ready = false) else p }) }
            retryCommands()
        } else {
            if (nowMs() - clockUpdated > 30000 && _state.value.clockReady) _state.update { it.copy(clockReady = false) }
            sendReady()
            val activeManifest = _state.value.manifest
            if (_state.value.localReady && activeManifest != null && (scheduler?.lastSequence ?: 0) > 0) {
                mesh.send(PacketType.PLAYBACK_STATUS, state.room.root, ControlPacket(activeManifest.id,
                    scheduler?.lastSequence ?: 1, monotonic(), player?.positionMs ?: 0, player?.playing == true).encode())
            }
        }
    }
    private fun handlePacket(packet: MeshPacket, receivedAt: Long) {
        transfer?.handle(packet)
        val root = _state.value.room.root
        when (packet.type) {
            PacketType.CLOCK_SYNC_REQUEST -> if (packet.sender == root && _state.value.role == Role.NODE) {
                val t1 = decodePayload(packet.payload) { long() } ?: return
                scope.launch { router?.send(PacketType.CLOCK_SYNC_RESPONSE, root, WireWriter().byte(0).long(t1).long(receivedAt).long(monotonic()).build()) }
            }
            PacketType.CLOCK_SYNC_RESPONSE -> handleClock(packet, receivedAt)
            PacketType.TRANSFER_PROGRESS -> if (_state.value.role == Role.SERVER) {
                val progress = decodePayload(packet.payload) { val id = uuid(); val done = short(); val total = short(); require(total in 1..160 && done <= total); Triple(id, done, total) } ?: return
                if (progress.first != _state.value.manifest?.id) return
                val sample = progressSamples[packet.sender]
                val speed = if (sample != null && progress.second > sample.first) (progress.second - sample.first) * 32768L * 1000 / (nowMs() - sample.second).coerceAtLeast(1) else null
                if (sample == null || progress.second != sample.first) progressSamples[packet.sender] = progress.second to nowMs()
                _state.update { old -> val previous = old.progress[packet.sender] ?: TransferProgress(packet.sender)
                    old.copy(progress = old.progress + (packet.sender to previous.copy(blocks = progress.second, total = progress.third,
                        bytesPerSecond = speed ?: previous.bytesPerSecond,
                        current = progress.second, status = if (progress.second == progress.third) "Verificando / preparado" else "Recibiendo"))) }
            }
            PacketType.PLAYER_READY -> if (_state.value.role == Role.SERVER) {
                val payload = decodePayload(packet.payload) { val id = uuid(); val hash = raw(32); val ready = byte(); require(ready in 0..1); Triple(id, hash, ready == 1) } ?: return
                val manifest = _state.value.manifest ?: return
                if (payload.first != manifest.id || !payload.second.contentEquals(manifest.sha256)) return
                val old = _state.value.playback[packet.sender] ?: PeerPlayback()
                val clock = clocks[packet.sender]
                val ready = payload.third && clock?.ready == true && monotonic() - clock.updatedAt < 30_000_000_000
                _state.update { it.copy(playback = it.playback + (packet.sender to old.copy(ready = ready, lastSeenMs = nowMs()))) }
                // A recovered node receives the current authoritative state at a new future boundary.
                if (ready && !old.ready && authority != null && pendingCommands[packet.sender] == null) rejoin(packet.sender)
            }
            PacketType.PLAY_AT, PacketType.PAUSE_AT, PacketType.SEEK_AT, PacketType.STOP -> if (packet.sender == root && _state.value.role == Role.NODE) receiveCommand(packet)
            PacketType.COMMAND_ACK -> if (_state.value.role == Role.SERVER) {
                val ack = decodePayload(packet.payload) { val id = uuid(); val sequence = long(); val executed = byte(); val skew = long(); require(executed in 0..1); Ack(id, sequence, executed == 1, skew) } ?: return
                if (ack.id != _state.value.manifest?.id) return
                pendingCommands[packet.sender]?.takeIf { it.second.sequence == ack.sequence }?.let { pendingCommands.remove(packet.sender); commandAttempts.remove(packet.sender) }
                val old = _state.value.playback[packet.sender] ?: PeerPlayback()
                _state.update { it.copy(playback = it.playback + (packet.sender to old.copy(commandAck = maxOf(old.commandAck, ack.sequence), dispatchSkewMs = if (ack.executed) ack.skew / 1e6 else old.dispatchSkewMs))) }
            }
            PacketType.PLAYBACK_STATUS -> if (packet.sender == root && _state.value.role == Role.NODE) handleAuthority(packet)
                else if (_state.value.role == Role.SERVER) {
                    val report = ControlPacket.decode(packet.payload) ?: return
                    if (report.transferId != preparedId) return
                    val old = _state.value.playback[packet.sender] ?: PeerPlayback()
                    _state.update { it.copy(playback = it.playback + (packet.sender to old.copy(positionMs = report.positionMs))) }
                    val authoritative = authority
                    if (authoritative != null && scheduler?.scheduled == null &&
                        (report.sequence < authoritative.sequence || report.playing != (player?.playing == true)) &&
                        pendingCommands[packet.sender] == null && nowMs() - (lastRepair[packet.sender] ?: -20000) > 10000) rejoin(packet.sender)
                }
            PacketType.TRANSFER_CANCEL -> if (packet.sender == root) {
                val id = decodePayload(packet.payload) { uuid() } ?: return
                if (id == _state.value.manifest?.id) { prepareJob?.cancel(); preparingId = null; scheduler?.cancel(); player?.stop(); preparedId = null; _state.update { it.copy(localReady = false, playing = false, transferState = TransferState.CANCELED) } }
            }
            PacketType.FILE_INVALID -> reportError("Un nodo detectó un archivo corrupto y debe reanudar la transferencia")
            PacketType.PING -> scope.launch { router?.send(PacketType.PONG, packet.sender, packet.payload) }
            else -> Unit
        }
    }
    private data class Ack(val id: UUID, val sequence: Long, val executed: Boolean, val skew: Long)
    private fun handleClock(packet: MeshPacket, receivedAt: Long) {
        if (packet.payload.isEmpty()) return
        if (_state.value.role == Role.SERVER) {
            val sample = decodePayload(packet.payload) { require(byte() == 0); ClockSample(long(), long(), long(), receivedAt) } ?: return
            if (pendingClock[packet.sender] != sample.t1) return
            pendingClock.remove(packet.sender)
            val clock = clocks.getOrPut(packet.sender) { ClockSynchronizer() }
            if (clock.add(sample)) {
                val old = _state.value.playback[packet.sender] ?: PeerPlayback()
                _state.update { it.copy(playback = it.playback + (packet.sender to old.copy(offsetMs = clock.offset / 1e6, rttMs = clock.bestRtt / 1e6))) }
                scope.launch { router?.send(PacketType.CLOCK_SYNC_RESPONSE, packet.sender, WireWriter().byte(1).long(clock.offset).long(clock.bestRtt).byte(if (clock.ready) 1 else 0).build()) }
            }
        } else if (packet.sender == _state.value.room.root) {
            val estimate = decodePayload(packet.payload) { require(byte() == 1); val offset = long(); val rtt = long(); val ready = byte(); require(rtt in 0..1_500_000_000 && ready in 0..1); Triple(offset, rtt, ready == 1) } ?: return
            offsetNanos = estimate.first; clockUpdated = nowMs()
            _state.update { it.copy(offsetMs = estimate.first / 1e6, rttMs = estimate.second / 1e6, clockReady = estimate.third) }
        }
    }
    private suspend fun sendReady() {
        val state = _state.value; val manifest = state.manifest ?: return
        if (state.role == Role.NODE && state.room.admitted) router?.send(PacketType.PLAYER_READY, state.room.root,
            WireWriter().uuid(manifest.id).raw(manifest.sha256).byte(if (state.localReady && state.clockReady) 1 else 0).build())
    }
    fun command(type: PacketType, seekMs: Long? = null) {
        val state = _state.value; val manifest = state.manifest ?: return
        if (state.role != Role.SERVER || !state.localReady || transfer?.current?.verified != true || (type == PacketType.PLAY_AT && !state.canPlay)) return
        if (!TransferSession(state.transferState).transition(TransferState.SCHEDULED)) return
        val lead = 4_000_000_000L
        val playing = when (type) { PacketType.PLAY_AT -> true; PacketType.PAUSE_AT, PacketType.STOP -> false; else -> player?.playing == true }
        val position = when (type) {
            PacketType.STOP -> 0L
            PacketType.SEEK_AT -> seekMs ?: 0L
            PacketType.PAUSE_AT -> (player?.positionMs ?: 0) + if (player?.playing == true) 4000 else 0
            else -> player?.positionMs ?: 0
        }.coerceIn(0, manifest.durationMs)
        val command = ControlPacket(manifest.id, ++commandSequence, monotonic() + lead, position, playing)
        authority = command; authorityType = type
        scheduler?.schedule(type, command, 0, database.outputOffsetMs)
        _state.update { it.copy(command = command, transferState = TransferState.SCHEDULED, status = "Orden programada para dentro de 4 segundos") }
        for (peer in state.room.peers.values.filter { it.id != database.nodeId && state.playback[it.id]?.ready == true }) queueCommand(peer.id, type, command)
    }
    private fun queueCommand(peer: Long, type: PacketType, command: ControlPacket) {
        pendingCommands[peer] = type to command; commandAttempts[peer] = 0
        scope.launch { router?.send(type, peer, command.encode()) }
    }
    private suspend fun retryCommands() {
        for ((peer, entry) in pendingCommands.toMap()) {
            val attempts = commandAttempts[peer] ?: 0
            if (attempts >= 4 || entry.second.atNanos < monotonic()) {
                pendingCommands.remove(peer); commandAttempts.remove(peer)
                reportError("Un nodo no confirmó la orden de reproducción; revisa su conexión")
            } else { commandAttempts[peer] = attempts + 1; router?.send(entry.first, peer, entry.second.encode()) }
        }
    }
    private fun receiveCommand(packet: MeshPacket) {
        val command = ControlPacket.decode(packet.payload) ?: return
        if (command.transferId != preparedId || !_state.value.localReady || !_state.value.clockReady) return
        if (command.sequence == scheduler?.lastSequence) { scope.launch { sendCommandAck(command, false, 0) }; return }
        if (scheduler?.schedule(packet.type, command, offsetNanos, database.outputOffsetMs) == true) {
            authority = command; authorityType = packet.type
            _state.update { it.copy(command = command, transferState = TransferState.SCHEDULED, status = "Reproducción programada") }
            scope.launch { sendCommandAck(command, false, 0) }
        }
    }
    private suspend fun sendCommandAck(command: ControlPacket, executed: Boolean, skew: Long) {
        if (_state.value.role == Role.NODE) router?.send(PacketType.COMMAND_ACK, _state.value.room.root,
            WireWriter().uuid(command.transferId).long(command.sequence).byte(if (executed) 1 else 0).long(skew).build())
    }
    private fun rejoin(peer: Long) {
        val current = authority ?: return
        if (scheduler?.scheduled != null) return
        lastRepair[peer] = nowMs()
        val playing = player?.playing == true
        val command = current.copy(sequence = ++commandSequence, atNanos = monotonic() + 4_000_000_000,
            positionMs = ((player?.positionMs ?: 0) + if (playing) 4000 else 0).coerceAtMost(_state.value.manifest?.durationMs ?: Long.MAX_VALUE), playing = playing)
        queueCommand(peer, if (playing) PacketType.PLAY_AT else PacketType.SEEK_AT, command)
    }
    private fun handleAuthority(packet: MeshPacket) {
        val report = ControlPacket.decode(packet.payload) ?: return
        if (report.transferId != preparedId || !_state.value.clockReady || !_state.value.localReady || scheduler?.scheduled != null) return
        // Drift correction follows an accepted scheduled command; it never starts an unprepared node.
        if (authority == null || !report.playing || player?.playing != true) return
        val elapsedMs = ((monotonic() - offsetNanos - report.atNanos) / 1_000_000).coerceIn(0, 10000)
        val expected = (report.positionMs + elapsedMs).coerceAtMost(_state.value.manifest?.durationMs ?: Long.MAX_VALUE)
        val correction = drift.correct(player?.positionMs ?: 0, expected, nowMs())
        correction.seekMs?.let { player?.seek(it) }
        if (correction.speed != 1f) {
            player?.speed(correction.speed); correctionJob?.cancel(); correctionJob = scope.launch { delay(4000); player?.speed(1f) }
        }
    }
    fun stop(clearRoom: Boolean = true) {
        roomGeneration++; sessionJob?.cancel(); prepareJob?.cancel(); importJob?.cancel(); correctionJob?.cancel()
        transfer?.close(); transfer = null; router?.stop(); router = null; scheduler?.cancel(); scheduler = null; player?.release(); player = null
        preparedId = null; preparingId = null; authority = null; pendingCommands.clear(); commandAttempts.clear(); lastRepair.clear(); progressSamples.clear(); clocks.clear(); pendingClock.clear(); clockUpdated = 0; offsetNanos = 0
        if (clearRoom) database.clearRoom()
        _state.value = AppState(nodeId = database.nodeId, outputOffsetMs = database.outputOffsetMs)
    }
}

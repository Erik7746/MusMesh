package com.project.musmesh.transfer

import com.project.musmesh.mesh.RoomSnapshot
import com.project.musmesh.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.BitSet
import java.util.UUID

data class TransferProgress(val id: Long, val blocks: Int = 0, val total: Int = 0, val current: Int = 0,
    val bytesPerSecond: Long = 0, val retries: Int = 0, val status: String = "En espera",
    val fragmentsSent: Long = 0, val fragmentsConfirmed: Long = 0)

class TransferManager(private val scope: CoroutineScope, private val nodeId: Long, private val repository: TransferRepository,
    private val nowMs: () -> Long, private val send: suspend (PacketType, Long, ByteArray) -> Boolean,
    private val chunkSize: (Long) -> Int?, private val onStored: (StoredTransfer) -> Unit,
    private val onProgress: (TransferProgress) -> Unit, private val onVerified: (StoredTransfer) -> Unit,
    private val onError: (String) -> Unit, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private var room = RoomSnapshot()
    var current: StoredTransfer? = null; private set
    private val receiver = BlockReceiver(repository)
    private val incoming = Channel<Pair<Long, BlockFragment>>(64)
    private val outgoing = mutableMapOf<Long, Job>()
    private val senders = mutableMapOf<Long, BlockSender>()
    private val resumes = mutableMapOf<Long, ResumeRequest>()
    private val retryAfter = mutableMapOf<Long, Long>()
    private var enabled = false
    private var generation = 0
    private var verifying = false
    private var offerJob: Job? = null
    private var acceptJob: Job? = null
    private val receiveJob = scope.launch {
        for ((sender, fragment) in incoming) {
            val transfer = current ?: continue
            if (!enabled || fragment.transfer != transfer.manifest.id || sender != room.tree.parents[nodeId]) continue
            try {
                val stamp = generation
                val ack = withContext(ioDispatcher) { receiver.receive(fragment) } ?: continue
                if (stamp != generation) continue
                // A persisted ACK is emitted only after writeBlock has fsynced data AND its bitmap.
                send(if (!ack.persisted && ack.fragments.isEmpty) PacketType.BLOCK_NACK else PacketType.BLOCK_ACK, sender, ack.encode())
                if (ack.persisted) {
                    val stored = withContext(ioDispatcher) { repository.load(fragment.transfer) } ?: continue
                    if (stamp != generation) continue
                    current = stored; onStored(stored)
                    reportProgress()
                    if (stored.completed.cardinality() == stored.manifest.blockCount) verify()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pause("No se pudo guardar un bloque; revisa el espacio disponible") }
        }
    }
    fun setRoom(snapshot: RoomSnapshot) {
        val previousParent = room.tree.parents[nodeId]
        room = snapshot
        val children = snapshot.tree.children(nodeId).toSet()
        outgoing.keys.toList().filter { it !in children || chunkSize(it) == null }.forEach { outgoing.remove(it)?.cancel(); senders.remove(it); resumes.remove(it) }
        if (previousParent != snapshot.tree.parents[nodeId]) {
            scope.launch {
                withContext(ioDispatcher) { receiver.discardPartial() }
                sendResume()
            }
        }
        if (enabled) pump()
    }
    fun use(stored: StoredTransfer, distribute: Boolean) {
        resetJobs(); generation++; current = stored; enabled = distribute; onStored(stored)
        if (stored.verified) onVerified(stored)
        startOffers(); pump()
    }
    fun distribute() { enabled = true; retryAfter.clear(); startOffers(); pump(); scope.launch { sendResume() } }
    private fun startOffers() {
        if (offerJob?.isActive == true) return
        offerJob = scope.launch {
            while (isActive) {
                val transfer = current
                if (enabled && transfer != null) {
                    for (child in room.tree.children(nodeId)) if (chunkSize(child) != null && child !in resumes) send(PacketType.MUSIC_OFFER, child, transfer.manifest.encode())
                    pump()
                }
                delay(4000)
            }
        }
    }
    fun handle(packet: MeshPacket) {
        when (packet.type) {
            PacketType.MUSIC_OFFER -> {
                if (packet.sender != room.tree.parents[nodeId]) return
                val manifest = MusicManifest.decode(packet.payload) ?: return
                val existing = current
                if (existing != null && existing.manifest.id == manifest.id) {
                    if (!existing.manifest.encode().contentEquals(manifest.encode())) return
                    if (existing.state == TransferState.CANCELED) return
                    enabled = true; scope.launch { sendResume(); if (existing.verified) onVerified(existing) }; pump(); return
                }
                if (acceptJob?.isActive == true) return
                val stamp = generation
                acceptJob = scope.launch {
                    try {
                        val stored = withContext(ioDispatcher) { repository.accept(manifest) }
                        if (stamp != generation) return@launch
                        use(stored, true); sendResume()
                        if (stored.completed.cardinality() == manifest.blockCount) verify()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { send(PacketType.MUSIC_REJECT, packet.sender, WireWriter().uuid(manifest.id).build()); onError("No se puede aceptar el archivo: espacio insuficiente u oferta inválida") }
                }
            }
            PacketType.MUSIC_ACCEPT, PacketType.RESUME_REQUEST -> {
                if (packet.sender !in room.tree.children(nodeId)) return
                val resume = ResumeRequest.decode(packet.payload) ?: return
                val manifest = current?.manifest ?: return
                if (resume.transfer != manifest.id || !resume.hash.contentEquals(manifest.sha256) || resume.blocks.length() > manifest.blockCount) return
                // Keep the original resume bitmap during an active send; duplicates cannot roll progress backward.
                val old = resumes[packet.sender]
                if (old == null || outgoing[packet.sender]?.isActive != true) resumes[packet.sender] = resume
                else old.blocks.or(resume.blocks)
                pump()
            }
            PacketType.BLOCK_DATA -> {
                if (packet.sender != room.tree.parents[nodeId]) return
                BlockFragment.decode(packet.payload)?.let { incoming.trySend(packet.sender to it) }
            }
            PacketType.BLOCK_ACK, PacketType.BLOCK_NACK -> BlockAck.decode(packet.payload)?.let { senders[packet.sender]?.acknowledgements?.trySend(it) }
            PacketType.TRANSFER_CANCEL -> if (packet.sender == room.root) {
                val id = decodePayload(packet.payload) { uuid() } ?: return
                if (id == current?.manifest?.id) cancel(false)
            }
            PacketType.MUSIC_REJECT -> onError("Un nodo rechazó el archivo; revisa su espacio disponible")
            else -> Unit
        }
    }
    private suspend fun sendResume() {
        val stored = current ?: return
        if (!enabled || stored.state == TransferState.CANCELED) return
        val parent = room.tree.parents[nodeId] ?: return
        send(PacketType.RESUME_REQUEST, parent, ResumeRequest(stored.manifest.id, stored.manifest.sha256, stored.completed).encode())
    }
    private fun pump() {
        val stored = current ?: return
        if (!enabled) return
        for (child in room.tree.children(nodeId)) {
            if (outgoing[child]?.isActive == true || child !in resumes || nowMs() < (retryAfter[child] ?: 0)) continue
            val chunk = chunkSize(child) ?: continue
            val resume = resumes.getValue(child)
            if (resume.blocks.cardinality() == stored.manifest.blockCount) continue
            val stamp = generation
            outgoing[child] = scope.launch {
                val sender = BlockSender(nowMs) { type, bytes -> send(type, child, bytes) }; senders[child] = sender
                val started = nowMs(); var sentBytes = 0L; var totalRetries = 0; var fragmentsConfirmed = 0L
                try {
                    for (index in 0 until stored.manifest.blockCount) {
                        if (resume.blocks[index]) continue
                        // Wait for upstream persistence before forwarding this block. No file-size buffer exists.
                        while (isActive && stamp == generation && current?.completed?.get(index) != true) delay(100)
                        if (stamp != generation) return@launch
                        val data = withContext(ioDispatcher) { repository.readBlock(stored.manifest.id, index) }
                        check(sender.sendBlock(stored.manifest, index, data, chunk)) { "Enlace interrumpido" }
                        resume.blocks.set(index); sentBytes += data.size; totalRetries += sender.retries
                        fragmentsConfirmed += sender.confirmedFragments
                        onProgress(TransferProgress(child, resume.blocks.cardinality(), stored.manifest.blockCount, index,
                            sentBytes * 1000 / (nowMs() - started).coerceAtLeast(1), totalRetries, "Recibiendo", sender.sentFragments, fragmentsConfirmed))
                    }
                    send(PacketType.TRANSFER_COMPLETE, child, WireWriter().uuid(stored.manifest.id).build())
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    retryAfter[child] = nowMs() + 8000; resumes.remove(child)
                    onProgress(TransferProgress(child, resume.blocks.cardinality(), stored.manifest.blockCount, status = "Pausada · esperando reanudación"))
                } finally { senders.remove(child) }
            }
        }
    }
    private fun verify() {
        val stored = current ?: return
        if (verifying) return
        verifying = true
        val stamp = generation
        scope.launch {
            try {
                val verified = withContext(ioDispatcher) { repository.verify(stored.manifest.id) }
                if (stamp != generation) return@launch
                current = verified; onStored(verified)
                send(if (verified.verified) PacketType.FILE_VERIFIED else PacketType.FILE_INVALID, room.root,
                    WireWriter().uuid(verified.manifest.id).raw(verified.manifest.sha256).build())
                if (verified.verified) onVerified(verified) else { withContext(ioDispatcher) { receiver.discardPartial() }; onError("Archivo corrupto; se solicitarán de nuevo los bloques"); sendResume() }
            } finally { verifying = false }
        }
    }
    suspend fun reportProgress() {
        val stored = current ?: return
        if (room.root != nodeId && room.root != 0L) send(PacketType.TRANSFER_PROGRESS, room.root,
            WireWriter().uuid(stored.manifest.id).short(stored.completed.cardinality()).short(stored.manifest.blockCount).build())
    }
    private fun pause(message: String) {
        enabled = false; outgoing.values.forEach { it.cancel() }; onError(message)
        current?.let { stored -> scope.launch { val saved = withContext(ioDispatcher) { repository.transition(stored.manifest.id, TransferState.PAUSED) }; current = saved; onStored(saved) } }
    }
    fun cancel(broadcast: Boolean = true) {
        val stored = current ?: return
        enabled = false; generation++; resetJobs()
        scope.launch {
            withContext(ioDispatcher) { receiver.discardPartial() }
            val saved = withContext(ioDispatcher) { repository.transition(stored.manifest.id, TransferState.CANCELED) }
            current = saved; onStored(saved)
            if (broadcast && room.root == nodeId) send(PacketType.TRANSFER_CANCEL, 0, WireWriter().uuid(stored.manifest.id).build())
        }
    }
    private fun resetJobs() { outgoing.values.forEach { it.cancel() }; outgoing.clear(); senders.clear(); resumes.clear(); offerJob?.cancel(); offerJob = null }
    fun close() { enabled = false; generation++; resetJobs(); acceptJob?.cancel(); receiveJob.cancel(); incoming.close() }
}

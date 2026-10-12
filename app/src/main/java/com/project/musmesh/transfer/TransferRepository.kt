package com.project.musmesh.transfer

import com.project.musmesh.protocol.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.UUID
import java.util.zip.CRC32

data class StoredTransfer(val manifest: MusicManifest, val completed: BitSet, val verified: Boolean, val state: TransferState, val generation: Long = 0)

/** Two fsynced snapshot slots: a torn newest snapshot falls back to the previous generation.
 * Data is fsynced BEFORE any durable completion bit. All paths are generated UUIDs, never metadata.
 */
class TransferRepository(private val root: File) {
    init { check(root.exists() || root.mkdirs()) }
    fun file(id: UUID) = File(root, "$id.audio")
    private fun slot(id: UUID, index: Int) = File(root, "$id.$index.state")

    @Synchronized fun load(id: UUID): StoredTransfer? = (0..1).mapNotNull { index ->
        val file = slot(id, index)
        if (!file.exists() || file.length() !in 20..8192) return@mapNotNull null
        runCatching {
            val bytes = file.readBytes()
            val reader = WireReader(bytes)
            val body = reader.blob(8100); val expected = reader.long(); reader.end()
            require(CRC32().apply { update(body) }.value == expected)
            val r = WireReader(body)
            val generation = r.long(); val manifest = MusicManifest.decode(r.blob(6144)) ?: return@runCatching null
            val bits = BitSet.valueOf(r.blob(20)); val verified = r.byte() == 1
            val state = TransferState.entries.getOrNull(r.byte()) ?: return@runCatching null
            r.end(); require(manifest.id == id && bits.length() <= manifest.blockCount && generation >= 0)
            require(!verified || bits.cardinality() == manifest.blockCount)
            if (this.file(id).length() != manifest.size.toLong()) return@runCatching null
            StoredTransfer(manifest, bits, verified, state, generation)
        }.getOrNull()
    }.maxByOrNull { it.generation }

    @Synchronized fun save(transfer: StoredTransfer): StoredTransfer {
        val next = transfer.copy(generation = (load(transfer.manifest.id)?.generation ?: -1) + 1)
        val body = WireWriter().long(next.generation).blob(next.manifest.encode()).blob(next.completed.toByteArray())
            .byte(if (next.verified) 1 else 0).byte(next.state.ordinal).build()
        val bytes = WireWriter().blob(body).long(CRC32().apply { update(body) }.value).build()
        FileOutputStream(slot(next.manifest.id, (next.generation % 2).toInt())).use { it.write(bytes); it.fd.sync() }
        return next
    }

    @Synchronized fun accept(manifest: MusicManifest): StoredTransfer {
        load(manifest.id)?.let {
            require(it.state != TransferState.CANCELED) { "La transferencia fue cancelada" }
            require(it.manifest.encode().contentEquals(manifest.encode())) { "La oferta no coincide con la transferencia guardada" }
            return it
        }
        require(root.usableSpace > manifest.size.toLong() + 1024 * 1024) { "Espacio insuficiente" }
        RandomAccessFile(file(manifest.id), "rw").use { it.setLength(manifest.size.toLong()); it.fd.sync() }
        return save(StoredTransfer(manifest, BitSet(manifest.blockCount), false, TransferState.ACCEPTED))
    }

    @Synchronized fun importFile(source: File, manifest: MusicManifest): StoredTransfer {
        require(FileVerifier.verify(source, manifest))
        require(root.usableSpace > manifest.size.toLong() + 1024 * 1024) { "Espacio insuficiente" }
        source.inputStream().use { input -> FileOutputStream(file(manifest.id)).use { out -> input.copyTo(out, 32768); out.fd.sync() } }
        return save(StoredTransfer(manifest, BitSet().apply { set(0, manifest.blockCount) }, true, TransferState.READY))
    }

    @Synchronized fun writeBlock(id: UUID, index: Int, bytes: ByteArray): StoredTransfer {
        val stored = requireNotNull(load(id))
        val machine = TransferSession(stored.state)
        if (machine.state in setOf(TransferState.PAUSED, TransferState.FAILED)) check(machine.transition(TransferState.RESUMING))
        check(machine.transition(TransferState.TRANSFERRING)) { "La transferencia no admite bloques en este estado" }
        require(bytes.size == stored.manifest.blockSize(index))
        require(FileVerifier.matches(bytes, stored.manifest.blockHashes[index])) { "Bloque corrupto" }
        RandomAccessFile(file(id), "rw").use { it.seek(index.toLong() * ProtocolConstants.BLOCK_SIZE); it.write(bytes); it.fd.sync() }
        stored.completed.set(index)
        return save(stored.copy(state = machine.state))
    }

    @Synchronized fun readBlock(id: UUID, index: Int): ByteArray {
        val stored = requireNotNull(load(id)); require(stored.completed[index])
        val bytes = ByteArray(stored.manifest.blockSize(index))
        RandomAccessFile(file(id), "r").use { it.seek(index.toLong() * ProtocolConstants.BLOCK_SIZE); it.readFully(bytes) }
        check(FileVerifier.matches(bytes, stored.manifest.blockHashes[index])) { "Bloque local corrupto" }
        return bytes
    }

    @Synchronized fun verify(id: UUID): StoredTransfer {
        val stored = requireNotNull(load(id)); require(stored.completed.cardinality() == stored.manifest.blockCount)
        if (stored.verified) return stored
        val machine = TransferSession(stored.state)
        if (machine.state == TransferState.PAUSED) check(machine.transition(TransferState.RESUMING))
        check(machine.transition(TransferState.VERIFYING))
        save(stored.copy(state = machine.state, verified = false))
        val valid = FileVerifier.verify(file(id), stored.manifest)
        check(machine.transition(if (valid) TransferState.READY else TransferState.FAILED))
        return save(stored.copy(verified = valid, state = machine.state,
            completed = if (valid) stored.completed else BitSet()))
    }

    @Synchronized fun transition(id: UUID, state: TransferState): StoredTransfer {
        val stored = requireNotNull(load(id)); val machine = TransferSession(stored.state)
        check(machine.transition(state)) { "Transición de transferencia inválida" }
        return save(stored.copy(state = machine.state, verified = stored.verified && state !in setOf(TransferState.CANCELED, TransferState.FAILED)))
    }

    fun list(): List<StoredTransfer> = root.listFiles().orEmpty().mapNotNull {
        if (!it.name.endsWith(".audio")) null else runCatching { UUID.fromString(it.name.removeSuffix(".audio")) }.getOrNull()
    }.mapNotNull(::load)

    @Synchronized fun remove(id: UUID) {
        // Exactly three generated files, no recursive deletion and no remote-controlled path.
        slot(id, 0).delete(); slot(id, 1).delete(); file(id).delete()
    }
    fun cleanup(keep: UUID?) = list().filter { it.manifest.id != keep && it.state in setOf(TransferState.CANCELED, TransferState.FAILED) }.forEach { remove(it.manifest.id) }
}

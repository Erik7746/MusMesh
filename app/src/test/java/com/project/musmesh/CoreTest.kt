package com.project.musmesh

import com.project.musmesh.ble.*
import com.project.musmesh.mesh.*
import com.project.musmesh.protocol.*
import com.project.musmesh.sync.*
import com.project.musmesh.transfer.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.BitSet
import java.util.UUID

class CoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val codec = PacketCodec(RoomAuthenticator("123456"))
    private fun packet(type: PacketType = PacketType.PING, payload: ByteArray = byteArrayOf(1, 2, 3)) = MeshPacket(type, 1, 1, 2, UUID(1, 2), 1, 1, payload = payload)
    @Test fun everyPacketTypeRoundTrips() {
        PacketType.entries.forEach { type ->
            val decoded = requireNotNull(codec.decode(codec.encode(packet(type))))
            assertEquals(type, decoded.type); assertEquals(1, decoded.sender); assertArrayEquals(byteArrayOf(1, 2, 3), decoded.payload)
        }
    }
    @Test fun allTruncationsAreRejected() {
        val bytes = codec.encode(packet())
        for (size in bytes.indices) assertNull(codec.decode(bytes.copyOf(size)))
    }
    @Test fun corruptedFramesAreRejected() {
        val bytes = codec.encode(packet())
        bytes.indices.forEach { i -> val bad = bytes.clone(); bad[i] = (bad[i].toInt() xor 1).toByte(); assertNull(codec.decode(bad)) }
    }
    @Test fun unknownVersionAndOversizeAreRejected() {
        val bytes = codec.encode(packet()); bytes[2] = 9; assertNull(codec.decode(bytes))
        assertNull(codec.decode(ByteArray(100_000)))
        assertNull(codec.decode(codec.encode(packet()) + 0.toByte()))
    }
    @Test fun wrongRoomCodeFailsAuthentication() {
        assertNull(PacketCodec(RoomAuthenticator("654321")).decode(codec.encode(packet())))
    }
    @Test fun randomRemoteInputNeverThrows() {
        val random = java.util.Random(42)
        repeat(3000) { val bytes = ByteArray(random.nextInt(7000)).also(random::nextBytes); codec.decode(bytes); BlockFragment.decode(bytes); BlockAck.decode(bytes); MusicManifest.decode(bytes); ControlPacket.decode(bytes) }
    }
    @Test fun allMtusFitAndReassemble() {
        val data = codec.encode(packet(payload = ByteArray(6000) { (it % 127).toByte() }))
        for (mtu in listOf(23, 64, 185, 247, 517)) {
            val assembler = LinkAssembler(); var result: ByteArray? = null
            LinkFramer.split(data, mtu, 1).reversed().forEach { assertTrue(it.size <= mtu - 3); assembler.receive(it, 0)?.let { complete -> result = complete } }
            assertArrayEquals(data, result)
            assertTrue(MtuManager.fragmentPayload(mtu) >= 16)
        }
        assertTrue(MtuManager.fragmentPayload(247) < MtuManager.fragmentPayload(517))
    }
    @Test fun incompleteLinkAssembliesExpire() {
        val assembler = LinkAssembler(); val parts = LinkFramer.split(ByteArray(200), 23, 1)
        assembler.receive(parts.first(), 0)
        parts.drop(1).forEach { assertNull(assembler.receive(it, 16000)) }
    }
    @Test fun duplicateGattTailsCannotFillAssemblySlotsAndBlockControl() {
        val assembler = LinkAssembler()
        for (id in 1..8) {
            val chunks = LinkFramer.split(ByteArray(200) { id.toByte() }, 23, id)
            chunks.forEach { assembler.receive(it, 0) }
            assertNull(assembler.receive(chunks.last(), 1))
        }
        val control = codec.encode(packet(PacketType.STOP))
        var result: ByteArray? = null
        LinkFramer.split(control, 23, 100).forEach { assembler.receive(it, 2)?.let { frame -> result = frame } }
        assertArrayEquals(control, result)
    }
    private fun transfer(size: Int = 70000): Pair<TransferRepository, MusicManifest> {
        val source = temp.newFile().apply { writeBytes(ByteArray(size) { (it % 239).toByte() }) }
        val manifest = MusicManifest.create(source, "Señal sintética", "Prueba", "audio/mpeg", 10000)
        val repo = TransferRepository(temp.newFolder()); repo.accept(manifest)
        return repo to manifest
    }
    @Test fun manifestRoundTripAndBounds() {
        val (_, manifest) = transfer(); assertArrayEquals(manifest.encode(), requireNotNull(MusicManifest.decode(manifest.encode())).encode())
        assertNull(MusicManifest.decode(manifest.encode().copyOf(20)))
    }
    @Test fun blockReconstructionPersistsBeforeFinalAck() {
        val (repo, manifest) = transfer(32768); val receiver = BlockReceiver(repo)
        val data = ByteArray(32768) { (it % 239).toByte() }; val chunk = 180; val count = (data.size + chunk - 1) / chunk
        var last: BlockAck? = null
        for (i in (0 until count).reversed()) {
            val fragment = BlockFragment(manifest.id, 0, i, count, chunk, manifest.blockHashes[0], data.copyOfRange(i * chunk, minOf((i + 1) * chunk, data.size)))
            last = receiver.receive(requireNotNull(BlockFragment.decode(fragment.encode())))
            if (last?.persisted == true) assertTrue(requireNotNull(repo.load(manifest.id)).completed[0])
        }
        assertTrue(requireNotNull(last).persisted); assertArrayEquals(data, repo.readBlock(manifest.id, 0)); assertTrue(repo.verify(manifest.id).verified)
    }
    @Test fun invalidBlockDoesNotAdvanceProgress() {
        val (repo, manifest) = transfer(100)
        val bad = BlockFragment(manifest.id, 0, 0, 1, 100, manifest.blockHashes[0], ByteArray(100))
        assertFalse(requireNotNull(BlockReceiver(repo).receive(bad)).persisted)
        assertEquals(0, requireNotNull(repo.load(manifest.id)).completed.cardinality())
    }
    @Test fun outOfRangeBlockRejected() {
        val (repo, manifest) = transfer(100)
        assertNull(BlockReceiver(repo).receive(BlockFragment(manifest.id, 2, 0, 1, 100, ByteArray(32), ByteArray(100))))
    }
    @Test fun resumeAfterRepositoryRecreation() {
        val (repo, manifest) = transfer(); repo.writeBlock(manifest.id, 0, ByteArray(32768) { (it % 239).toByte() })
        val reopened = TransferRepository(repo.file(manifest.id).parentFile!!)
        assertTrue(requireNotNull(reopened.accept(manifest).completed)[0])
        assertFalse(reopened.accept(manifest).completed[1])
        val request = ResumeRequest(manifest.id, manifest.sha256, reopened.accept(manifest).completed)
        assertEquals(request.blocks, requireNotNull(ResumeRequest.decode(request.encode())).blocks)
    }
    @Test fun tornSnapshotFallsBackConservatively() {
        val (repo, manifest) = transfer(); val saved = repo.writeBlock(manifest.id, 0, ByteArray(32768) { (it % 239).toByte() })
        java.io.File(repo.file(manifest.id).parentFile, "${manifest.id}.${saved.generation % 2}.state").writeBytes(byteArrayOf(0))
        assertEquals(0, requireNotNull(repo.load(manifest.id)).completed.cardinality())
    }
    @Test fun fullHashFailureClearsVerifiedState() {
        val (repo, manifest) = transfer(100); repo.writeBlock(manifest.id, 0, ByteArray(100) { (it % 239).toByte() })
        repo.file(manifest.id).writeBytes(ByteArray(100))
        val result = repo.verify(manifest.id); assertFalse(result.verified); assertEquals(TransferState.FAILED, result.state)
    }
    @Test fun ackBitmapRoundTrip() {
        val bits = BitSet().apply { set(0); set(15); set(199) }
        val ack = BlockAck(UUID(1, 1), 0, 200, bits, false)
        assertEquals(bits, requireNotNull(BlockAck.decode(ack.encode())).fragments)
    }
    @Test fun selectiveRetransmissionAndTimeout() {
        val window = AckWindow(20); val first = window.due(0); assertEquals(16, first.size); first.forEach { window.sent(it, 0) }
        val ack = BitSet().apply { set(0, 16); clear(4) }; window.acknowledge(ack, 100)
        assertFalse(4 in window.due(200)); assertTrue(4 in window.due(3000)); assertFalse(1 in window.due(3000))
        window.sent(4, 3000); assertEquals(1, window.retries)
    }
    @Test fun nackInvalidatesVolatileFragmentAcks() {
        val window = AckWindow(5)
        repeat(5) { window.sent(it, 0) }; window.acknowledge(BitSet().apply { set(0, 5) }, 20)
        window.resetAfterNack()
        assertEquals((0..4).toList(), window.due(25))
    }
    @Test fun canceledTransferRejectsLateBlocksAndReoffers() {
        val (repo, manifest) = transfer(100)
        repo.transition(manifest.id, TransferState.CANCELED)
        assertTrue(runCatching { repo.writeBlock(manifest.id, 0, ByteArray(100) { (it % 239).toByte() }) }.isFailure)
        assertTrue(runCatching { repo.accept(manifest) }.isFailure)
        assertEquals(0, repo.load(manifest.id)?.completed?.cardinality())
    }
    @Test(expected = IllegalStateException::class) fun retriesAreBounded() { val window = AckWindow(1, maxRetries = 1); repeat(3) { window.sent(0, it * 10000L) } }
    @Test fun replayWindowAllowsReorderingButNotDuplicates() {
        val dedup = PacketDeduplicator(4)
        assertTrue(dedup.accept(1, 1, 3)); assertTrue(dedup.accept(1, 1, 2)); assertFalse(dedup.accept(1, 1, 2))
        assertTrue(dedup.accept(1, 1, 8)); assertFalse(dedup.accept(1, 1, 1))
    }
    @Test fun treeHasEightNodesTwoChildrenAndFourHops() {
        val nodes = (1L..8L).toSet(); val edges = nodes.flatMap { a -> nodes.filter { it > a }.map { b -> MeshEdge(a, b, -60) } }
        val tree = MeshTopology.build(1, nodes, edges)
        assertEquals(7, tree.parents.size); nodes.forEach { assertTrue(tree.children(it).size <= 2); assertTrue(tree.path(it).size in 1..5) }
    }
    @Test fun lostParentChangesRoute() {
        val edges = listOf(MeshEdge(1, 2), MeshEdge(2, 3), MeshEdge(1, 4), MeshEdge(4, 3))
        val first = MeshTopology.build(1, setOf(1, 2, 3, 4), edges)
        val parent = first.parents.getValue(3)
        val second = MeshTopology.build(1, setOf(1, 2, 3, 4), edges.filter { setOf(it.from, it.to) != setOf(3L, parent) }, first)
        assertNotEquals(parent, second.parents[3]); assertEquals(4, second.path(4).last())
    }
    @Test fun stateMachineRejectsPlaybackBeforeVerification() {
        val state = TransferSession(); assertFalse(state.transition(TransferState.PLAYING))
        listOf(TransferState.OFFERED, TransferState.ACCEPTED, TransferState.TRANSFERRING, TransferState.PAUSED,
            TransferState.RESUMING, TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.READY,
            TransferState.SCHEDULED, TransferState.PLAYING).forEach { assertTrue(state.transition(it)) }
    }
    @Test fun clockOffsetAndOutlierRejection() {
        val clock = ClockSynchronizer()
        repeat(4) { val t = 1_000_000_000L * (it + 1); assertTrue(clock.add(ClockSample(t, t + 60_000_000, t + 61_000_000, t + 21_000_000))) }
        assertTrue(clock.ready); assertEquals(50_000_000L, clock.offset)
        assertFalse(clock.add(ClockSample(6_000_000_000, 6_300_000_000, 6_301_000_000, 6_600_000_000)))
        assertEquals(1_040_000_000, ClockSynchronizer.toLocal(1_000_000_000, clock.offset, 10))
    }
    @Test fun negativeRttIsRejected() { assertFalse(ClockSynchronizer().add(ClockSample(100, 100, 500, 200))) }
    @Test fun driftUsesGentleSpeedAndCooldown() {
        val drift = DriftCorrector()
        assertEquals(1.005f, drift.correct(1000, 1100, 0).speed)
        assertNull(drift.correct(1000, 5000, 100).seekMs)
        assertEquals(5000L, drift.correct(1000, 5000, 6000).seekMs)
    }
}

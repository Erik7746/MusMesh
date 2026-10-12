package com.project.musmesh

import com.project.musmesh.audio.MusicPlayer
import com.project.musmesh.ble.BleSendQueue
import com.project.musmesh.mesh.*
import com.project.musmesh.protocol.*
import com.project.musmesh.sync.PlaybackScheduler
import com.project.musmesh.transfer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun oneMegabyteTraversesTwoHopTreeWithLossAndSmallMtu() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rootFile = temp.newFile().apply { writeBytes(ByteArray(1024 * 1024) { (it % 251).toByte() }) }
        val manifest = MusicManifest.create(rootFile, "Sintético", "Prueba", "audio/mpeg", 60000)
        val repos = (1L..5L).associateWith { TransferRepository(temp.newFolder()) }
        val verified = mutableSetOf<Long>()
        val errors = mutableListOf<String>()
        val managers = mutableMapOf<Long, TransferManager>()
        val tree = DistributionTree(1, mapOf(2L to 1L, 3L to 1L, 4L to 2L, 5L to 2L))
        var sequence = 0L
        var dropped = false
        var deliveredData = 0
        val hops = mutableSetOf<Pair<Long, Long>>()
        for (id in 1L..5L) {
            managers[id] = TransferManager(backgroundScope, id, repos.getValue(id), { testScheduler.currentTime },
                { type, to, payload ->
                    if (type == PacketType.BLOCK_DATA) {
                        hops += id to to; deliveredData++
                        if (!dropped && BlockFragment.decode(payload)?.index == 4) { dropped = true; return@TransferManager true }
                    }
                    managers[to]?.handle(MeshPacket(type, ++sequence, id, to, UUID(1, 1), 1, sequence, payload = payload))
                    true
                }, { to -> if (to == 4L) 32 else 180 }, {}, {}, { verified += id }, { errors += it }, dispatcher)
            managers.getValue(id).setRoom(RoomSnapshot(UUID(1, 1), 1, (1L..5L).associateWith { MeshPeer(it, 1) }, tree, admitted = true))
        }
        managers.getValue(1).use(repos.getValue(1).importFile(rootFile, manifest), true)
        withTimeout(240000) { while (verified.size < 5) delay(50) }
        assertTrue(dropped); assertTrue(deliveredData > 1000)
        assertEquals(setOf(1L to 2L, 1L to 3L, 2L to 4L, 2L to 5L), hops)
        for (repo in repos.values) { assertTrue(FileVerifier.verify(repo.file(manifest.id), manifest)); assertEquals(32, repo.load(manifest.id)?.completed?.cardinality()) }
        assertTrue(errors.toString(), errors.isEmpty())
        managers.values.forEach { it.close() }
    }

    @Test fun durableResumeSkipsCompletedHalfAfterRestartAndParentChange() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = temp.newFile().apply { writeBytes(ByteArray(65536) { (it % 251).toByte() }) }
        val manifest = MusicManifest.create(source, "Sintético", "", "audio/mpeg", 10000)
        val parentRepo = TransferRepository(temp.newFolder()); parentRepo.importFile(source, manifest)
        val nodeRepo = TransferRepository(temp.newFolder()); nodeRepo.accept(manifest)
        nodeRepo.writeBlock(manifest.id, 0, parentRepo.readBlock(manifest.id, 0))
        val reopened = TransferRepository(nodeRepo.file(manifest.id).parentFile!!)
        val managers = mutableMapOf<Long, TransferManager>(); val sentBlocks = mutableSetOf<Int>(); var complete = false; var seq = 0L
        for (id in listOf(3L, 4L)) {
            managers[id] = TransferManager(backgroundScope, id, if (id == 3L) parentRepo else reopened, { testScheduler.currentTime },
                { type, to, bytes ->
                    if (type == PacketType.BLOCK_DATA) sentBlocks += requireNotNull(BlockFragment.decode(bytes)).block
                    managers[to]?.handle(MeshPacket(type, ++seq, id, to, UUID(1, 1), 1, seq, payload = bytes)); true
                }, { 180 }, {}, {}, { if (id == 4L) complete = true }, { fail(it) }, dispatcher)
            managers.getValue(id).setRoom(RoomSnapshot(UUID(1, 1), 1, tree = DistributionTree(1, mapOf(3L to 1L, 4L to 3L)), admitted = true))
        }
        managers.getValue(3).use(requireNotNull(parentRepo.load(manifest.id)), true)
        managers.getValue(4).use(requireNotNull(reopened.load(manifest.id)), true)
        withTimeout(30000) { while (!complete) delay(25) }
        assertEquals(setOf(1), sentBlocks); assertTrue(reopened.load(manifest.id)?.verified == true)
        managers.values.forEach { it.close() }
    }

    @Test fun gattQueueWaitsForCallbackAndControlPreemptsData() = runTest {
        val writes = mutableListOf<Int>(); var disconnected = false
        val queue = BleSendQueue(backgroundScope, { testScheduler.currentTime }, { writes += it[0].toInt(); true }, { disconnected = true })
        val data = backgroundScope.async { queue.send(listOf(byteArrayOf(1), byteArrayOf(2)), 2) }
        runCurrent(); assertEquals(listOf(1), writes); assertFalse(data.isCompleted)
        val control = backgroundScope.async { queue.send(listOf(byteArrayOf(9)), 0) }
        runCurrent(); assertEquals(listOf(1), writes)
        queue.callback(true); runCurrent(); assertEquals(listOf(1, 9), writes)
        queue.callback(true); runCurrent(); assertEquals(listOf(1, 9, 2), writes); assertTrue(control.await())
        queue.callback(true); runCurrent(); assertTrue(data.await()); assertFalse(disconnected); queue.close()
    }
    @Test fun missingGattCallbackClosesGenerationInsteadOfAdvancing() = runTest {
        var disconnected = false; var writes = 0
        val queue = BleSendQueue(backgroundScope, { testScheduler.currentTime }, { writes++; true }, { disconnected = true })
        val result = backgroundScope.async { queue.send(listOf(byteArrayOf(1), byteArrayOf(2)), 2) }
        runCurrent(); advanceTimeBy(5001); runCurrent()
        assertTrue(disconnected); assertFalse(result.await()); assertEquals(1, writes)
    }
    @Test fun disconnectReleasesQueuedWaiters() = runTest {
        val queue = BleSendQueue(backgroundScope, { testScheduler.currentTime }, { true }, {})
        val result = backgroundScope.async { queue.send(listOf(byteArrayOf(1), byteArrayOf(2)), 2) }
        runCurrent(); queue.close(); runCurrent(); assertFalse(result.await())
    }
    @Test fun playbackDeadlineUsesOffsetAndRejectsOldCommands() = runTest {
        val player = FakePlayer(); var skew = Long.MAX_VALUE
        val scheduler = PlaybackScheduler(backgroundScope, player, { testScheduler.currentTime * 1_000_000 }) { _, late -> skew = late }
        val command = ControlPacket(UUID(1, 1), 1, 4_000_000_000L, 500, true)
        assertTrue(scheduler.schedule(PacketType.PLAY_AT, command, 50_000_000, 10))
        assertFalse(scheduler.schedule(PacketType.PLAY_AT, command, 0, 0))
        advanceTimeBy(4039); runCurrent(); assertFalse(player.playing)
        advanceTimeBy(2); runCurrent(); assertTrue(player.playing); assertEquals(0L, skew)
        assertEquals(500, player.positionMs)
    }
    private class FakePlayer : MusicPlayer {
        override var positionMs = 0L
        override var playing = false
        override val ready = true
        override suspend fun prepare(file: File, positionMs: Long) { this.positionMs = positionMs }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seek(positionMs: Long) { this.positionMs = positionMs }
        override fun speed(value: Float) {}
        override fun stop() { playing = false; positionMs = 0 }
        override fun release() {}
    }
}

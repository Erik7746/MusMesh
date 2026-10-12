package com.project.musmesh

import com.project.musmesh.app.*
import com.project.musmesh.mesh.*
import com.project.musmesh.service.ForegroundPolicy
import com.project.musmesh.transfer.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ForegroundPolicyTest {
    private fun state() = AppState(role = Role.NODE, nodeId = 2,
        room = RoomSnapshot(root = 1, tree = DistributionTree(1, mapOf(2L to 1L, 3L to 2L))),
        manifest = MusicManifest(UUID(1, 1), 32768, ByteArray(32), listOf(ByteArray(32)), "Sintético", "", "audio/mpeg", 1000),
        transferState = TransferState.READY)
    @Test fun scheduledPlaybackAcquiresCpuImmediately() { assertTrue(ForegroundPolicy.needsCpu(AppState(transferState = TransferState.SCHEDULED))) }
    @Test fun readyRelayKeepsCpuUntilChildHasDurableBlock() {
        assertTrue(ForegroundPolicy.needsCpu(state()))
        assertFalse(ForegroundPolicy.needsCpu(state().copy(progress = mapOf(3L to TransferProgress(3, blocks = 1, total = 1)))))
    }
    @Test fun lostRouteAndCancellationReleaseCpu() {
        assertFalse(ForegroundPolicy.needsCpu(state().copy(room = RoomSnapshot(root = 1))))
        assertFalse(ForegroundPolicy.needsCpu(state().copy(transferState = TransferState.CANCELED)))
    }
    @Test fun serverSelectionAloneDoesNotAcquireCpuForever() { assertFalse(ForegroundPolicy.needsCpu(state().copy(role = Role.SERVER, distributing = false))) }
}

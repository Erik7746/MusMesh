package com.project.musmesh.service

import com.project.musmesh.app.AppState
import com.project.musmesh.app.Role
import com.project.musmesh.transfer.TransferState

object ForegroundPolicy {
    fun needsCpu(state: AppState): Boolean {
        if (state.busy || state.transferState == TransferState.SCHEDULED) return true
        if (state.room.tree.path(state.nodeId).isEmpty()) return false
        if (state.transferState in setOf(TransferState.ACCEPTED, TransferState.TRANSFERRING, TransferState.RESUMING, TransferState.VERIFYING)) return true
        val manifest = state.manifest ?: return false
        if (state.transferState in setOf(TransferState.CANCELED, TransferState.FAILED)) return false
        if (state.role == Role.SERVER && !state.distributing) return false
        return state.room.tree.children(state.nodeId).any { child -> state.progress[child]?.blocks != manifest.blockCount }
    }
}

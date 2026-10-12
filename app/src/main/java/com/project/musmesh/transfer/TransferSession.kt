package com.project.musmesh.transfer

enum class TransferState {
    IDLE, DISCOVERING, CONNECTING, OFFERED, ACCEPTED, TRANSFERRING, PAUSED, RESUMING,
    VERIFYING, READY, SCHEDULED, PLAYING, COMPLETED, CANCELED, FAILED
}

class TransferSession(initial: TransferState = TransferState.IDLE) {
    var state = initial
        private set
    fun transition(next: TransferState): Boolean {
        if (state == next) return true
        val allowed = when (state) {
            TransferState.IDLE -> setOf(TransferState.DISCOVERING, TransferState.OFFERED)
            TransferState.DISCOVERING -> setOf(TransferState.CONNECTING, TransferState.OFFERED)
            TransferState.CONNECTING -> setOf(TransferState.DISCOVERING, TransferState.OFFERED)
            TransferState.OFFERED -> setOf(TransferState.ACCEPTED)
            TransferState.ACCEPTED -> setOf(TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.PAUSED)
            TransferState.TRANSFERRING -> setOf(TransferState.PAUSED, TransferState.VERIFYING)
            TransferState.PAUSED -> setOf(TransferState.RESUMING)
            TransferState.RESUMING -> setOf(TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.PAUSED)
            TransferState.VERIFYING -> setOf(TransferState.READY)
            TransferState.READY -> setOf(TransferState.SCHEDULED, TransferState.COMPLETED)
            TransferState.SCHEDULED -> setOf(TransferState.PLAYING, TransferState.READY, TransferState.COMPLETED)
            TransferState.PLAYING -> setOf(TransferState.SCHEDULED, TransferState.READY, TransferState.COMPLETED)
            TransferState.COMPLETED -> setOf(TransferState.READY, TransferState.SCHEDULED, TransferState.OFFERED)
            TransferState.CANCELED, TransferState.FAILED -> setOf(TransferState.OFFERED, TransferState.RESUMING)
        }
        if (next !in allowed && next !in setOf(TransferState.FAILED, TransferState.CANCELED)) return false
        state = next
        return true
    }
}

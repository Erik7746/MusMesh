package com.project.musmesh

import com.project.musmesh.mesh.AdmissionGuard
import com.project.musmesh.mesh.JoinAttempt
import org.junit.Assert.*
import org.junit.Test

class AdmissionTest {
    @Test fun rootReplyCannotReplayAfterAdmissionOrClientTimeout() {
        val attempt = JoinAttempt(); val first = attempt.begin(0)
        assertTrue(attempt.matches(first, 100)); attempt.complete(); assertFalse(attempt.matches(first, 101))
        val second = attempt.begin(102); assertNotEquals(first, second)
        assertFalse(attempt.matches(second, 20000)); assertNotEquals(second, attempt.begin(20000))
    }
    @Test fun onlyFreshChallengeInstallsSession() {
        val guard = AdmissionGuard()
        val nonce = requireNotNull(guard.challenge(1, 4, 0))
        assertFalse(guard.confirm(1, 3, nonce, 10))
        assertFalse(guard.confirm(1, 4, nonce + 1, 10))
        assertTrue(guard.confirm(1, 4, nonce, 10))
        assertFalse(guard.confirm(1, 4, nonce, 11))
    }
    @Test fun expiredChallengeCannotReplay() {
        val guard = AdmissionGuard(); val nonce = requireNotNull(guard.challenge(1, 1, 0))
        assertFalse(guard.confirm(1, 1, nonce, 15001))
        assertNotEquals(nonce, guard.challenge(1, 1, 15001))
    }
}

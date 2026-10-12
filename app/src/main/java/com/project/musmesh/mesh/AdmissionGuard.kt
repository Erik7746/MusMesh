package com.project.musmesh.mesh

import com.project.musmesh.protocol.Ids

/** A fresh root challenge is required before installing a new sender session. */
class AdmissionGuard {
    private data class Challenge(val session: Long, val nonce: Long, val expires: Long)
    private val challenges = mutableMapOf<Long, Challenge>()
    fun challenge(node: Long, session: Long, nowMs: Long): Long? {
        challenges.entries.removeAll { it.value.expires < nowMs }
        challenges[node]?.takeIf { it.session == session }?.let { return it.nonce }
        if (challenges.size >= 8) return null
        val challenge = Challenge(session, Ids.node(), nowMs + 15000)
        challenges[node] = challenge
        return challenge.nonce
    }
    fun confirm(node: Long, session: Long, nonce: Long, nowMs: Long): Boolean {
        val expected = challenges[node] ?: return false
        if (expected.session != session || expected.nonce != nonce || nowMs > expected.expires) return false
        challenges.remove(node)
        return true
    }
}

/** A root response must echo a currently outstanding unpredictable client challenge. */
class JoinAttempt {
    private var nonce = 0L
    private var expires = 0L
    fun begin(nowMs: Long): Long {
        if (nonce == 0L || nowMs > expires) { nonce = Ids.node(); expires = nowMs + 15000 }
        return nonce
    }
    fun matches(candidate: Long, nowMs: Long) = nonce != 0L && candidate == nonce && nowMs <= expires
    fun complete() { nonce = 0; expires = 0 }
}

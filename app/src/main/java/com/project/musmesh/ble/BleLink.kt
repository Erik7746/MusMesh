package com.project.musmesh.ble

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

object BleUuids {
    val SERVICE: UUID = UUID.fromString("42dd41d0-b754-41a8-9108-df29226e2b10")
    val DATA: UUID = UUID.fromString("42dd41d1-b754-41a8-9108-df29226e2b10")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

class BleLink(val address: String, var mtu: Int, val rssi: Int, scope: CoroutineScope,
    write: (ByteArray) -> Boolean, val disconnect: () -> Unit, private val onFrame: (BleLink, ByteArray) -> Unit) {
    val generation = UUID.randomUUID()
    var nodeId: Long? = null
    val connectedAt = SystemClock.elapsedRealtime()
    private var nextMessage = 1
    private val assembler = LinkAssembler()
    val queue = BleSendQueue(scope, SystemClock::elapsedRealtime, write, disconnect)
    suspend fun send(bytes: ByteArray, priority: Int): Boolean = queue.send(LinkFramer.split(bytes, mtu, nextMessage++), priority)
    fun receive(bytes: ByteArray) { assembler.receive(bytes, SystemClock.elapsedRealtime())?.let { onFrame(this, it) } }
    fun close() = queue.close()
}

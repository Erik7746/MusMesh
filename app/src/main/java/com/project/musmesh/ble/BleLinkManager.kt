@file:Suppress("MissingPermission")
package com.project.musmesh.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.SystemClock
import kotlinx.coroutines.*

class BleLinkManager(private val context: Context, private val scope: CoroutineScope, private val nodeId: Long,
    private val connected: (BleLink) -> Unit, private val disconnected: (BleLink) -> Unit,
    private val frame: (BleLink, ByteArray) -> Unit, private val error: (String) -> Unit) {
    val links = mutableMapOf<String, BleLink>()
    private val clients = mutableMapOf<String, BleGattClient>()
    private val attempts = mutableMapOf<Long, Pair<Int, Long>>()
    private var scanner: BleScanner? = null
    private var advertiser: BleAdvertiser? = null
    private var server: BleGattServer? = null
    private var active = false
    private var receiverRegistered = false
    private var roomCode = ""
    private var powerProfile = PowerProfile.IDLE
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> { stopRadio(); error("Bluetooth apagado; se reanudará al activarlo") }
                BluetoothAdapter.STATE_ON -> if (active) startRadio(roomCode)
            }
        }
    }
    fun start(code: String) {
        stop(); active = true
        roomCode = code
        ContextCompat.registerReceiver(context, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        startRadio(code)
    }
    private fun startRadio(code: String) {
        stopRadio()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) { error("Bluetooth apagado o no disponible"); return }
        advertiser = BleAdvertiser(adapter) { scope.launch { error(it) } }
        server = BleGattServer(context, scope, ::ready, ::gone, frame, { if (active) advertiser?.start(nodeId, code) }, error)
        scanner = BleScanner(adapter, scope, { device, remoteId, rssi ->
            // Only the larger logical ID initiates, avoiding simultaneous reciprocal connections.
            if (active && nodeId > remoteId && remoteId != nodeId && device.address !in links && device.address !in clients &&
                links.values.none { it.nodeId == remoteId } && (links.keys + clients.keys).size < 7 && clients.keys.count { it !in links } < 2) {
                val old = attempts[remoteId] ?: (0 to 0L)
                val backoff = (2000L shl old.first.coerceAtMost(5)).coerceAtMost(60000)
                if (SystemClock.elapsedRealtime() - old.second >= backoff) {
                    if (attempts.size > 32) attempts.clear()
                    attempts[remoteId] = old.first + 1 to SystemClock.elapsedRealtime()
                    val client = BleGattClient(context, scope, ::ready, { link -> clients.remove(device.address); link?.let(::gone) }, frame)
                    clients[device.address] = client; client.connect(device, rssi)
                }
            }
        }, error)
        try { server?.start(); scanner?.start(code, powerProfile) } catch (_: SecurityException) { error("Faltan permisos Bluetooth"); stopRadio() }
    }
    private fun ready(link: BleLink) {
        if (!active || link.address in links || links.size >= 7) { link.disconnect(); return }
        links[link.address] = link; connected(link)
    }
    private fun gone(link: BleLink) {
        if (links[link.address] !== link) return
        links.remove(link.address); disconnected(link)
    }
    fun setPowerProfile(profile: PowerProfile) {
        if (profile == powerProfile) return
        powerProfile = profile
        if (active) scanner?.start(roomCode, profile)
    }
    fun stop() {
        active = false
        if (receiverRegistered) { runCatching { context.unregisterReceiver(bluetoothState) }; receiverRegistered = false }
        stopRadio()
    }
    private fun stopRadio() {
        scanner?.stop(); advertiser?.stop(); clients.values.toList().forEach { it.close() }; clients.clear()
        server?.close(); server = null; links.values.toList().forEach { it.close() }; links.clear()
    }
}

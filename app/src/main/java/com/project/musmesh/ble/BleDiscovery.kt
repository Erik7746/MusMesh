@file:Suppress("MissingPermission")
package com.project.musmesh.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.os.ParcelUuid
import com.project.musmesh.protocol.Ids
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class PowerProfile(val scanMode: Int, val scanOnMs: Long, val scanOffMs: Long) {
    TRANSFER(ScanSettings.SCAN_MODE_LOW_LATENCY, 12000, 18000),
    IDLE(ScanSettings.SCAN_MODE_BALANCED, 10000, 20000)
}

fun roomHint(code: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).copyOf(2)

class BleScanner(private val adapter: BluetoothAdapter, private val scope: CoroutineScope,
    private val found: (BluetoothDevice, Long, Int) -> Unit, private val error: (String) -> Unit) {
    private var callback: ScanCallback? = null
    private var job: Job? = null
    fun start(code: String, profile: PowerProfile = PowerProfile.IDLE) {
        stop()
        val hint = roomHint(code)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(ParcelUuid(BleUuids.SERVICE)) ?: return
                if (data.size != 10 || !data.copyOfRange(8, 10).contentEquals(hint)) return
                val node = ByteBuffer.wrap(data).long
                if (node > 0) scope.launch { found(result.device, node, result.rssi) }
            }
            override fun onScanFailed(errorCode: Int) { scope.launch { error("No se pudo iniciar el escaneo BLE ($errorCode)") } }
        }
        callback = cb
        job = scope.launch {
            while (isActive) {
                try {
                    val scanner = adapter.bluetoothLeScanner ?: error("Bluetooth apagado").let { return@launch }
                    scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.SERVICE)).build()),
                        ScanSettings.Builder().setScanMode(profile.scanMode).build(), cb)
                    delay(profile.scanOnMs); scanner.stopScan(cb); delay(profile.scanOffMs)
                } catch (_: SecurityException) { error("Faltan permisos Bluetooth"); break }
                catch (_: IllegalStateException) { error("Bluetooth apagado"); break }
            }
        }
    }
    fun stop() { job?.cancel(); job = null; callback?.let { runCatching { adapter.bluetoothLeScanner?.stopScan(it) } }; callback = null }
}

class BleAdvertiser(private val adapter: BluetoothAdapter, private val error: (String) -> Unit) {
    private var callback: AdvertiseCallback? = null
    fun start(nodeId: Long, code: String) {
        stop()
        val advertiser = adapter.bluetoothLeAdvertiser ?: return error("Este teléfono no permite advertising BLE")
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { error("Advertising BLE no disponible ($errorCode)") }
        }
        callback = cb
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(BleUuids.SERVICE)).setIncludeDeviceName(false).build()
        val response = AdvertiseData.Builder().addServiceData(ParcelUuid(BleUuids.SERVICE), ByteBuffer.allocate(10).putLong(nodeId).put(roomHint(code)).array()).build()
        advertiser.startAdvertising(AdvertiseSettings.Builder().setConnectable(true).setTimeout(0)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(), data, response, cb)
    }
    fun stop() { callback?.let { runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } }; callback = null }
}

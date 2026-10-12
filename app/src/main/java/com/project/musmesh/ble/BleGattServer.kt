@file:Suppress("MissingPermission", "DEPRECATION")
package com.project.musmesh.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*

class BleGattServer(private val context: Context, private val scope: CoroutineScope,
    private val ready: (BleLink) -> Unit, private val gone: (BleLink) -> Unit,
    private val frame: (BleLink, ByteArray) -> Unit, private val started: () -> Unit, private val error: (String) -> Unit) {
    private var server: BluetoothGattServer? = null
    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val links = mutableMapOf<String, BleLink>()
    private val mtus = mutableMapOf<String, Int>()
    private val characteristic = BluetoothGattCharacteristic(BleUuids.DATA,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
        BluetoothGattCharacteristic.PERMISSION_WRITE).apply {
        addDescriptor(BluetoothGattDescriptor(BleUuids.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
    }
    fun start() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) { scope.launch {
                if (server == null) return@launch
                if (status == BluetoothGatt.GATT_SUCCESS) started() else error("No se pudo crear el servicio GATT")
            } }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) { scope.launch {
                if (server == null) return@launch
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (devices.size >= 7) server?.cancelConnection(device) else {
                        devices[device.address] = device
                        scope.launch { delay(15000); if (device.address in devices && device.address !in links) disconnect(device) }
                    }
                } else disconnect(device)
            } }
            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { scope.launch {
                mtus[device.address] = mtu.coerceIn(23, 517); links[device.address]?.mtu = mtu.coerceIn(23, 517)
            } }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, target: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) { scope.launch {
                val link = links[device.address]
                val valid = !preparedWrite && offset == 0 && target.uuid == BleUuids.DATA && link != null && value.size in 9..MtuManager.attCapacity(link.mtu)
                if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                if (valid) link?.receive(value)
            } }
            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) { scope.launch {
                val valid = !preparedWrite && offset == 0 && descriptor.uuid == BleUuids.CCCD &&
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) && device.address in devices
                if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                if (valid && device.address !in links) {
                    val link = BleLink(device.address, mtus[device.address] ?: 23, -90, scope, { bytes ->
                        val gatt = server
                        if (gatt == null) false else if (Build.VERSION.SDK_INT >= 33) gatt.notifyCharacteristicChanged(device, characteristic, true, bytes) == BluetoothStatusCodes.SUCCESS
                        else { characteristic.value = bytes; gatt.notifyCharacteristicChanged(device, characteristic, true) }
                    }, { disconnect(device) }, frame)
                    links[device.address] = link; ready(link)
                }
            } }
            override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) { scope.launch {
                server?.sendResponse(device, requestId, if (offset == 0 && descriptor.uuid == BleUuids.CCCD) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET,
                    0, if (device.address in links) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            } }
            override fun onNotificationSent(device: BluetoothDevice, status: Int) { scope.launch { links[device.address]?.queue?.callback(status == BluetoothGatt.GATT_SUCCESS) } }
        })
        val service = BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(characteristic) }
        if (server?.addService(service) != true) error("GATT server no disponible")
    }
    private fun disconnect(device: BluetoothDevice) {
        devices.remove(device.address); mtus.remove(device.address)
        links.remove(device.address)?.let { it.close(); gone(it) }
        runCatching { server?.cancelConnection(device) }
    }
    fun close() { devices.values.toList().forEach(::disconnect); runCatching { server?.close() }; server = null }
}

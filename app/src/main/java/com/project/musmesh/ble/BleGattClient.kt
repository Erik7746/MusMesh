@file:Suppress("MissingPermission", "DEPRECATION")
package com.project.musmesh.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*

class BleGattClient(private val context: Context, private val scope: CoroutineScope,
    private val ready: (BleLink) -> Unit, private val gone: (BleLink?) -> Unit,
    private val frame: (BleLink, ByteArray) -> Unit) {
    private var gatt: BluetoothGatt? = null
    private var link: BleLink? = null
    private var mtu = 23
    private var timeout: Job? = null
    private var closed = false
    fun connect(device: BluetoothDevice, rssi: Int) {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { scope.launch {
                if (closed) { gatt.close(); return@launch }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!gatt.requestMtu(517)) close()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) close()
            } }
            override fun onMtuChanged(gatt: BluetoothGatt, value: Int, status: Int) { scope.launch {
                if (closed) return@launch
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = value.coerceIn(23, 517)
                if (link == null) { if (!gatt.discoverServices()) close() } else link?.mtu = mtu
            } }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { scope.launch {
                if (closed) return@launch
                val characteristic = gatt.getService(BleUuids.SERVICE)?.getCharacteristic(BleUuids.DATA)
                val descriptor = characteristic?.getDescriptor(BleUuids.CCCD)
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) { close(); return@launch }
                val accepted = if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                else { descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE; gatt.writeDescriptor(descriptor) }
                if (!accepted) close()
            } }
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { scope.launch {
                if (closed || descriptor.uuid != BleUuids.CCCD || link != null) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS) { close(); return@launch }
                val characteristic = descriptor.characteristic
                link = BleLink(device.address, mtu, rssi, scope, { data ->
                    if (closed) false else if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    else { characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; characteristic.value = data; gatt.writeCharacteristic(characteristic) }
                }, ::close, frame)
                timeout?.cancel(); ready(requireNotNull(link))
            } }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { scope.launch {
                if (!closed && characteristic.uuid == BleUuids.DATA) link?.queue?.callback(status == BluetoothGatt.GATT_SUCCESS)
            } }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == BleUuids.DATA) scope.launch { if (!closed) link?.receive(value) }
            }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(gatt, characteristic, characteristic.value?.clone() ?: return)
            }
        }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        timeout = scope.launch { delay(15000); if (link == null) close() }
        if (gatt == null) close()
    }
    fun close() {
        if (closed) return
        closed = true; timeout?.cancel(); link?.close(); runCatching { gatt?.disconnect(); gatt?.close() }; gone(link); gatt = null
    }
}

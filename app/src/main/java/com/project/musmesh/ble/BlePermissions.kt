package com.project.musmesh.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BlePermissions {
    @android.annotation.SuppressLint("InlinedApi") // Explicit sdk parameter is also used by version-policy tests.
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> = if (sdk >= 31) listOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE
    ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun missing(context: Context) = required().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    fun requestable() = required() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
}

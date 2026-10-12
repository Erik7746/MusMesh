package com.project.musmesh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.project.musmesh.app.MusMeshApplication
import com.project.musmesh.ble.BlePermissions
import com.project.musmesh.service.MusMeshForegroundService
import com.project.musmesh.ui.MusMeshScreen
import com.project.musmesh.ui.theme.MusMeshTheme

class
MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val coordinator = (application as MusMeshApplication).coordinator
        setContent {
            val state by coordinator.state.collectAsState()
            var pendingAction by remember { mutableStateOf<Pair<String, String?>?>(null) }
            fun launchService(action: String, code: String?) {
                try { ContextCompat.startForegroundService(this, Intent(this, MusMeshForegroundService::class.java).setAction(action).putExtra("code", code)) }
                catch (_: Exception) { coordinator.reportError("No se pudo iniciar la sala. Mantén la aplicación visible y revisa Bluetooth.") }
            }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                val pending = pendingAction; pendingAction = null
                if (BlePermissions.missing(this).isEmpty() && pending != null) launchService(pending.first, pending.second)
                else if (BlePermissions.missing(this).isNotEmpty()) coordinator.reportError("MusMesh necesita permisos de dispositivos cercanos; en Android 11 o anterior, ubicación para escanear BLE.")
            }
            val picker = rememberLauncherForActivityResult(com.project.musmesh.audio.LocalMusicPicker.Contract()) { uri -> uri?.let(coordinator::selectMusic) }
            fun start(action: String, code: String? = null) {
                if (BlePermissions.missing(this).isEmpty()) launchService(action, code)
                else { pendingAction = action to code; permissions.launch(BlePermissions.requestable().toTypedArray()) }
            }
            MusMeshTheme(dynamicColor = false) {
                MusMeshScreen(state, coordinator,
                    create = { start(MusMeshForegroundService.CREATE) }, join = { start(MusMeshForegroundService.JOIN, it) },
                    restore = if (coordinator.hasSavedRoom()) ({ start(MusMeshForegroundService.RESTORE) }) else null,
                    pick = { picker.launch(arrayOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/x-m4a")) },
                    leave = { startService(Intent(this, MusMeshForegroundService::class.java).setAction(MusMeshForegroundService.LEAVE)) },
                    bluetooth = { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    permissions = { permissions.launch(BlePermissions.requestable().toTypedArray()) })
            }
        }
    }
}

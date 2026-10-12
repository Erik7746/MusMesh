package com.project.musmesh.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.project.musmesh.MainActivity
import com.project.musmesh.R
import com.project.musmesh.app.*
import com.project.musmesh.ble.BlePermissions
import com.project.musmesh.transfer.TransferState
import kotlinx.coroutines.*

class MusMeshForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator get() = (application as MusMeshApplication).coordinator
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaTypeEnabled = false
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sala y reproducción", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusMesh:active-operation").apply { setReferenceCounted(false) }
        if (BlePermissions.missing(this).isNotEmpty()) { coordinator.reportError("Faltan permisos Bluetooth"); stopSelf(); return }
        try { foreground(coordinator.state.value) } catch (_: SecurityException) { coordinator.reportError("No se pudo iniciar el servicio: revisa los permisos"); stopSelf(); return }
        scope.launch { coordinator.state.collect { state ->
            if (state.transferState == TransferState.SCHEDULED || state.playing) mediaTypeEnabled = true
            foreground(state)
            updateWakeLock(state)
        } }
        scope.launch {
            while (isActive) {
                updateWakeLock(coordinator.state.value)
                delay(10000)
            }
        }
    }
    private fun updateWakeLock(state: AppState) {
        if (ForegroundPolicy.needsCpu(state)) wakeLock?.acquire(20000)
        else if (wakeLock?.isHeld == true) wakeLock?.release()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BlePermissions.missing(this).isNotEmpty()) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            CREATE -> coordinator.start(true)
            JOIN -> coordinator.start(false, intent.getStringExtra("code"))
            CANCEL -> coordinator.cancelTransfer()
            LEAVE -> { coordinator.stop(); stopSelf(); return START_NOT_STICKY }
            else -> if (coordinator.hasSavedRoom()) coordinator.start(false, restore = true) else { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }
    private fun foreground(state: AppState) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, MusMeshForegroundService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val leave = PendingIntent.getService(this, 2, Intent(this, MusMeshForegroundService::class.java).setAction(LEAVE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (state.manifest != null) "${state.completedBlocks}/${state.manifest.blockCount} bloques · ${if (state.playing) "Reproduciendo" else "Sala activa"}" else "Descubriendo teléfonos por Bluetooth LE"
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MusMesh · ${if (state.role == Role.SERVER) "Servidor" else "Nodo"}").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Cancelar transferencia", cancel).addAction(0, "Cerrar sala", leave).build()
        val types = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (mediaTypeEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(11, notification, types) else startForeground(11, notification)
    }
    override fun onDestroy() {
        scope.cancel(); coordinator.stop(false)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null; super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val CREATE = "com.project.musmesh.CREATE"
        const val JOIN = "com.project.musmesh.JOIN"
        const val RESTORE = "com.project.musmesh.RESTORE"
        const val CANCEL = "com.project.musmesh.CANCEL"
        const val LEAVE = "com.project.musmesh.LEAVE"
        private const val CHANNEL = "musmesh-room"
    }
}

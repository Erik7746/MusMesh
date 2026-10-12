package com.project.musmesh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.musmesh.BuildConfig
import com.project.musmesh.app.*
import com.project.musmesh.ble.MtuManager
import com.project.musmesh.protocol.PacketType
import com.project.musmesh.transfer.TransferState

@Composable
fun MusMeshScreen(state: AppState, coordinator: AppCoordinator, create: () -> Unit, join: (String) -> Unit,
    restore: (() -> Unit)?, pick: () -> Unit, leave: () -> Unit, bluetooth: () -> Unit, permissions: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        LazyColumn(modifier = Modifier.safeDrawingPadding(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("MusMesh", fontSize = 32.sp, fontWeight = FontWeight.Bold); Text("MÚSICA LOCAL · SOLO BLE", fontSize = 11.sp, letterSpacing = 2.sp, color = colors.primary) }
                    if (state.role != null) TextButton(onClick = leave) { Text("Salir") }
                }
            }
            state.error?.let { message -> item {
                Card(colors = CardDefaults.cardColors(containerColor = colors.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, color = colors.onErrorContainer)
                        Row { TextButton(onClick = bluetooth) { Text("Bluetooth") }; TextButton(onClick = permissions) { Text("Permisos") }; TextButton(onClick = coordinator::clearError) { Text("Cerrar") } }
                    }
                }
            } }
            if (state.role == null) {
                item { RoleCard(create, join, restore) }
                item { InfoCard("De un teléfono a todos", "Primero compartimos el archivo completo. Después, cada teléfono reproduce su copia verificada a la misma hora.") }
                item { Text("Hasta 8 dispositivos · MP3 y M4A/AAC · 5 MiB\nSin cuentas. Sin Internet. Sin streaming.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (state.role == Role.SERVER) "TU SALA · SERVIDOR" else "CONECTADO COMO NODO", fontSize = 12.sp, letterSpacing = 1.sp)
                            Text(state.code.chunked(3).joinToString(" "), fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                            Text(state.status, style = MaterialTheme.typography.bodyMedium)
                            Text("${state.room.peers.size.coerceAtLeast(1)} de 8 dispositivos", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                item { TrackCard(state, pick, coordinator) }
                item { PlayerCard(state, coordinator) }
                if (state.role == Role.SERVER) {
                    item { Text("Dispositivos de la sala", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                    if (state.room.peers.size <= 1) item { InfoCard("Esperando teléfonos", "Abre MusMesh en otro teléfono, elige Unirse a sala e introduce el código. Mantén Bluetooth activado.") }
                    items(state.room.peers.values.filter { it.id != state.nodeId }.sortedBy { it.id }, key = { it.id }) { peer ->
                        val progress = state.progress[peer.id]; val playback = state.playback[peer.id]
                        val route = state.room.tree.path(peer.id); val parent = state.room.tree.parents[peer.id]
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column { Text(peerName(peer.id, state), fontWeight = FontWeight.Bold); Text(if (playback?.ready == true) "LISTO" else if (route.isEmpty()) "Fuera de alcance" else progress?.status ?: "Conectado", color = if (playback?.ready == true) colors.primary else colors.onSurfaceVariant) }
                                    Switch(checked = peer.required, onCheckedChange = { coordinator.setRequired(peer.id, it) })
                                }
                                Text(if (peer.required) "Obligatorio para reproducir" else "Excluido de la espera", style = MaterialTheme.typography.labelSmall)
                                val fraction = if ((progress?.total ?: 0) > 0) progress!!.blocks.toFloat() / progress.total else 0f
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                Text("${(fraction * 100).toInt()} % confirmado · ${progress?.blocks ?: 0}/${progress?.total ?: state.manifest?.blockCount ?: 0} bloques", style = MaterialTheme.typography.bodySmall)
                                Text("Padre: ${parent?.let { peerName(it, state) } ?: "sin ruta"} · ${(route.size - 1).coerceAtLeast(0)} saltos", style = MaterialTheme.typography.bodySmall)
                                if (route.isNotEmpty()) Text(route.joinToString(" → ") { peerName(it, state) }, style = MaterialTheme.typography.labelSmall)
                                state.room.rssi[peer.id]?.let { Text("RSSI $it dBm", style = MaterialTheme.typography.labelSmall) }
                                if ((progress?.bytesPerSecond ?: 0) > 0) {
                                    val remaining = ((state.manifest?.size ?: 0) * (1 - fraction)).toLong()
                                    Text("${progress!!.bytesPerSecond} B/s · aprox. ${remaining / progress.bytesPerSecond} s restantes · ${progress.retries} reintentos", style = MaterialTheme.typography.bodySmall)
                                } else Text("Tiempo restante: esperando medición", style = MaterialTheme.typography.labelSmall)
                                playback?.let { Text("Reloj: RTT ${"%.1f".format(it.rttMs)} ms · offset ${"%.1f".format(it.offsetMs)} ms", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                } else {
                    item { InfoCard("Conexión y relay", "${if (state.room.admitted) "Admitido en la sala" else "Solicitando acceso"}\nPadre: ${state.room.tree.parents[state.nodeId]?.let { peerName(it, state) } ?: "esperando ruta"}\nRetransmitiendo a ${state.room.tree.children(state.nodeId).size} nodos. Los bloques se verifican antes de reenviarse.") }
                }
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Compensación de salida", style = MaterialTheme.typography.titleMedium)
                            Text("${state.outputOffsetMs} ms · un valor positivo adelanta el inicio para compensar la latencia del altavoz.", style = MaterialTheme.typography.bodySmall)
                            Slider(value = state.outputOffsetMs.toFloat(), onValueChange = { coordinator.setOutputOffset(it.toInt()) }, valueRange = -500f..500f, steps = 99)
                            Text("Objetivo: menos de 100 ms entre teléfonos. Pendiente de medición acústica en dispositivos físicos.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (BuildConfig.DEBUG) item {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Diagnóstico local", fontWeight = FontWeight.SemiBold)
                        for ((id, metric) in state.room.direct) Text("${peerName(id, state)} · MTU ${metric.mtu} · útil ${MtuManager.fragmentPayload(metric.mtu)} B\nCola ${metric.queued} / ${metric.pendingBytes} B · RTT GATT ${metric.rttMs} ms\nATT ${metric.sent} · reintentos ${metric.retries} · errores ${metric.errors}", style = MaterialTheme.typography.labelSmall)
                        for ((id, progress) in state.progress.filterValues { it.fragmentsSent > 0 }) Text("${peerName(id, state)} · fragmentos intentados ${progress.fragmentsSent} · ACK ${progress.fragmentsConfirmed}", style = MaterialTheme.typography.labelSmall)
                        state.playback.values.mapNotNull { it.dispatchSkewMs }.forEach { Text("Retraso del planificador: ${"%.2f".format(it)} ms (no es desviación acústica)", style = MaterialTheme.typography.labelSmall) }
                    } }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable private fun RoleCard(create: () -> Unit, join: (String) -> Unit, restore: (() -> Unit)?) {
    var code by rememberSaveable { mutableStateOf("") }
    Card {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Una canción.\nTodos a tiempo.", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
            Text("Crea una sala para compartir tu música o únete con el código de otro teléfono.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = create, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Crear sala") }
            HorizontalDivider()
            OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("Código de sala") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { join(code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Unirse a sala") }
            if (restore != null) TextButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("Recuperar la última sala") }
        }
    }
}

@Composable private fun TrackCard(state: AppState, pick: () -> Unit, coordinator: AppCoordinator) {
    Card {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Archivo musical", style = MaterialTheme.typography.titleMedium)
            state.manifest?.let { manifest ->
                Text(manifest.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (manifest.artist.isNotBlank()) Text(manifest.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${time(manifest.durationMs)} · ${"%.2f".format(manifest.size / 1048576.0)} MiB · ${manifest.mime}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { state.completedBlocks.toFloat() / manifest.blockCount }, modifier = Modifier.fillMaxWidth())
                Text("${state.completedBlocks}/${manifest.blockCount} bloques locales · ${transferLabel(state.transferState)}", style = MaterialTheme.typography.labelMedium)
            } ?: Text(if (state.role == Role.SERVER) "Elige un MP3 o M4A/AAC de hasta 5 MiB." else "El servidor seleccionará la canción.")
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.role == Role.SERVER) {
                OutlinedButton(onClick = pick, enabled = !state.busy && !state.playing && state.transferState != TransferState.SCHEDULED, modifier = Modifier.fillMaxWidth()) { Text("Seleccionar archivo local") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = coordinator::distribute, enabled = state.localReady && !state.busy && state.transferState != TransferState.CANCELED, modifier = Modifier.weight(1f)) { Text("Distribuir") }
                    OutlinedButton(onClick = coordinator::cancelTransfer, enabled = state.manifest != null, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                }
            }
        }
    }
}

@Composable private fun PlayerCard(state: AppState, coordinator: AppCoordinator) {
    var target by remember(state.manifest?.id) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reproducción compartida", style = MaterialTheme.typography.titleMedium)
            Text(if (state.localReady) "Audio verificado y preparado" else "Esperando archivo verificado")
            if (state.role == Role.NODE) Text(if (state.clockReady) "Reloj sincronizado · RTT ${"%.1f".format(state.rttMs)} ms" else "Calibrando reloj con el servidor", style = MaterialTheme.typography.bodySmall)
            val duration = (state.manifest?.durationMs ?: 1).coerceAtLeast(1).toFloat()
            Slider(value = (if (dragging) target else state.positionMs.toFloat()).coerceIn(0f, duration), onValueChange = { dragging = true; target = it },
                onValueChangeFinished = { coordinator.command(PacketType.SEEK_AT, target.toLong()); dragging = false }, valueRange = 0f..duration,
                enabled = state.role == Role.SERVER && state.localReady)
            Text("${time(state.positionMs)} / ${time(duration.toLong())}", style = MaterialTheme.typography.labelMedium)
            if (state.role == Role.SERVER) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { coordinator.command(PacketType.PLAY_AT) }, enabled = state.canPlay && state.transferState != TransferState.SCHEDULED) { Text("Reproducir") }
                    OutlinedButton(onClick = { coordinator.command(PacketType.PAUSE_AT) }, enabled = state.localReady) { Text("Pausa") }
                    TextButton(onClick = { coordinator.command(PacketType.STOP) }, enabled = state.localReady) { Text("Detener") }
                }
                Text(if (state.canPlay) "Los nodos obligatorios están listos. Inicio con 4 s de anticipación." else "Todos los nodos obligatorios deben estar listos. Puedes excluir uno desde su tarjeta.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun InfoCard(title: String, description: String) {
    Card { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
private fun peerName(id: Long, state: AppState): String = if (id == state.room.root) "Servidor" else "Nodo ${state.room.peers.keys.filter { it != state.room.root }.sorted().indexOf(id) + 1}"
private fun time(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
private fun transferLabel(state: TransferState) = when (state) {
    TransferState.IDLE -> "En espera"; TransferState.DISCOVERING -> "Descubriendo"; TransferState.CONNECTING -> "Conectando"
    TransferState.OFFERED -> "Ofrecido"; TransferState.ACCEPTED -> "Aceptado"; TransferState.TRANSFERRING -> "Transfiriendo"
    TransferState.PAUSED -> "Pausado"; TransferState.RESUMING -> "Reanudando"; TransferState.VERIFYING -> "Verificando"
    TransferState.READY -> "Listo"; TransferState.SCHEDULED -> "Programado"; TransferState.PLAYING -> "Reproduciendo"
    TransferState.COMPLETED -> "Completado"; TransferState.CANCELED -> "Cancelado"; TransferState.FAILED -> "Error"
}

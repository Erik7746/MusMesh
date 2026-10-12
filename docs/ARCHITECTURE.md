# Arquitectura

## Separación de responsabilidades

| Capa | Implementación | Responsabilidad |
|---|---|---|
| app | `AppCoordinator`, `AppState`, `MusMeshApplication` | Sesión de sala, StateFlow de UI, estado autoritativo, preparación y órdenes |
| ble | `BleScanner`, `BleAdvertiser`, `BleGattClient`, `BleGattServer`, `BleLinkManager` | Descubrimiento simultáneo, conexión y MTU reales |
| ble | `BleSendQueue`, `LinkFramer`, `LinkAssembler`, `MtuManager` | Una operación pendiente por enlace, callbacks, prioridad y límites |
| mesh | `MeshRouter`, `MeshTopology`, `DistributionTree`, `AdmissionGuard`, `PacketDeduplicator` | Identidad, admisión, sesiones, grafo y distribución |
| protocol | `PacketCodec`, `MeshPacket`, `WireReader`, `ControlPacket`, `RoomAuthenticator` | Envelope binario autenticado y decodificación acotada |
| transfer | `TransferManager`, `BlockSender`, `BlockReceiver`, `AckWindow`, `TransferSession` | Store-and-forward, ACK, reintentos y máquina de estados |
| transfer/data | `TransferRepository`, `AppDatabase` | Archivo privado, bitmap durable, salas, identidad y participantes |
| audio | `LocalMusicPicker`, `TrackMetadataReader`, `MusicPlayer`, `LocalMusicPlayer` | SAF, copia, inspección del contenedor y Media3 local |
| sync | `ClockSynchronizer`, `PlaybackScheduler`, `DriftCorrector` | Reloj monotónico, programación, compensación y deriva |
| service | `MusMeshForegroundService`, `ForegroundPolicy` | Vida en segundo plano, notificación, wakelocks temporales |
| ui | `MusMeshScreen` y tema Compose | Rol, sala, progreso, rutas y controles en español |

Algunas clases pequeñas relacionadas comparten archivo; no hay módulos ajenos a música, BLE y validación.

## Flujo vertical

1. El servicio inicia GATT server y advertising, más escaneo filtrado por UUID. El anuncio tiene un identificador lógico y un filtro corto de código; la autenticación real ocurre dentro del protocolo.
2. El mayor Node ID inicia el enlace para evitar conexiones recíprocas. Cliente: MTU → discovery → CCCD de indicaciones → HELLO. Servidor: CCCD válida → enlace listo.
3. HELLO/ACK usan desafíos de enlace. ROOM_JOIN añade un desafío de la raíz antes de instalar una sesión. Solo la raíz admite participantes; máximo ocho incluyendo ella.
4. Los participantes reportan vecinos. La raíz construye un árbol sobre enlaces confirmados por ambos extremos, con dos hijos y cuatro saltos como máximos. Evalúa RSSI, errores, estabilidad y conservación de padres previos; calcula alternativa si es viable. El control de topología se renueva periódicamente.
5. SAF entrega un URI temporalmente autorizado. La app copia con buffer de 32 KiB, comprueba límite/espacio, cabecera y pistas de MediaExtractor, rechaza video/DRM/codecs ajenos, extrae metadatos y SHA-256. Media3 usa exclusivamente `FileDataSource` sobre la copia privada.
6. Cada padre ofrece el manifiesto a sus hijos. El hijo acepta/preasigna, responde con hash y bitmap durable. El padre envía solo bloques faltantes.
7. El receptor reconstruye como máximo un bloque de 32 KiB, verifica SHA-256, escribe por posición, hace `fsync`, guarda bitmap y entonces confirma persistencia. Puede reenviar ese bloque mientras recibe el siguiente. Hay como máximo dos emisores por nodo, uno por hijo.
8. Con todos los bloques se verifica SHA-256 completo. Solo entonces se prepara Media3, pausado. READY requiere decoder preparado y reloj calibrado.
9. La raíz programa PLAY/PAUSE/SEEK/STOP con cuatro segundos de margen. La secuencia de comando impide aplicar órdenes viejas; hay ACK de aceptación y de ejecución. Los estados de reproducción reportados permiten detectar nodos que necesitan reincorporación.

## Persistencia equivalente a Room

Se usa un repositorio de archivos transaccionales pequeños en vez de añadir Room/KSP al proyecto base. Cada transferencia tiene un `.audio` preasignado y dos snapshots `.0.state`/`.1.state`, alternados por generación, con CRC y `fsync`. Los snapshots contienen manifiesto, bitmap de bloques y estado de verificación. El snapshot más reciente íntegro gana; una escritura truncada retrocede conservadoramente al anterior. No se promete durabilidad frente a un dispositivo de almacenamiento que ignore `fsync`.

La identidad de instalación y la sala se guardan en SharedPreferences atómicas privadas; también participantes, obligatoriedad, padre, transferencia activa y compensación de salida. El grafo vivo se reconstruye, nunca se confía en rutas antiguas después de reiniciar. Los nombres en disco se derivan exclusivamente de UUID parseados. Los nombres de canciones no definen rutas.

No se conservan fragmentos parciales: tras cambiar de padre se vuelve a solicitar ese bloque, manteniendo todos los completos. Los archivos cancelados/fallidos distintos del activo pueden limpiarse al importar otra canción; no se eliminan automáticamente transferencias pausadas.

## Concurrencia, límites y energía

- El transporte, el estado observable y Media3 se confinan al dispatcher Main. Disco y hashes de archivos usan IO; la búsqueda de árbol usa Default.
- Cada enlace tiene cola de 1.024 elementos/64 KiB; los datos solo pueden ocupar 768/48 KiB, dejando reserva para control. Un callback faltante cierra la generación del enlace: no se inicia otra operación sobre un GATT de estado incierto.
- Reintentos GATT: hasta cuatro intentos por operación. Transferencia: ocho reintentos por fragmento, timeout 800–8.000 ms adaptado al RTT de ACK, ventana inicial 16, rango adaptativo 2–32.
- Buffer de entrada de bloques: 64 mensajes; si se llena se descarta el fragmento y la retransmisión selectiva lo recupera. Reensamblado de enlace: cuatro mensajes, máximo 6.227 bytes cada uno, caducidad 15 s.
- La cola de datos nunca prueba entrega. El progreso aumenta por bits completos del receptor; las métricas GATT se muestran separadamente.
- Perfiles de escaneo: idle balanceado 10 s/20 s; transferencia low-latency 12 s/18 s. Las conexiones ya establecidas continúan durante las pausas de escaneo.
- Foreground de tipo connectedDevice, con mediaPlayback cuando hay reproducción programada. Un wakelock de 20 s se adquiere inmediatamente al programar o trabajar y se renueva solo mientras es necesario. Media3 gestiona su wakelock durante audio. Al desconectarse una ruta, cancelar o cerrar, se liberan los recursos correspondientes.

## Sincronización

`SystemClock.elapsedRealtimeNanos()` es el único reloj para plazos y muestras. `offset = ((t2-t1)+(t3-t4))/2`, expresado como reloj del nodo menos reloj del servidor. Se requieren tres muestras; se retienen veinte y se usa la mediana de hasta cinco de menor RTT. Se rechazan RTT negativos, mayores de 1,5 s o anómalos respecto al mejor. Las estimaciones se renuevan y caducan a los 30 s.

Tiempo local de ejecución: `serverTime + offset - outputOffset`. El diagnóstico de retraso del planificador mide la llamada a Media3, **no** la llegada acústica al altavoz. La corrección de deriva usa 0,995–1,005x durante cuatro segundos o seek por encima de 250 ms, con cooldown de cinco segundos. Esto exige calibración física y puede producir artefactos en un seek grande.

## Seguridad y evolución

El MVP usa HMAC-SHA256 truncado a 128 bits, con clave derivada del código de seis cifras, Room ID aleatorio, Node ID estable, sesión aleatoria por ejecución y ventana de secuencias. CRC detecta corrupción accidental antes de autenticar. Hash completo y por bloque son SHA-256.

El código corto evita incorporación accidental; **no equivale a identidad pública verificada, PAKE ni resistencia fuerte a fuerza bruta offline**. Todos los miembros conocen la misma clave y pueden suplantar a otro miembro. El archivo no se cifra. `FrameAuthenticator` permite sustituir la autenticación; para Noise habrá que negociar claves y actualizar el protocolo, no solo cambiar una constante.

`AudioNormalizer` reserva la interfaz para una futura transcodificación local AAC 64/96 kbps. No se aplica normalización en el MVP. No se incorporó código fuente de bitchat.

## Referencias de plataforma

- [Android: BLE en segundo plano](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [BluetoothGatt: MTU, callbacks y operaciones](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)
- [Media3: releases y compatibilidad](https://developer.android.com/jetpack/androidx/releases/media3)

Se mantiene la versión mínima del proyecto. El protocolo es una mesh de aplicación sobre GATT; no es el perfil Bluetooth SIG Mesh ni es interoperable con bitchat.

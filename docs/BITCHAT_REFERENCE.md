# Referencia de bitchat

El proyecto bitchat se consultó en modo lectura. No se modificó ni se compiló su repositorio. No se copiaron clases ni fragmentos de su implementación a MusMesh. El wire protocol es nuevo y no interoperable con bitchat.

## Archivos consultados

Las rutas siguientes son relativas a la raíz de bitchat:

- `AGENTS.md`
- `docs/file_transfer.md`
- `docs/LIVE_VOICE_V1.md`
- `app/src/main/java/com/bitchat/android/mesh/BluetoothConnectionManager.kt`
- `app/src/main/java/com/bitchat/android/mesh/BluetoothConnectionTracker.kt`
- `app/src/main/java/com/bitchat/android/mesh/BluetoothGattClientManager.kt`
- `app/src/main/java/com/bitchat/android/mesh/BluetoothGattServerManager.kt`
- `app/src/main/java/com/bitchat/android/mesh/BluetoothPacketBroadcaster.kt`
- `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt`
- `app/src/main/java/com/bitchat/android/mesh/PeerManager.kt`
- `app/src/main/java/com/bitchat/android/mesh/MeshCore.kt`
- `app/src/main/java/com/bitchat/android/mesh/PowerManager.kt`
- `app/src/main/java/com/bitchat/android/protocol/BinaryProtocol.kt`
- `app/src/main/java/com/bitchat/android/mesh/FragmentManager.kt`
- `app/src/main/java/com/bitchat/android/mesh/FragmentingPacketSender.kt`
- `app/src/main/java/com/bitchat/android/mesh/PacketProcessor.kt`
- `app/src/main/java/com/bitchat/android/mesh/PacketRelayManager.kt`
- `app/src/main/java/com/bitchat/android/mesh/MeshPacketUtils.kt`
- `app/src/main/java/com/bitchat/android/mesh/StoreForwardManager.kt`
- `app/src/main/java/com/bitchat/android/services/meshgraph/RoutePlanner.kt`
- `app/src/main/java/com/bitchat/android/util/AppConstants.kt`
- `app/src/main/java/com/bitchat/android/features/voice/VoiceBurstPacket.kt`
- `app/src/main/java/com/bitchat/android/features/voice/PttAudioPlayer.kt`
- `app/src/main/java/com/bitchat/android/features/voice/LiveVoiceManager.kt`

Se inspeccionaron las estructuras y métodos relevantes, con lecturas de las rutas de callbacks, límites, fragmentación, relay, cola, persistencia y reproducción; no se importó el árbol completo del proyecto.

## Ideas y diferencias

| Referencia conceptual | Implementación propia en MusMesh |
|---|---|
| Advertising y scanning simultáneos; central/peripheral | Managers BLE separados, selección determinista del iniciador por Node ID |
| Identidad lógica y generación de conexión | Node ID persistente, UUID por enlace, desafíos y sesión por proceso |
| MTU y callbacks GATT | Negotiation antes de CCCD; colas con una operación y timeout que cierra enlace |
| Cola por enlace, bytes pendientes, reintentos | Prioridades P0/P1/P2 y reserva de capacidad para controles |
| Decoder binario limitado | Envelope propio CRC+HMAC, límites antes de reservar payload y rechazo de entradas inválidas |
| Fragmentación y cancelación de jobs | Fragmentos de bloques según MTU, cancelación estructurada, reensamblado pequeño de enlace |
| Duplicados y TTL | Ventana por identidad/sesión/secuencia y broadcasts acotados de control |
| RoutePlanner y store-forward | Árbol de distribución de grado dos y relay de bloques durables; sin flooding de audio |
| Power profiles y desconexiones | Escaneo con ciclos, foreground service, recursos acotados y reanudación persistente |
| Ordenamiento de audio y jitter | Separación entre recepción y reproducción; preparación completa y plazos monotónicos |

Se descartaron de forma explícita el archivo entero dentro de un único payload, el fragmento fijo de 469 bytes, la indicación de progreso basada en encolado, el relay probabilístico de todos los datos y el uso de reloj civil para sincronización. Las clases de voz se usaron para razonar sobre buffers limitados, secuencias y limpieza; no se implementó voz en vivo ni se copió su reproductor.

No se incorporaron Wi-Fi Aware, Nostr, Tor, geohash, IRC, chats, imágenes transmitidas ni Wear OS. SHA-256, HMAC, GATT, coroutines, StateFlow y las ecuaciones NTP se implementaron con APIs estándar, sin extraer código de negocio de bitchat.

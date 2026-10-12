# MusMesh BLE protocol v1

Todos los enteros usan big-endian salvo los bytes de bitmap, que siguen `BitSet.toByteArray`: bit 0 es el menos significativo del byte 0. UUID son dos campos de 64 bits. Node IDs/sesiones/Packet IDs son aleatorios positivos de 63 bits. Node ID cero es broadcast controlado; UUID cero solo se permite durante descubrimiento inicial.

## Envelope

| Offset | Bytes | Campo |
|---:|---:|---|
| 0 | 2 | Magic `0x4D4D` |
| 2 | 1 | Versión `1` |
| 3 | 1 | Tipo |
| 4 | 8 | Packet ID aleatorio |
| 12 | 8 | Sender Node ID lógico |
| 20 | 8 | Destination Node ID, o cero |
| 28 | 16 | Room ID |
| 44 | 8 | Session ID |
| 52 | 8 | Secuencia positiva de paquete |
| 60 | 1 | TTL, máximo 4 |
| 61 | 2 | Longitud de payload, máximo 6.144 |
| 63 | N | Payload |
| 63+N | 4 | CRC32 de header y payload |
| 67+N | 16 | Primeros 16 bytes de HMAC-SHA256 de header+payload+CRC |

Overhead total: **83 bytes**; frame máximo: **6.227 bytes**. La clave HMAC es SHA-256 de UTF-8 `MusMesh/v1/` concatenado con el código de sala. El decoder valida longitud exacta, versión, tipo, TTL, IDs, CRC y HMAC antes de entregar payload. Payloads malformados se descartan sin excepciones remotas hacia el llamador. No hay compresión ni asignaciones del tamaño de un archivo.

Se requiere sesión registrada para tráfico normal. La ventana de replay admite reordenamiento de hasta 4.096 secuencias y rechaza repeticiones; está limitada a 32 sesiones observadas. Al instalar una nueva sesión demostrada se retira la anterior de ese participante.

## GATT y subfragmentación de enlace

- Servicio: `42dd41d0-b754-41a8-9108-df29226e2b10`.
- Característica WRITE con respuesta + INDICATE: `42dd41d1-b754-41a8-9108-df29226e2b10`.
- CCCD estándar `0x2902`; se exige `ENABLE_INDICATION_VALUE`. No se admiten prepared writes ni offsets de escritura GATT.
- Advertising legacy: UUID de servicio; scan response: Node ID (8) y dos bytes de SHA-256 del código. No transmite nombre de dispositivo ni MAC dentro del protocolo.
- MTU se solicita a 517, pero solo se usa el realmente notificado. Se parte de **MTU 23**, sin suponer que 180 bytes útiles caben antes de negociar.
- Capacidad ATT: `min(mtu - 3, 512)`. Cada valor ATT tiene subheader `messageId:u32, offset:u16, total:u16`, seguido de bytes. Reensamblado con límites, control de solapamiento y expiración de 15 s.
- Tamaño útil BLOCK_DATA: `max(16, min(512, ATTcapacity - 8 - 83 - 56))`. Ejemplo MTU 517: 365 bytes; MTU 247: 97 bytes. Si el envelope no cabe (MTU 23, por ejemplo), un fragmento lógico de 16 bytes se subfragmenta en varias operaciones ATT. Es compatible pero muy lento.

Los relays reconstruyen, verifican y persisten un bloque antes de volver a fragmentarlo para la salida. BLOCK_DATA nunca se enruta ciegamente a través de un enlace intermedio.

## Tipos

| Valor | Tipo | Payload / uso |
|---:|---|---|
| 1, 2 | HELLO, HELLO_ACK | root ID, root session, desafío propio, eco del desafío remoto: cuatro u64. HELLO_ACK debe responder al desafío de esta generación de enlace |
| 3 | ROOM_JOIN | fase u8 (0 solicitud, 1 desafío raíz, 2 respuesta), nonce de raíz u64, sesión objetivo u64, desafío de cliente/eco u64. Ambos extremos exigen respuesta vigente (15 s) antes de instalar una sesión |
| 4 | ROOM_ACCEPT | número u8 y miembros `{node:u64, session:u64, required:u8}`, máximo ocho, sin repetidos |
| 5 | ROOM_REJECT | Vacío; capacidad de sala agotada |
| 6 | PEER_INFO | cantidad u8 + vecinos `{id:u64, rssi:i32, errors:u16, stableSeconds:i32}`; máximo siete |
| 7, 8 | TOPOLOGY_UPDATE, PARENT_ASSIGN | versión u64, cantidad u8, entradas `{child:u64, parent:u64, alternate:u64}`; alternativa cero si no existe |
| 9, 10 | PING, PONG | Payload acotado que PONG refleja; diagnóstico de liveness |
| 11 | MUSIC_OFFER | Manifiesto descrito abajo |
| 12, 17 | MUSIC_ACCEPT, RESUME_REQUEST | transfer UUID, SHA-256 esperado (32), longitud bitmap u16, bitmap de bloques completos (máximo 20 bytes) |
| 13 | MUSIC_REJECT | Transfer UUID; rechazo local de la oferta |
| 14 | BLOCK_DATA | Transfer UUID, bloque u16, índice u16, total de fragmentos u16, chunkSize u16, SHA-256 de bloque (32), datos |
| 15, 16 | BLOCK_ACK, BLOCK_NACK | Transfer UUID, bloque u16, cantidad u16, persisted u8, longitud bitmap u16, bitmap de fragmentos (hasta 256 bytes) |
| 18 | TRANSFER_CANCEL | Transfer UUID; solo se aplica si proviene de la raíz y coincide con el activo |
| 19 | TRANSFER_COMPLETE | Transfer UUID; aviso del padre. No sustituye la verificación del receptor |
| 20, 21 | FILE_VERIFIED, FILE_INVALID | Transfer UUID y hash completo (32) |
| 22 | CLOCK_SYNC_REQUEST | t1:u64 monotónico de servidor |
| 23 | CLOCK_SYNC_RESPONSE | modo 0: t1,t2,t3 (u64); modo 1: offset:i64, RTT:u64, calibrado:u8 |
| 24 | PLAYER_READY | Transfer UUID, SHA-256 (32), listo:u8. Listo requiere hash, decoder y reloj |
| 25–28 | PLAY_AT, PAUSE_AT, SEEK_AT, STOP | ControlPacket: Transfer UUID, commandSequence:u64, atNanos:u64, positionMs:u64, playing:u8 |
| 29 | ERROR | Reservado para error acotado de protocolo; los errores locales actuales se muestran sin reenviar textos privados |
| 30 | COMMAND_ACK | Transfer UUID, commandSequence:u64, executed:u8, dispatchSkewNanos:i64 |
| 31 | PLAYBACK_STATUS | ControlPacket con instante de observación y estado actual; servidor autoritativo y reporte periódico de nodo |
| 32 | TRANSFER_PROGRESS | Transfer UUID, bloques durables u16, total u16; dirigido a raíz |

Los tipos 8, 12 y 29 están definidos para evolución/interoperabilidad interna: v1 transmite normalmente 7 y 17, y muestra errores localmente. No hay compatibilidad de wire con bitchat.

## Manifiesto y bloques

Manifiesto: `transfer:UUID, size:i32, fullSHA256:32, durationMs:i64, title:text, artist:text, mime:text, count:u16, blockSHA256[count]:32`. Texto: longitud UTF-8 u16 y bytes; título/artista máximo 160 bytes; MIME máximo 40. Duración 1–86.400.000 ms; tamaño 1–5.242.880 bytes; count debe ser exactamente `ceil(size/32768)` y nunca superar 160. MIME permitido: audio/mpeg, audio/mp4, audio/aac. Los metadatos remotos no determinan rutas ni ejecutan acciones.

Un bloque mide 32.768 bytes salvo el último. La cantidad e índices de fragmentos se verifican contra el tamaño real del bloque y el chunkSize; no basta confiar en un total remoto. Máximo 2.048 fragmentos por bloque.

En ACK, un bit significa fragmento recibido en el bloque parcial, **todavía no entrega durable**. `persisted=1` solo es válido con todos los bits y se emite después de SHA-256 + escritura + fsync + snapshot. El emisor solo incrementa progreso al recibir este último ACK. El receptor responde nuevamente si se repite el fragmento final por pérdida del ACK. NACK vacío invalida el bloque parcial y hace repetir sus fragmentos con reintentos acotados.

RESUME no incluye bloque parcial: esa optimización está deliberadamente deshabilitada. Sí incluye todos los bloques íntegros persistidos. Un cambio de MTU, ruta o padre no reinicia el archivo.

## Rutas, prioridades y órdenes

Los broadcasts permitidos son ROOM_JOIN, ROOM_ACCEPT, PEER_INFO, TOPOLOGY_UPDATE, PARENT_ASSIGN y TRANSFER_CANCEL. Son mensajes pequeños de control, con TTL, deduplicación y renovación periódica. Los datos de audio van exclusivamente al hijo asignado; hasta dos emisores simultáneos por nodo.

- P0: topología, cancelación, reproducción/pausa/seek/stop. Desconectar cancela directamente la cola.
- P1: ACK, reloj, READY, heartbeat y estado.
- P2: bloques y manifiestos de archivo. Un manifiesto grande también cede ante controles.

La cola puede intercalar subfragmentos de distinta prioridad y el reensamblador los distingue por messageId. Cada operación espera callback; aceptar `writeCharacteristic`/`notifyCharacteristicChanged` nunca confirma un bloque.

PLAY exige todos los nodos marcados obligatorios en READY. La raíz reintenta órdenes hasta cuatro veces antes del plazo y avisa de falta de ACK. Esto **no es un protocolo de commit distribuido atómico**: una partición después de READY puede permitir que algunos nodos ejecuten y otros no. La UI lo debe tratar como un fallo de sincronización, no como garantía de ejecución conjunta.

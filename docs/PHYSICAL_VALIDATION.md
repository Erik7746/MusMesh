# Validación física: 5–8 teléfonos

Estado inicial: **pendiente**. Las simulaciones y una prueba instrumentada en un solo teléfono no certifican ninguno de estos ocho escenarios.

## Preparación y evidencia

1. Usar 5–8 teléfonos, idealmente de al menos tres fabricantes, incluyendo Android 7–11 y Android 12+. Etiquetarlos **S, A, B, C, D, E, F, G**; no registrar números de serie, MAC, nombres personales ni coordenadas.
2. Instalar la misma APK debug. Tener batería suficiente, Bluetooth LE advertising soportado y almacenamiento libre. Conceder permisos y notificaciones. En Android <=30 activar el ajuste de ubicación si el escaneo lo requiere; la app no lee ubicación.
3. Desactivar Wi-Fi y datos móviles durante los escenarios. No usar altavoces Bluetooth externos: añaden otra latencia y no forman parte de esta prueba inicial.
4. Generar archivos sintéticos MP3/M4A sin metadatos personales. Por ejemplo, con FFmpeg instalado en un equipo de laboratorio:

```text
ffmpeg -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=140" -c:a aac -b:a 64k -map_metadata -1 synthetic-1m.m4a
ffmpeg -f lavfi -i "sine=frequency=660:sample_rate=44100:duration=390" -c:a aac -b:a 64k -map_metadata -1 synthetic-3m.m4a
```

Comprobar tamaños: primer archivo **al menos 1 MiB**, segundo **al menos 3 MiB**, ambos <=5 MiB. Ajustar duración si el encoder produce menos. Para medir inicios usar una señal sintética con impulsos cortos, silencio entre impulsos y un primer impulso conocido; no usar voz personal ni música protegida. Los comandos anteriores producen tonos continuos y sirven para transferencia, no son el patrón ideal de medición acústica.

5. Crear sala en S, unir los demás con el código. Esperar hasta 90 s por los ciclos de escaneo. Confirmar roster, rutas, máximo dos hijos/cuatro saltos. Mantener la evidencia local.
6. Para cada escenario registrar: versión APK, versión Android por etiqueta, archivo/tamaño/SHA-256 sintéticos, MTU, fragmento útil, árbol inicial/final, bloques confirmados, B/s, reintentos, errores, RTT/offset, estado READY y resultado. No copiar logs crudos a servicios externos.

Plantilla de resultado:

| Escenario | Etiquetas / versiones | Tamaño / hash sintético | Duración | Errores / reintentos | Resultado y evidencia local |
|---|---|---|---|---|---|
| 1–8 | | | | | Pendiente |

## 1. Todos dentro de alcance, 1 MiB

- Ubicar todos cerca de S. Verificar descubrimiento y admisión de 5–8 miembros. Los enlaces físicos pueden ser directos, pero el **árbol de datos sigue limitado a dos hijos por padre**.
- Elegir archivo >=1 MiB, distribuir y esperar todos los READY.
- Confirmar que cada progreso solo avanza por bloques persistidos y llega a 100%; comprobar reproducción local en todos.
- Durante transferencia enviar una orden STOP después de que algún nodo ya esté preparado; observar que los controles no esperan la cola de datos. Antes de READY no se permite PLAY.
- Aprobación: mismo hash en cada receptor, sin caída, sin Internet y sin crecimiento de memoria proporcional al archivo completo.

## 2. Dos saltos, 3 MiB

- Formar físicamente enlaces S↔A y A↔B, evitando S↔B mediante atenuación o separación del laboratorio. No registrar ubicación real.
- Confirmar en UI ruta de B con dos saltos. Incorporar los demás sin superar límites del árbol.
- Distribuir >=3 MiB. Observar que A empieza a reenviar bloques completos mientras sigue recibiendo otros.
- Aprobación: B llega a READY sin enlace directo a S; misma integridad en todos; no hay flooding de fragmentos.

## 3. Desconectar al 50% y reanudar

- En un nodo con aproximadamente la mitad de bloques confirmados, desactivar Bluetooth durante 30 s. Anotar el número durable.
- Reactivarlo. Esperar descubrimiento/topología y reanudación. Repetir matando/reabriendo la app y pulsando Recuperar la última sala.
- Aprobación: se conservan los bloques previos, solo se reenvían faltantes y, como máximo, el bloque parcial. El hash final coincide.
- No confundir **Cancelar** con desconexión: Cancelar es terminal y requiere una nueva selección/oferta de archivo.

## 4. Perder padre intermedio

- Elegir un relay con al menos un hijo y una ruta física alternativa viable. Distribuir y apagar Bluetooth del relay.
- Esperar propagación del fallo y reconstrucción (presupuesto inicial: <=90 s, por escaneo y temporizadores).
- Aprobación: la raíz reasigna padre sin ciclo, respeta grado/profundidad y el hijo conserva su bitmap. Si no hay enlace alternativo viable, debe esperar, no fingir progreso.

## 5. MTU pequeño

- Incluir un dispositivo que realmente negocie MTU pequeño; registrar el MTU mostrado, no el solicitado.
- Forzar un recorrido desde un enlace mayor a uno menor, transferir y comparar hash.
- Aprobación: el relay refragmenta; ningún ATT supera `min(MTU-3,512)` y los controles siguen pasando.
- Si el hardware siempre negocia 517, registrar este escenario como **no ejecutado físicamente**. Las pruebas JVM cubren MTU 23/64/185/247/517, pero no sustituyen este ensayo.

## 6. Excluir nodo lento

- Hacer lenta la recepción de un nodo por atenuación o MTU menor. Esperar READY en los otros.
- Confirmar PLAY deshabilitado mientras sigue obligatorio. Apagar su interruptor de obligatoriedad.
- Aprobación: S puede programar a los listos; el nodo lento conserva la transferencia y puede incorporarse al quedar listo. Excluir de la espera no elimina su función de relay.

## 7. Medir inicio sincronizado

- Esperar READY y reloj estable en todos. Programar PLAY con cuatro segundos de margen.
- Medir con captura externa multicanal o un método acústico/video de alta velocidad que permita atribuir cada inicio a un teléfono. Mantener distancias equivalentes o corregir explícitamente propagación acústica (aprox. 2,9 ms por metro).
- Hacer al menos 20 arranques. Calcular por intento `máximo inicio − mínimo inicio`; registrar mediana, p95 y máximo. El objetivo inicial es <100 ms, pendiente de demostración.
- Ajustar manualmente Compensación de salida por etiqueta, repetir y anotar tanto valores como metodología. No calibrar con el retraso del planificador: ese dato no mide el altavoz.
- Repetir con pantallas apagadas y a los 1, 5 y 10 minutos para deriva. No afirmar precisión a partir de logs de llamadas a `play()`.

## 8. Pausa, seek y reanudación

- Ejecutar PLAY, PAUSE_AT, SEEK_AT pausado, PLAY y SEEK_AT reproduciendo. Todas las órdenes tienen secuencia y plazo futuro.
- Observar ACK y estado autoritativo. Repetir con un nodo desconectado y reincorporarlo durante reproducción.
- Medir acústicamente discontinuidades/posiciones y anotar correcciones de velocidad/seek. Probar STOP y una orden nueva mientras existe otra programada: solo la secuencia más reciente debe ejecutarse.
- Aprobación: comandos viejos no cambian estado, los nodos solo usan archivos verificados y el nodo recuperado sigue al servidor.

## Ensayos adicionales de errores y segundo plano

- Denegar cada permiso; apagar Bluetooth; negar notificaciones; archivo >5 MiB; MIME falso; contenedor inválido; archivo con DRM; poco espacio; bloque corrupto; cola llena; callback perdido; pérdida del ACK final.
- Mantener transferencia con pantalla apagada y app fuera de primer plano. Observar notificación, finalizar con Cancelar/Cerrar sala y confirmar cese de radio/wakelock.
- Probar recreación del servicio y proceso. No esperar que Android reinicie una app a la que el usuario aplicó **Forzar detención**: abrirla de nuevo y recuperar sala.
- Algunos fabricantes imponen restricciones adicionales de batería. Registrar fabricante por etiqueta y el ajuste usado; no dar por superado el ensayo por la mera existencia del foreground service.

## Criterio de cierre

Separar siempre: **JVM simulado**, **instrumentado en un teléfono**, **BLE entre dos teléfonos** y **acústico con 5–8 teléfonos**. Solo marcar aprobado lo ejecutado y respaldado por evidencia local. Si desaparece un dispositivo de ADB, repetir la prueba afectada; no contar una ejecución incompleta como éxito.

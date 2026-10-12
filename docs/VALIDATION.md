# Estado de validación

Fecha de trabajo: **12 de octubre de 2026**. Todo el código y la evidencia de herramientas permanecen dentro del proyecto o de sus directorios locales de build. No se hicieron commits ni publicaciones. El proyecto de referencia no se modificó.

## Resultados automatizados

| Verificación | Resultado |
|---|---|
| `gradlew.bat clean testDebugUnitTest assembleDebug lintDebug connectedDebugAndroidTest` | Compilación desde cero ejecutada correctamente |
| `gradlew.bat testDebugUnitTest` | 41 pruebas JVM; cero fallos |
| `gradlew.bat assembleDebug` | APK debug generada |
| `gradlew.bat lintDebug` | Sin errores; recomendaciones de versiones, estilo y plantilla |
| `gradlew.bat connectedDebugAndroidTest` | 5 pruebas aprobadas en un teléfono físico Android 13 |
| `git diff --check` | Sin errores de whitespace |

Hubo fallos intermedios y se corrigieron: compatibilidad de la llamada foreground con Core de la plantilla, firma de retorno de una prueba JUnit, coste de leer snapshots por cada fragmento y un caso de bootstrap cuando dos nodos se conectan antes de conocer la raíz. La admisión incluye desafíos de ambos extremos; se rechazaron respuestas de sala antiguas en tests.

Una segunda conexión Android apareció durante una tanda y quedó offline. Su ejecución incompleta **no** se cuenta como éxito ni como prueba BLE entre dos teléfonos. La tanda posterior aprobada se ejecutó en el dispositivo disponible.

## Qué cubren las pruebas

- `CoreTest`: envelope de todos los tipos, truncamientos, corrupción de cada byte, tamaños/versiones, código de sala incorrecto, fuzz acotado, MTU 23/64/185/247/517, reensamblado y expiración, manifiestos, bloques, SHA incorrecto, journal truncado, reanudación, bitmaps, NACK, retransmisión/timeout/reintentos, cancelación terminal, duplicados, árbol de ocho nodos, cambio de padre, estados, cálculo NTP, outliers y deriva.
- `FlowTest`: transferencia simulada de **1 MiB a cinco nodos en dos saltos**, pérdida intencional, fragmentos de salida menores, SHA final idéntico; reinicio/repositorio reabierto y continuación con otro padre sin reenviar la mitad completa; cola GATT con callback obligatorio, preempción de control, timeout, desconexión; ejecución de PLAY_AT usando tiempo virtual y offset.
- `AdmissionTest`: desafío de raíz válido, vencimiento, sesión incorrecta, respuesta repetida y eco de cliente caducado/completado.
- `ForegroundPolicyTest`: CPU inmediata al programar, relay después de READY, liberación al perder ruta/cancelar y no retener CPU solo por seleccionar archivo.
- `AndroidFlowTest`: política de permisos por versión, selector local, ausencia de INTERNET/RECORD_AUDIO en el manifiesto fusionado, tipos/exportación del servicio; generación **local** de AAC sintético, lectura por ContentResolver, copia privada, persistencia y preparación Media3 pausada; creación y cierre de sala mediante foreground service.
- `UiTest`: selección de rol visible en español. También se conserva la prueba instrumentada original del package de la aplicación.

Los tests de transferencia usan bytes sintéticos reproducibles y no pretenden decodificarlos como audio. El test instrumentado sí genera y prepara un M4A/AAC real mediante MediaCodec/MediaMuxer, sin micrófono y sin archivos personales.

## Evidencia local

- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- JVM: `app/build/reports/tests/testDebugUnitTest/index.html` y `app/build/test-results/testDebugUnitTest/`.
- Lint: `app/build/reports/lint-results-debug.html` y `.sarif`.
- Instrumentación: `app/build/reports/androidTests/connected/` y `app/build/outputs/androidTest-results/connected/`.

Los informes automáticos pueden incluir detalles del entorno/dispositivo; mantenerlos locales y no publicarlos sin sanitización. La app no emite logs propios con nombres, URIs, rutas o identificadores completos; los logs de Media3 se desactivan en builds release.

## Criterios que aún requieren validación física

| Criterio | Estado |
|---|---|
| Descubrimiento y conexión BLE entre dos teléfonos sin Internet | Implementado, no validado con una pareja estable |
| Transferir/verificar/reproducir >=1 MiB por radio real | Lógica simulada y decoder local comprobados por separado; flujo físico completo pendiente |
| Desconexión al 50%, reconexión y cambio de padre reales | Simulación aprobada; radio real pendiente |
| Relay de bloques por un teléfono intermedio | Simulación aprobada; hardware pendiente |
| Controles con radio saturada y MTU pequeño | Prioridad/MTU probados en simulación; hardware pendiente |
| Transferencia y reproducción con pantalla apagada/Doze | Servicio y política comprobados; prueba prolongada por fabricante pendiente |
| Cinco a ocho teléfonos simultáneos | No ejecutado físicamente |
| Desviación acústica de inicio <100 ms | Objetivo; no medida ni garantizada |

Por ello, esta entrega es un **MVP implementado y verificable**, no una certificación de que se han superado todos los criterios de aceptación en hardware.

## Limitaciones conocidas

1. El código de seis cifras, la autenticación compartida y los desafíos evitan cruces accidentales y replay de sesiones admitidas, pero no proporcionan identidad individual criptográfica ni resistencia fuerte a ataques offline. No hay cifrado del archivo. Un miembro con el código comparte la confianza del resto.
2. Es una mesh propia sobre GATT. El máximo práctico de enlaces simultáneos depende del controlador Android; los límites de ocho participantes y dos hijos no garantizan que cualquier modelo de teléfono sostenga la topología.
3. MTU 23 está soportado por subfragmentación, con rendimiento muy bajo. No se promete un tiempo de distribución fijo.
4. Se reanuda por bloque completo; hasta 32 KiB parciales se retransmiten. Una cancelación es terminal; para volver a distribuir un archivo cancelado se selecciona de nuevo, generando otro Transfer ID.
5. Solo hay una canción activa por sala. No hay colas de canciones, elección de nuevo servidor tras perder la raíz, transcodificación ni interoperabilidad con otras aplicaciones.
6. La reanudación del proceso prepara el archivo en pausa. No se reanuda automáticamente una reproducción de la raíz después de que su proceso muera. Los nodos recuperados pueden reincorporarse al estado de una raíz que sigue activa.
7. El planificador mide cuándo solicita reproducir, no cuándo el hardware emite sonido. Las diferencias de decoder, salida y gestión de energía necesitan calibración. El seek sincronizado tampoco garantiza latencia idéntica de decodificación.
8. READY es una barrera previa a PLAY, no un commit atómico distribuido. Una partición posterior a READY puede dejar una orden sin ejecutar en algún nodo; se muestran faltas de ACK y se reconcilia el estado cuando vuelve.
9. Las tasas de nodos remotos se estiman a partir de bloques reportados. Los contadores detallados de fragmentos/reintentos corresponden a los envíos locales hacia hijos; no se agregan todos los contadores de cada radio remota.
10. Si el fabricante rechaza advertising o impone restricciones de batería, la app muestra el fallo. Esos casos requieren pruebas en el fabricante afectado; un foreground service no elimina toda restricción OEM.

## Próximos pasos

1. Conectar dos teléfonos de forma estable y ejecutar selección → 1 MiB → SHA → READY → PLAY, primero cerca y luego con pantalla apagada.
2. Añadir un tercero para relay real y cambio de padre; revisar MTU, callbacks y RTT observados antes de ajustar ventanas/timeouts.
3. Ejecutar íntegramente [los ocho escenarios](PHYSICAL_VALIDATION.md) con 5–8 teléfonos y medir acústicamente los inicios y la deriva.
4. Ajustar latencia/energía según evidencia por fabricante. Después, considerar PAKE/Noise y autenticación individual, y la implementación de `AudioNormalizer` si el tiempo BLE lo justifica.

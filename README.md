# MusMesh

Aplicación Android para distribuir música local por Bluetooth Low Energy y reproducir copias verificadas con órdenes programadas. La misma APK crea una sala o se une como nodo. Interfaz en español, `minSdk 24`, `targetSdk 36`.

## Uso

1. Instala `app/build/outputs/apk/debug/app-debug.apk` en los teléfonos.
2. Activa Bluetooth y concede los permisos que solicita Android. En Android 6–11, el sistema puede requerir también activar ubicación para entregar resultados BLE; MusMesh no obtiene coordenadas.
3. En un teléfono elige **Crear sala**. En los demás, **Unirse a sala**, usando el código de seis cifras.
4. Espera a que se asigne una ruta. Selecciona un MP3, M4A/AAC local de hasta **5 MiB (5.242.880 bytes)** y pulsa **Distribuir**.
5. Espera **LISTO** en los nodos obligatorios. El interruptor de cada nodo permite excluirlo de esa espera; puede seguir siendo relay.
6. **Reproducir**, **Pausa**, **Detener** y la barra de posición programan órdenes con cuatro segundos de anticipación.
7. Tras reiniciar la app, usa **Recuperar la última sala**. Los bloques completos se conservan; un bloque parcial se vuelve a solicitar.

La notificación permite cancelar la transferencia o cerrar la sala. La APK final no solicita INTERNET, Wi-Fi ni micrófono. No hay streaming, transcodificación, cuentas ni servicios externos. Las dependencias de compilación se descargan mediante Gradle; eso no añade conectividad IP al funcionamiento de la aplicación.

## Implementación y verificación

- [Arquitectura y decisiones](docs/ARCHITECTURE.md)
- [Protocolo binario y límites](docs/PROTOCOL.md)
- [Referencia de bitchat y procedencia](docs/BITCHAT_REFERENCE.md)
- [Pruebas físicas con 5–8 teléfonos](docs/PHYSICAL_VALIDATION.md)
- [Estado de validación y limitaciones](docs/VALIDATION.md)

```powershell
# Usar un JDK 21; JAVA_HOME debe señalar una instalación local válida.
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
# Con un Android conectado y autorizado:
.\gradlew.bat connectedDebugAndroidTest
```

No se afirma precisión acústica inferior a 100 ms ni funcionamiento validado con 5–8 teléfonos físicos. Los tests JVM incluyen una transferencia simulada de 1 MiB por cinco nodos y dos saltos. Esto comprueba la lógica de distribución, no el comportamiento de las radios Android.

# Ultimate Gamepad Android — Etapa 1

Mando virtual multi-transporte para jugar en una **Hyundai Google TV** con **OpenNOW** (cliente no oficial y open-source de GeForce NOW, no la app oficial de NVIDIA).

Teléfono (MediaTek Helio G99) → TV Hyundai → OpenNOW.

> **Estado honesto — 2026-11-11 (ronda 2):** el mando existe de verdad (Fases 1–4 + Bluetooth dos vías) y compila en verde en CI (run #168). **Shizuku está DESCARTADO del todo** (no está adaptado a TV y la TV no tiene depuración de red): la única vía para que el juego vea un mando es el **Bluetooth HID directo del teléfono**; por Wi-Fi la TV recibe y muestra los paquetes en vivo, pero sin Shizuku no puede inyectarlos en el juego. **No está verificado en el teléfono/TV real todavía** (la prueba real es la del usuario). Lee `docs/VIABILITY-OPENNOW.md` antes de esperar que funcione en un juego.

## Criterio de éxito de la Etapa 0: que lo reconozca como si fuera un mando real

No basta con que "llegue el paquete" por Wi-Fi. El éxito es:

- Android en la TV lista un dispositivo llamado **"Xbox 360 Controller"** — nunca "Virtual", nunca "Ultimate Gamepad" como nombre de dispositivo.
- Identidad completa: vendor **0x045E**, product **0x028E**, bustype USB (uinput), mismo mapeo de botones/ejes que un Xbox 360 físico.
- `getSources()` incluye **GAMEPAD + JOYSTICK**.
- OpenNOW lo lista como controller y lo codifica a XInput como un mando físico.
- La pantalla de diagnóstico de la TV dice **"Parece mando real: SÍ"**.

Si un método no logra esa identidad, se marca como **FALLO** en la UI aunque el Wi-Fi funcione. Ver `docs/VIABILITY-OPENNOW.md` ("Por qué otras apps se ven como control de TV"). Matiz honesto (2026-11-11): por **Bluetooth HID** la TV ve el nombre "Xbox 360 Controller" y el descriptor de Game Pad, pero el VID/PID **no** se pueden fijar por la API HID (son los del teléfono); la identidad completa con VID/PID 0x045E/0x028E solo la da la ruta uinput de la TV, que hoy no tiene vía de inyección (Shizuku descartado). Lo que decide al final es la prueba en la TV real.

## Orden de prioridades

- **Fase 1 = enlace Wi-Fi en la misma red, visible en pantalla.** Primero que teléfono y TV se vean y que la TV muestre en vivo cada botón/stick.
- **Fase 2 = Bluetooth HID directo del teléfono** para que OpenNOW lo vea como mando real. **Shizuku / uinput está DESCARTADO** (2026-11-07): Shizuku no está adaptado a TV y la TV no tiene depuración de red ni inalámbrica, solo USB. No se reabre.
- **Multi-método:** todos los métodos posibles como fallback, por si uno falla. El modo Automático prueba en orden UDP → TCP → Bluetooth datos → Bluetooth HID.

## Transportes teléfono → TV

| # | Método | Prioridad | Qué necesita | Si falla | Estado Etapa 1 |
|---|---|---|---|---|---|
| 1 | Wi-Fi UDP, misma red | Principal | Misma Wi-Fi, TV con receptor en puerto 51999 | Pasa a TCP | Completo |
| 2 | Wi-Fi TCP, misma red | Respaldo | Misma Wi-Fi, puerto TCP 52000 | Pasa a Bluetooth datos | Completo (básico) |
| 3 | Hotspot del teléfono | Sin router | Hotspot del teléfono, TV conectada a él, IP 192.168.43.x | Volver a Wi-Fi con router | Mismo UDP/TCP, modo en UI + docs |
| 5 | Bluetooth datos (BLE/SPP) a la app receptora | Respaldo si falla todo el Wi-Fi | Emparejamiento BT, app receptora | Pasa a Bluetooth HID | Completo (SPP real, Fase 5) |
| 4 | Bluetooth HID, teléfono como mando real | Directo, sin receptora ni Shizuku | el teléfono expone HID_DEVICE, emparejar "Xbox 360 Controller" | Ninguno (último) | Completo: descriptor HID 486 B, 16 botones en orden Android, gatillos analógicos, rumble, escaneo BT desde el teléfono |

El modo Automático prueba en orden **1 → 2 → 5 → 4**. Se puede forzar un método manualmente. Ningún método se muestra "Conectado" sin paquetes reales.

## Inyección en la TV → OpenNOW

| Método | Qué es | Si falla | Estado 2026-11-11 |
|---|---|---|---|
| A. Shizuku + /dev/uinput, mando virtual Xbox 360 | Principal en el diseño original | — | **DESCARTADO**: Shizuku no está adaptado a TV; el código uinput sigue en la app de TV pero no tiene vía de inyección |
| B. Shizuku + `injectInputEvent`, SOURCE_GAMEPAD | Respaldo si uinput falla | — | **DESCARTADO** (mismo motivo) |
| C. Bluetooth HID | Inyección directa, no necesita Shizuku | Ninguno | **Principal y único camino real**: el teléfono expone el mando por HID; la TV lo empareja como "Xbox 360 Controller" |

## Cómo funciona (resumen)

1. La TV muestra su IP, puertos (UDP 51999 / TCP 52000) y un PIN, o un QR `UGP1|<ip>|51999|<pin>`.
2. El teléfono escanea el QR (CameraX + ML Kit, real) o escribe IP/PIN, y envía el estado del mando a 120 Hz por UDP (o TCP) en paquetes binarios de 32 bytes (`:core`, ver `docs/ARCHITECTURE.md`).
3. La TV recibe los paquetes en un servicio en primer plano, los muestra en vivo (botones, sticks, último paquete, paquetes recibidos) y hace eco de ping. Por Wi-Fi la TV **recibe** pero **no inyecta** en el juego (sin Shizuku no hay vía): para jugar, el teléfono se conecta por **Bluetooth HID directo** y la TV lo ve como mando físico.
4. El perfil de emulación activo (Xbox 360, Xbox One, DualShock 4, DualSense o genérico; por defecto "Xbox 360 Controller") viaja en el byte 28 del paquete y en el nombre expuesto por HID. El touchpad de DS4/DualSense no se emula en esta versión (ver `docs/PLAN-GAMEPAD-PRO.md`, Fase 2).

## Qué hay en Etapa 1

| Módulo | Qué hace de verdad |
|---|---|
| `:core` | Protocolo 32 bytes (magic, versión, secuencia, timestamp, bitmask, sticks, gatillos, CRC-16), dead zones, curvas, emparejamiento QR/PIN, UDP + TCP, selector multi-transporte, clasificación GAMEPAD, tests unitarios |
| `:app-phone` | Mando completo estilo Xbox (dos sticks, cruceta, A/B/X/Y, LB/RB, LT/RT analógicos, L3/R3, VIEW/MENU/SHARE) + editor visual de botones, zona muerta ajustable; selector de método con estado real por método; modo Automático y manual; Hotspot; ping real por eco (UDP y TCP); conexión por QR real o IP/PIN; comprobación Bluetooth HID |
| `:app-tv` | Receptor UDP 51999 + TCP 52000, pantalla de estado en vivo, diagnóstico "¿Cómo me ve Android?" con "Parece mando real: SÍ/NO"; receptor Bluetooth datos (SPP); código uinput presente pero sin vía de inyección (Shizuku descartado) |

**No hay (y no se finge):** import/export de perfiles, touchpad de DS4/DualSense, háptica avanzada/adaptativa de DualSense. Los perfiles por juego, TIPO DE MANDO, PRUEBA DE MANDO y Rendimiento YA existen (Fase 3, Gamepad PRO). El mando completo (stick derecho, gatillos analógicos LT/RT, LB/RB, L3/R3), el editor de controles y el mando virtual uinput nativo YA existen; lo que falta es verificarlos en la TV real (Fase 0). Los extras de la Fase 4 TAMBIÉN existen (L4/R4 como alias de L3/R3, turbo, macros, vibración al pulsar en el teléfono, teclado virtual y modo ratón como dispositivos aparte, giroscopio); la vibración DEL JUEGO (rumble) existe por Bluetooth HID (hay que re-emparejar tras el cambio de descriptor). Todo sigue sin verificar en TV real.

## Compilar sin PC

1. Crea el repositorio público `ultimate-gamepad-android` en GitHub (desde la app de Android: `+` → New repository). Ver `docs/SETUP-SIN-PC.md`.
   > Bloqueo conocido (2026-11-07): la creación automática del repo desde la integración de GitHub falló con `403 Resource not accessible by integration`, y el push a un repo inexistente con `404 Not Found`. La integración no tiene permiso para crear repositorios nuevos. El repo debe crearlo el usuario a mano; después Coco puede subir los archivos.
2. Sube estos archivos a la rama `main`.
3. Abre la pestaña **Actions** → última ejecución → **Artifacts** → descarga `app-phone-apks` y `app-tv-apks`.
4. Instala `app-phone-debug.apk` en el teléfono y `app-tv-debug.apk` en la TV (permitiendo "instalar apps desconocidas" solo para esa instalación).

## Probar en la TV (Etapa 0, imprescindible)

La Hyundai **no tiene depuración inalámbrica** (solo "Depuración por USB") y **Shizuku está descartado**: no hay ADB por red (:5555) que arrancar. El camino para jugar es el **Bluetooth HID directo**:

1. Teléfono: abre Ultimate Gamepad → Ajustes → Bluetooth HID (el teléfono se registra y se hace visible 300 s).
2. TV: Ajustes → Bluetooth → buscar → emparejar **"Xbox 360 Controller"** (o usa "Buscar TV por Bluetooth" desde el teléfono: ambas direcciones funcionan).
3. Abre OpenNOW y prueba A / joystick. Si la TV no lo ve como mando, revisa el diagnóstico "¿Cómo me ve Android?" de la app de TV.

Prueba mínima por Wi-Fi (sin jugar): en la TV, abre el diagnóstico — ¿dice **"Parece mando real: SÍ"**? Y en "Paquetes", ¿sube el contador al pulsar A en el teléfono? Si dice NO, anota el motivo que muestra el diagnóstico (nombre, vendor/product, sources, ejes que faltan).

## Estructura

```
settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml
core/        # protocolo, estado, UDP/TCP, pairing, transportes, clasificación + tests
app-phone/   # mando virtual (Compose, CameraX QR, Bluetooth HID)
app-tv/      # receptor UDP/TCP + Bluetooth datos + diagnóstico + uinput sin inyección (Shizuku descartado)
.github/workflows/android.yml
docs/VIABILITY-OPENNOW.md  docs/ARCHITECTURE.md  docs/SETUP-SIN-PC.md
```

## Licencia

Código propio de este proyecto: MIT. OpenNOW, Shizuku y las librerías de terceros conservan sus propias licencias.

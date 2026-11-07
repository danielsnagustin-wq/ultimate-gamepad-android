# Arquitectura — Ultimate Gamepad Android

## Módulos Gradle

```
ultimate-gamepad-android
├── core/       Kotlin puro (sin Android UI). Compila como librería Android por
│               el plugin de Gradle, pero sin dependencias de UI.
├── app-phone/  Kotlin + Jetpack Compose + Material 3 oscuro. applicationId
│               com.ultimate.gamepad.phone. minSdk 26, targetSdk 34.
└── app-tv/     Leanback / Activity clásica. applicationId
                com.ultimate.gamepad.tv. minSdk 26, targetSdk 34.
```

## Criterio de éxito: que lo reconozca como si fuera un mando real

El dispositivo expuesto NO debe llamarse "Virtual" ni "Ultimate Gamepad".
Nombre expuesto: **"Xbox 360 Controller"**. Identidad: vendor **0x045E**,
product **0x028E**, bustype USB (uinput), mismo mapeo que un Xbox 360
físico. En Bluetooth HID igual: nombre "Xbox 360 Controller", descriptor
Game Pad, sin servicios extra de teclado/ratón/remoto.

Verificación (Etapa 0): InputManager lo lista, `getSources()` incluye
GAMEPAD+JOYSTICK, OpenNOW lo lista como controller y lo codifica a
XInput como un mando físico. La pantalla de diagnóstico de la TV muestra
"Parece mando real: SÍ / NO" con el motivo. Si un método no logra esa
identidad, se marca FALLO en la UI aunque el Wi-Fi funcione.

## Transportes teléfono → TV

| # | Método | Prioridad | Qué necesita | Si falla | Estado Etapa 1 |
|---|---|---|---|---|---|
| 1 | Wi-Fi UDP, misma red | Principal | Misma Wi-Fi, puerto 51999 | Pasa a TCP | Completo |
| 2 | Wi-Fi TCP, misma red | Respaldo | Misma Wi-Fi, puerto 52000 | Pasa a Bluetooth datos | Completo (básico) |
| 3 | Hotspot del teléfono | Sin router | Hotspot Samsung, TV en esa red, IP 192.168.43.x | Volver a Wi-Fi | Mismo UDP/TCP, modo en UI + docs |
| 5 | Bluetooth datos (BLE/SPP) | Respaldo si falla todo el Wi-Fi | Emparejamiento BT | Pasa a Bluetooth HID | Estructura, Pendiente Etapa 2 |
| 4 | Bluetooth HID | Directo, sin receptora ni Shizuku | A17 expone HID_DEVICE | Ninguno (último) | Estructura + comprobación |

Modo Automático: prueba en orden **1 → 2 → 5 → 4**. Manual: el usuario
fuerza un método. Todos llevan el mismo paquete de 32 bytes de
`:core` (`Transport.kt`, `GamepadProtocol.kt`): cambiar de transporte
no cambia el modelo de entrada.

## Inyección en la TV → OpenNOW

| Método | Qué es | Si falla | Estado Etapa 1 |
|---|---|---|---|
| A. Shizuku + /dev/uinput Xbox 360 | Principal | Pasa a B | Estructura, .so nativo pendiente Etapa 0 |
| B. Shizuku + injectInputEvent SOURCE_GAMEPAD | Respaldo | Queda en "ninguno" | Fallback implementado |
| C. Bluetooth HID | Directa, sin Shizuku | Ninguno | Estructura + comprobación |

## Flujo de datos

```
Teléfono (Compose)                Red Wi-Fi local / BT           TV (Hyundai)
┌────────────────────┐   UDP 51999 / TCP 52000, 32 B, 60 Hz  ┌──────────────────────┐
│ Joystick/D-pad/ABXY│ ─────────────────────────────────────▶│ UdpReceiverService   │
│ GamepadViewModel   │   eco ping cada 1 s ◀──────────────── │  + TcpReceiverService│
│ GamepadSenderService│  (UDP; TCP: ping "—" en Etapa 1)     │  ShizukuHelper       │
│ BluetoothHidService│ ── HID directo ──────────────────────▶│  GamepadUserService  │
└────────────────────┘   (sin receptora)                     │   (shell uid 2000)   │
                                                              │   ├─ uinput Xbox 360 │
                                                              │   └─ injectInputEvent│
                                                              └──────────┬───────────┘
                                                                         │ InputDevice real
                                                                         ▼
                                                                   OpenNOW → XInput → nube
```

## Por qué otras apps se ven como control de TV y cómo lo evitamos

Bug reportado (2026-11-07): el usuario probó una app de gamepad de
Play Store y la TV la reconoció como control de TV / mando a
distancia, no como mando de juego. OpenNOW / GeForce NOW entonces
solo la usa para navegar menús, no para jugar.

Causa probable: esa app enviaba eventos con SOURCE_DPAD /
SOURCE_KEYBOARD, o un descriptor HID genérico / de control remoto,
y Android la clasificó como mando a distancia.

Cómo lo evitamos en Ultimate Gamepad (ver `GamepadClassification.kt`):

1. **Fuente:** todos los eventos llevan `SOURCE_GAMEPAD | SOURCE_JOYSTICK`.
   Nunca SOURCE_DPAD solo, nunca SOURCE_KEYBOARD, nunca
   SOURCE_TOUCHSCREEN para botones/sticks.
2. **KeyEvents:** `KEYCODE_BUTTON_A/B/X/Y`, `BUTTON_L1/R1`,
   `BUTTON_START/SELECT`, `BUTTON_THUMBL/R`, y la cruceta como
   `KEYCODE_DPAD_*` **siempre con source GAMEPAD** y además por
   `AXIS_HAT_X/Y`. Nunca `KEYCODE_ENTER` ni `KEYCODE_BACK` para jugar.
3. **MotionEvents:** `AXIS_X/Y` (stick izq), `AXIS_Z/RZ` (stick der),
   `AXIS_LTRIGGER/RTRIGGER`, `AXIS_HAT_X/Y` (cruceta), siempre con
   `SOURCE_JOYSTICK`.
4. **uinput:** nombre "Xbox 360 Controller", vendor 0x045E, product
   0x028E, bustype BUS_USB. Solo EV_KEY de mando (BTN_A/B/X/Y, TL/TR,
   TL2/TR2, START/SELECT, THUMBL/R, DPAD) y solo EV_ABS de mando
   (ABS_X/Y/RX/RY -32768..32767, ABS_Z/RZ 0..255, ABS_HAT0X/Y -1..1).
   PROHIBIDO: KEY_POWER, KEY_HOME, KEY_VOLUME*, KEY_MENU, KEY_BACK,
   Usage Page Consumer / Remote Control.
5. **Descriptor HID Bluetooth:** Usage Page 0x01 Generic Desktop,
   Usage 0x05 Game Pad (no Joystick ambiguo, no Remote Control 0x0C,
   no Keyboard 0x07). Colección Application Game Pad, ejes, hat
   switch, botones Usage Page 0x09 Button. Mismo mapeo que uinput.
6. **Verificación:** `Diagnostics.kt` en la TV lee los InputDevice
   reales y muestra nombre, vendor/product, sources, ejes y
   "Parece mando real: SÍ / NO" con el motivo. La Etapa 0 consiste
   en que esa línea diga SÍ y que OpenNOW responda en juego.

## Protocolo — :core

Paquete fijo de 32 bytes, little-endian (ver `GamepadProtocol.kt` para el layout exacto byte a byte):
magic `UG` (0x55 0x47), versión 1, flags, secuencia uint16, timestamp ms, bitmask de botones uint16, sticks int16 (-32767..32767), gatillos uint8, CRC-16/CCITT-FALSE sobre bytes 0..25, 4 bytes reservados a 0.

Reglas: la TV descarta magic/versión/CRC incorrectos y paquetes con secuencia más vieja que la última aplicada. Sin retransmisión UDP: un paquete perdido se reemplaza por el siguiente. TCP lee exactamente 32 bytes por paquete (`readFully`).

Emparejamiento: QR `UGP1|<ip>|<puerto>|<pin4>`. El PIN solo evita conectarse a otra TV por error; no es cifrado. Todo ocurre solo en la red local. Hotspot: mismo protocolo, IP del hotspot (192.168.43.x).

## Teléfono — :app-phone

- `MainActivity`: tema oscuro, una pantalla.
- `GamepadViewModel`: estado de botones/sticks, dead zone 0.12 en Etapa 1, conexión por QR o IP/PIN, estado DISCONNECTED/CONNECTING/CONNECTED/ERROR, selector multi-transporte (AUTO/MANUAL, Hotspot), estado real por método, fallback automático UDP → TCP → BLE/SPP → HID, comprobación HID.
- `GamepadSenderService`: servicio en primer plano, envía a 60 Hz por UDP o TCP, ping real por eco UDP cada segundo, reconexión automática (reintento 1 s sin cerrar el servicio). Si el socket falla, guarda `lastError` y la UI lo muestra.
- `BluetoothHidService`: comprueba HID_DEVICE, registra descriptor Game Pad "Xbox 360 Controller", envía reportes HID. Si el A17 no expone el perfil, queda en FAILED.
- `BluetoothDataTransport`: solo interfaz, PENDING_STAGE2.
- `ConnectSheet` / `QrScannerScreen`: escaneo QR real con CameraX + ML Kit. Sin permiso de cámara, queda la entrada manual. Modo Hotspot documentado en la hoja.
- Editor y perfiles: TODO en `ui/TodoScreens.kt` — Etapa 2 y 3, no se presentan como funciones.

## TV — :app-tv

- `MainActivity`: pantalla de estado en vivo (500 ms): IP, puertos, transporte activo, conectado sí/no, último paquete, paquetes recibidos, última entrada (botones/sticks), Shizuku, inyección activa, diagnóstico "¿Cómo me ve Android?".
- `UdpReceiverService`: `DatagramSocket(51999)`, valida, hace eco de ping, descarta secuencias viejas, expone `lastState`/`lastPacketAtMs`/`packetsReceived`/`activeTransport`/`injectionMethod`. Arranca también `TcpReceiverService`.
- `TcpReceiverService`: `ServerSocket(52000)`, lee 32 bytes, mismo `recordPacket()`.
- `Diagnostics`: `InputDevice.getDeviceIds()`, sources, ejes, veredicto "MANDO DE JUEGO" / "CONTROL DE TV - FALLO" y "Parece mando real: SÍ / NO" con motivo.
- `InputMapper`: GamepadState → `KeyEvent` (BUTTON_*) y `MotionEvent` (ejes + HAT) con source `SOURCE_GAMEPAD | SOURCE_JOYSTICK`. Es la inyección B.
- `ShizukuHelper`: comprueba instalación, `pingBinder()`, permiso. Guía específica para Hyundai sin depuración inalámbrica (ADB por red :5555 + Bugjaeger).
- `GamepadUserService` (AIDL `IGamepadInputService`): corre como shell. `createVirtualGamepad()` / `sendReport()` / `injectGamepadEvent()`.
- `UinputGamepad`: **pendiente de Etapa 0**. Devuelve false hasta que su librería JNI (.so) exista y se demuestre en la Hyundai. No se finge un mando.
- `BluetoothDataReceiverStub`: PENDING_STAGE2.

## Decisiones que NO se tomaron en Etapa 1

- Sin cifrado en el protocolo (se documenta como no existente).
- Sin firma de release (sin secretos; el workflow publica release sin firmar).
- Sin base de datos de perfiles (DataStore llegará con perfiles en Etapa 3).
- Sin modificar OpenNOW ni inventar sus APIs.

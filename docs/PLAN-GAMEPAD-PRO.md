# Plan — ULTIMATE GAMEPAD PRO

Fecha: 2026-11-07. HEAD de referencia: `37d91845` (rama `main`, repo
`danielsnagustin-wq/ultimate-gamepad-android`). Análisis de solo lectura:
ningún archivo de código fue modificado para redactar este plan. La copia
local de trabajo no es un checkout git (no hay `.git`), así que el HEAD
se toma del encargo; antes de implementar, verificar que el HEAD remoto
sigue siendo ese.

## 0. Correcciones 2026-11-11 — este plan es una foto de 2026-11-07

**Lo de abajo es la foto original. Lo que sigue la corrige y manda sobre
ella; no se reescribe el documento entero.**

- **Shizuku está DESCARTADO del todo en esta TV** (2026-11-07: no está
  adaptado a TV y la TV no tiene depuración de red ni inalámbrica, solo
  USB). La Fase 0 ya NO es arrancar Shizuku por ADB :5555 + Bugjaeger:
  es emparejar el teléfono por **Bluetooth HID directo** (ver
  `docs/SETUP-SIN-PC.md` §3). Todas las secciones de Fase 0 que hablan
  de Shizuku / ADB :5555 / `sh .../start.sh` / uid 2000 quedan
  **anuladas**. La única vía para que el juego vea un mando es el
  Bluetooth HID. Además, en la ronda 2 se apagó
  `ReceiverConfig.SHIZUKU_PHASE2_ENABLED` (de `true` a `false`): con él
  en `true`, `deliver()` intentaba la inyección Shizuku en cada paquete
  (120 Hz) y sobrescribía `lastError` 120 veces por segundo sin que
  nada pudiera funcionar.
- **Los "12 tests unitarios" de §1 son de 2026-11-07**: la suite creció
  mucho desde entonces (protocolo, rumble, HID, macros, descubrimiento,
  perfiles, secuencias, curvas).

### Filas que decían NO EXISTE y ya EXISTEN

| Fila (§) | Ahora |
|---|---|
| Perfiles Xbox y PlayStation (§2.1), Xbox One (§2.2), DualShock 4, DualSense | **EXISTEN**: `GamepadProfile` en `:core` con 5 perfiles (Xbox 360, Xbox One, DS4, DualSense, genérico); el `profileId` viaja en el byte 28 del paquete. El touchpad de DS4/DualSense **sigue sin existir** y la háptica avanzada / gatillos adaptativos de DualSense **siguen sin ser implementables** por uinput. |
| Cambio de perfil = cambio de mapeo/descriptor (§2.2) | **EXISTE**: `GamepadViewModel.setActiveProfile()` re-registra el HID al cambiar. |
| Pantalla "TIPO DE MANDO" (§2.2, §3.3) | **EXISTE**: `ControllerTypeScreen`. |
| Guardar / aplicar / comprobar reconocimiento (§2.2) | **EXISTE**: `GamepadProfilePrefs`; el cambio re-registra el HID y la UI muestra CONECTANDO durante el cambio. |
| Informar si hace falta reiniciar el servicio (§2.2) | **EXISTE** en la práctica: el re-registro HID es el "reinicio" y se muestra. |
| Al perder la conexión, liberar TODOS los controles (§2.3) | **EXISTE**: `disconnect()` llama a `releaseAllInputs()` (estado cero) y la TV tiene watchdog de 2 s. |
| Perfiles por juego (§2.4) | **EXISTEN**: `GameProfilesScreen` + `GameProfilePrefs` (JSON en SharedPreferences). El **import/export sigue sin existir**. |
| L4/R4 (§2.4) | **EXISTEN como alias de L3/R3** (`ExtrasPrefs.l4_remap` / `r4_remap`, configurables en ExtrasScreen). No son botones independientes; no hizo falta protocolo v2. |
| Teclado virtual, modo ratón (§2.4) | **EXISTEN en código y UI** (`ExtrasProtocol`, `ExtrasScreen`), pero su inyección es por uinput en la TV (Shizuku descartado): **hoy NO llegan al juego**. |
| Turbo, macros, giroscopio (§2.4) | **EXISTEN**: `Turbo`, `MacroRecorder`, `GyroMapper`. |
| Vibración (§2.4) | **EXISTE**: rumble del juego por Bluetooth HID (`RumbleReport`, colección PID en el descriptor) + vibración al pulsar en el teléfono. Hay que **re-emparejar** tras el cambio de descriptor. |
| Prueba interactiva (§2.4) | **EXISTE**: `ControllerTestScreen` (PRUEBA DE MANDO, Fase 3). |
| Rendimiento (§3.3) | **EXISTE**: `PerformanceScreen` (ping, paquetes/s, pérdida). |
| Bluetooth datos (SPP) | **REAL desde la Fase 5** (`BluetoothDataTransport` / `BluetoothDataReceiver`): ya no es "Pendiente Etapa 2". |
| Receptor TV: "crea gamepad virtual" (§2.1) | **PARCIAL**: recibe y muestra en vivo; el mando virtual uinput existe como código (el `.so` compila en CI) pero **sin vía de inyección** (Shizuku descartado). |
| Acceso por Shizuku, comprobación SELinux (§2.2) | **OBSOLETOS**: sin Shizuku no aplican. |
| Estado del servicio y de conexión (§2.5) | **CORREGIDO**: la tarjeta de la TV ya no es Shizuku sino **Bluetooth HID** (ronda 2, UI-1). |

### Sigue sin existir (no se finge)

Detección de app en primer plano / cambio automático (§2.4), panel
rápido durante el juego (§2.4), lectura de vuelta de los valores del
dispositivo virtual (§2.4), integración `dumpsys input` / `getevent`
(§2.4), confirmación de que OpenNOW recibe el gamepad (§2.4: solo el
usuario en un juego puede confirmarlo), touchpad DS4/DualSense, háptica
avanzada / gatillos adaptativos de DualSense, import/export de perfiles.

## 1. Resumen honesto del estado actual

**Lo que es real y funciona en código (sin verificar en la TV real):**

- Protocolo binario de 32 bytes con CRC, secuencia y timestamp, enviado a
  120 Hz por Wi-Fi UDP (principal) y TCP (respaldo), con WifiLock de baja
  latencia, ping/eco, descubrimiento por broadcast, QR/PIN, hotspot y
  fallback automático UDP → TCP → HID. 12 tests unitarios en `:core`
  (8 de geometría de layout + 4 de protocolo) en verde en CI.
- Mando virtual uinput **Xbox 360 completo**: nombre exacto
  `"Xbox 360 Controller"`, vendor 0x045E, product 0x028E, bustype USB,
  16 botones (incl. BTN_MODE y Compartir) y 8 ejes (sticks, gatillos
  analógicos, cruceta). El `.so` nativo se compila en CI
  (`externalNativeBuild` declarado y `:app-tv:assembleDebug` en verde
  en el run 42408588893), y la ruta A (uinput) es la primera que se
  intenta; si falla, cae a la B (`injectInputEvent`).
- Bluetooth HID real en el teléfono: comprobación de disponibilidad,
  registro del descriptor Game Pad, reportes HID de 9 bytes de datos
  + Report ID (1), colección PID de force feedback (rumble, ronda REA
  GFN+OpenNOW), reconexión con backoff y fallback inmediato a Wi-Fi al caer.
- Diagnóstico en la TV que lee los `InputDevice` reales y dice
  "Parece mando real: SÍ / NO" con el motivo exacto.
- Pantalla de mando completa estilo Xbox (sticks, cruceta, ABXY, LB/RB,
  LT/RT analógicos, L3/R3, VIEW/MENU/SHARE), editor visual de botones
  (mover/tamaño/opacidad/eliminar) y ajustes de zona muerta.

**Lo que es stub, TODO o no existe:**

- Bluetooth datos (SPP): implementado en la Fase 5 (SPP/RFCOMM real en los dos lados).
- Perfiles de mando (Xbox 360 / Xbox One / DS4 / DualSense / genérico):
  no existe ninguno salvo el Xbox 360 cableado a mano. No hay pantalla
  "TIPO DE MANDO", no hay perfiles por juego (solo un texto TODO sin
  enlazar), no hay almacenamiento de perfiles (DataStore está declarado
  como dependencia pero no se usa en ningún archivo).
- "PRUEBA DE MANDO": solo existe como vista pasiva en la TV (última
  entrada en vivo + escaneo estático de dispositivos). No hay prueba
  interactiva por botón/stick, no hay valores analógicos en vivo, no
  hay comprobación de OpenNOW, no hay `dumpsys`/`getevent`.
- Controles extra (L4/R4, teclado virtual, modo ratón, turbo, macros,
  giroscopio, vibración): ninguno existe. El permiso `VIBRATE` está
  declarado en el manifest del teléfono y no se usa en ningún archivo.
- Pantalla "Rendimiento": no existe (solo el ping en ms dentro de
  Ajustes).

**Lo que está SIN VERIFICAR en dispositivo real (todo lo decisivo):**

- Que `/dev/uinput` exista y se pueda abrir desde el proceso shell
  (uid 2000) en esta Hyundai concreta, con su SELinux y su kernel.
- Que Shizuku arranque por ADB :5555 (la TV no tiene depuración
  inalámbrica) y se mantenga estable.
- Que Android TV registre el dispositivo y OpenNOW lo reconozca y
  responda en un juego.
- Que Bluetooth HID funcione contra esta TV (el teléfono expone o no
  el perfil, y la TV acepta o no el descriptor).

**Hallazgo crítico de la auditoría (bug real, no opinión):** el
transporte TCP **no inyecta nada**. `TcpReceiverService.kt` solo
llama a `recordPacket()` (lo muestra en vivo) pero nunca llama a
`deliver()` ni a `ShizukuHelper.sendToGamepad()`, así que por TCP el
mando se ve en la pantalla de la TV pero OpenNOW no recibe nada.
Es la corrección nº 1 de la Fase 1.

## 2. Mapa de la especificación al estado actual

Categorías: **IMPLEMENTADO** (código real) / **PARCIAL** (existe pero
incompleto) / **STUB-TODO** (estructura sin funcionar) / **NO EXISTE** /
**SIN VERIFICAR** (código real sin prueba en la TV real).

### 2.1 Objetivo y componentes (§1)

| Punto | Estado | Evidencia |
|---|---|---|
| App teléfono: mando virtual, táctil, editor, sensibilidad, Wi-Fi, BT HID | IMPLEMENTADO | `app-phone/.../ui/GamepadScreen.kt`, `Joystick.kt`, `LayoutEditorScreen.kt`, `SettingsScreen.kt`, `GamepadViewModel.kt`, `GamepadSenderService.kt`, `BluetoothHidService.kt` |
| App teléfono: perfiles Xbox y PlayStation | NO EXISTE | Sin ningún `profile` en `core/`; solo existe el perfil Xbox 360 cableado |
| Receptor TV: recibe entradas, crea gamepad virtual, expone botones/ejes, disponible para otras apps | PARCIAL + SIN VERIFICAR | `app-tv/.../receiver/UdpReceiverService.kt`, `shizuku/UinputGamepad.kt`, `app-tv/src/main/cpp/uinput_gamepad.c`; el dispositivo solo existe si el kernel lo acepta (sin probar en la Hyundai) |
| Receptor independiente de la pantalla de OpenNOW | SIN VERIFICAR | Nada en el código depende de OpenNOW; la verificación es la Fase 0 |

### 2.2 Gamepad real / uinput (§2)

| Punto | Estado | Evidencia |
|---|---|---|
| Dispositivo uinput "Xbox 360 Controller", vendor 0x045E, product 0x028E, BUS_USB, v0x0114 | IMPLEMENTADO + SIN VERIFICAR | `app-tv/src/main/cpp/uinput_gamepad.c:147-159` (`UI_DEV_SETUP`); `core/.../GamepadClassification.kt:52-90` (`UinputSpec`) |
| Stick izq. ABS_X/Y, der. ABS_RX/RY, gatillos ABS_Z/RZ, cruceta ABS_HAT0X/Y | IMPLEMENTADO + SIN VERIFICAR | `uinput_gamepad.c:136-145` (`setup_abs`); botones (tabla `kButtons`, líneas 43-63) |
| Botones frontales, superiores, L3/R3, sistema (Xbox/Share) | IMPLEMENTADO + SIN VERIFICAR | `uinput_gamepad.c:55-72` (BTN_A/B/X/Y, TL/TR, TL2/TR2, START/SELECT, THUMBL/R, DPAD, BTN_MODE 0x13C, BTN_TRIGGER_HAPPY1 0x2C0) |
| Acceso por Shizuku (shell uid 2000) | IMPLEMENTADO + SIN VERIFICAR | `app-tv/.../shizuku/GamepadUserService.kt`, `ShizukuHelper.kt`, `IGamepadInputService.kt`; Shizuku 13.1.5 (`gradle/libs.versions.toml`) |
| Comprobación de permisos/SELinux/restricciones del fabricante | NO EXISTE | Ningún archivo comprueba `/dev/uinput`, SELinux ni el kernel; `ug_create()` solo devuelve false si `open()` falla (`uinput_gamepad.c:118-124`) |
| Alternativa si la TV no permite uinput | PARCIAL | Fallback B `injectInputEvent` existe (`GamepadUserService.kt:91-105`, `InputMapper.kt`); sus límites están documentados en `docs/VIABILITY-OPENNOW.md` §3: inyecta eventos pero **no registra un InputDevice** |
| No sustituir el mando por teclado/D-pad | IMPLEMENTADO | `InputMapper.kt:14-18`, `GamepadClassification.kt:20-30`: todo con `SOURCE_GAMEPAD \| SOURCE_JOYSTICK`, prohibidos `KEYCODE_ENTER`/`BACK` y teclas de remoto |

### 2.3 Perfiles de mando (§3)

| Perfil | Estado | Qué falta |
|---|---|---|
| Xbox 360 | IMPLEMENTADO + SIN VERIFICAR | Nada en el dispositivo; falta el cambio de perfil (hoy es el único) |
| Xbox One | NO EXISTE | Segunda definición de dispositivo: nombre `"Xbox One Controller"`, product 0x02DD (o 0x02FD), mismo mapa de botones/ejes; hoy `uinput_gamepad.c` tiene UNA sola definición cableada |
| DualShock 4 | NO EXISTE | Definición Sony: vendor 0x054C, product 0x05C4, nombre DS4; Cross/Circle/Square/Triangle sobre los mismos códigos evdev (BTN_SOUTH/EAST/WEST/NORTH = 0x130/0x131/0x133/0x134); Options=START, Share=SELECT, PS=BTN_MODE; **touchpad no existe**: necesitaría BTN_TOUCH + ejes de toque (dispositivo táctil aparte o ABS_MT), hoy no hay nada |
| DualSense | NO EXISTE | Como DS4 (vendor 0x054C, product 0x0CE6, nombre `"DualSense Wireless Controller"`, botón Create) + touchpad; **háptica avanzada y gatillos adaptativos NO son implementables por uinput** (necesitan reportes HID feature por USB/BT, no existen en evdev) — no prometerlos |
| Genérico HID | PARCIAL | El descriptor HID genérico ya existe (`GamepadClassification.kt:94-163`, 16 botones + hat + 4 ejes + 2 gatillos analógicos); falta un dispositivo uinput genérico (nombre neutro, sin VID/PID de Microsoft/Sony) y el selector que lo active |
| Cambio de perfil = cambio de mapeo/descriptor, no solo dibujos | NO EXISTE | No hay concepto de perfil en `GamepadState` (`core/.../GamepadState.kt:11-24`), ni en el protocolo (32 bytes sin campo de perfil), ni en `UinputGamepad` (un solo `create()` sin parámetros), ni en `BluetoothHidService` (un solo descriptor fijo) |

### 2.4 Cambio de mando en caliente (§4)

| Punto | Estado | Evidencia |
|---|---|---|
| Pantalla "TIPO DE MANDO" con tarjetas | NO EXISTE | Ninguna pantalla ni navegación; `MainActivity.kt` (teléfono) solo muestra `GamepadScreen` |
| Guardar selección / aplicar mapeo / actualizar dispositivo / comprobar reconocimiento / volver atrás | NO EXISTE | Nada de eso existe; el único guardado es el layout de botones (`LayoutConfig.kt`, SharedPreferences JSON) |
| Informar si hace falta reiniciar el servicio | NO EXISTE | El cambio de perfil exigiría destruir y recrear el dispositivo uinput; `destroyVirtualGamepad()` existe en la interfaz pero **nadie lo llama** (solo su implementación en `GamepadUserService.kt:89`) |

### 2.5 Conexión teléfono–TV (§5)

| Punto | Estado | Evidencia |
|---|---|---|
| Wi-Fi UDP baja latencia (principal) | IMPLEMENTADO | `UdpTransport.kt` (IPTOS_LOWDELAY, SO_REUSEADDR, búferes 64 KiB), `WifiLatencyLock.kt` (WIFI_MODE_FULL_LOW_LATENCY), envío a 120 Hz (`GamepadSenderService.kt:193` `delay(8)`) |
| TCP como alternativa | PARCIAL — **BUG: no inyecta** | `TcpTransport.kt` + `TcpReceiverService.kt` reciben y muestran, pero el bucle de TCP solo llama a `recordPacket()` (líneas 57-66) y **nunca** a `deliver()`/`sendToGamepad()`; por TCP OpenNOW no recibe nada |
| Bluetooth HID opcional independiente | IMPLEMENTADO + SIN VERIFICAR | `BluetoothHidService.kt`; reportes solo al cambiar la entrada (`GamepadViewModel.kt:157`), no a frecuencia fija |
| Protocolo: botones, sticks, gatillos, cruceta, estado completo, secuencia, conexión | IMPLEMENTADO | `GamepadProtocol.kt:9-28` (32 bytes: magic, versión, secuencia uint16, timestamp, bitmask uint16, 4 ejes int16, 2 gatillos uint8, CRC-16, 4 reservados) |
| Sincronización periódica anti-bloqueo | PARCIAL | El estado COMPLETO se reenvía a 120 Hz (un paquete perdido se reemplaza 8 ms después) y hay ping/eco cada 500 ms por UDP (`GamepadSenderService.kt:149-151`); **no hay heartbeat explícito ni paquete de liberación** |
| Al perder la conexión, liberar TODOS los controles | **NO EXISTE** | Ni el teléfono envía un estado cero al desconectar (`GamepadViewModel.kt:438-444` `disconnect()`; `GamepadSenderService.kt:203-206` `stopSending()` cancela sin enviar nada) ni la TV tiene un watchdog que suelte los controles: `UdpReceiverService.kt` solo marca "Sin conexión" por silencio > 2 s (`ConnectionHealth.kt:15`) y `lastState` queda con los botones pulsados; el mando virtual uinput queda con el último reporte hasta que llegue otro paquete |

### 2.6 Bluetooth HID (§6)

| Punto | Estado | Evidencia |
|---|---|---|
| `BluetoothHidDevice`, teléfono como periférico HID gamepad | IMPLEMENTADO + SIN VERIFICAR | `BluetoothHidService.kt:22-263` |
| Perfil HID gamepad real (no multimedia/remoto) | IMPLEMENTADO + SIN VERIFICAR | Descriptor `HidGamepadDescriptor.BYTES` (`GamepadClassification.kt:94-163`): Usage Page 0x01, Usage 0x05 Game Pad, 16 botones en el orden Android, hat, 4 ejes, 2 gatillos analógicos (Brake/Accelerator); sin Usage 0x0C/0x07 |
| VID/PID fijables por HID | **NO (limitación de la API)** | `BluetoothHidService.kt:190-196`: `BluetoothHidDeviceAppSdpSettings(name, description, provider, subclass, descriptors)` **no acepta vendorId/productId**; por HID la identidad es nombre + descriptor, la identidad completa (0x045E/0x028E) solo la da uinput |
| Gatillos analógicos por HID | **IMPLEMENTADO (ronda Bluetooth dos vías, 2026-11-11)** | Descriptor con Brake (LT) / Accelerator (RT), 0..255, en el reporte de 9 bytes; LT/RT siguen además como botones digitales 9/10 al pasar de 0.5 |
| Compatibilidad con la versión de Android del teléfono y Google TV | SIN VERIFICAR | Comprobación en tiempo de ejecución (`checkAvailability`, API 28+, permiso `BLUETOOTH_CONNECT`); el firmware del teléfono puede no exponer HID_DEVICE; la TV puede rechazar el descriptor |
| HID ≠ XInput | Documentado | `docs/VIABILITY-OPENNOW.md` §1: OpenNOW lee eventos Android y los codifica a XInput; HID es solo el transporte hasta Android |

### 2.7 Juegos y perfiles por juego (§7–§8)

| Punto | Estado | Evidencia |
|---|---|---|
| Jugar a cualquier género con mando (sin teclado) | PARCIAL + SIN VERIFICAR | Depende por completo de que Android registre el gamepad (Fase 0); nada en el código depende del género |
| Perfiles por juego (layout, tipo de mando, sensibilidad, zona muerta, curva, gatillos, vibración, remap) | NO EXISTE / STUB-TODO | `TodoScreens.kt:20` `ProfilesTodoScreen` ("TODO Etapa 3 — Perfiles por juego, guardar / importar / exportar"), **sin enlazar desde ningún botón**; `LayoutConfig.kt` solo guarda layout + zona muerta (SharedPreferences JSON); DataStore 1.1.1 está declarado en `app-phone/build.gradle.kts` y en `gradle/libs.versions.toml` pero **no se usa en ningún archivo `.kt`** |
| Detección de app en primer plano / cambio automático | NO EXISTE | Nada lee la app en primer plano; en GeForce NOW el juego no es identificable desde la TV, queda selección manual (como pide la especificación) |

### 2.8 Mando virtual del teléfono (§9)

| Control | Estado | Evidencia |
|---|---|---|
| ABXY, cruceta, 2 sticks, L3/R3, LB/RB, LT/RT, VIEW, MENU, SHARE | IMPLEMENTADO | `GamepadScreen.kt:241-628` (`ShoulderRow`, `LeftControls`, `RightControls`, `CenterControls`, `AbxyPad`, `XboxButton`); LT/RT son analógicos por separado (`GamepadViewModel.kt:137-140`); L3/R3 por toque del stick (`Joystick.kt:105-115`); multitáctil con liberación garantizada (`Joystick.kt:36-66` `pointerPress`) |
| L4/R4 | **NO EXISTE** | El bitmask de botones está lleno: 16/16 bits usados (`GamepadState.kt:71-88`); hacen falta 2 bits más → protocolo v2 |
| Teclado virtual | NO EXISTE | Nada; además un teclado exigiría un segundo dispositivo virtual (uinput teclado) o inyección de teclas, separado del gamepad |
| Modo ratón | NO EXISTE | Nada; exigiría un dispositivo puntero aparte (uinput ratón con ABS_X/Y relativos o un HID mouse) |
| Panel rápido | NO EXISTE | Solo `TransportSelectorRow.kt` (chips de método dentro de Ajustes); no hay panel rápido durante el juego |
| Turbo | NO EXISTE | Nada; el envío es 120 Hz de estado, sin generador de pulsos |
| Macros | NO EXISTE | Nada |
| Giroscopio | NO EXISTE | Nada; no se lee ningún sensor del teléfono |
| Vibración | NO EXISTE | Permiso `VIBRATE` declarado (`app-phone/.../AndroidManifest.xml:14`) y **cero usos** en todo el código |

### 2.9 Diagnóstico (§10) — "PRUEBA DE MANDO"

| Punto | Estado | Evidencia |
|---|---|---|
| Pantalla de prueba en la TV | PARCIAL | `app-tv/.../MainActivity.kt`: estado en vivo (IP, PIN, transporte, paquetes, última entrada) + `Diagnostics.render()`; no es una pantalla dedicada ni interactiva |
| Nombre del dispositivo virtual, descriptor/perfil activo, id, ejes, botones | PARCIAL | `Diagnostics.kt:93-106` `scan()`: nombre, vendor/product, sources, **ejes**; **no lista botones** (no hay lectura de teclas del dispositivo) y no hay "perfil activo" porque no existe el concepto |
| Valores analógicos en tiempo real | NO EXISTE | `MainActivity.kt` muestra la última entrada recibida por red (sticks/gatillos como texto), **no los valores del dispositivo virtual**; no hay lectura de vuelta desde `/dev/input/event*` |
| Estado del servicio y de conexión | IMPLEMENTADO | `MainActivity.kt:139-160` (Shizuku, inyección activa, conectado sí/no, último paquete) |
| Prueba interactiva (mover cada stick, pulsar cada botón) | NO EXISTE | La vista en vivo es pasiva; no hay checklist por control ni registro de lo probado |
| Etapa 1: el teléfono envía | IMPLEMENTADO | Vista en vivo + contador de paquetes (`UdpReceiverService.kt`) |
| Etapa 2: el receptor recibe | IMPLEMENTADO | Mismo punto; la TV muestra lo que decodifica |
| Etapa 3: Android registra el gamepad | PARCIAL | `Diagnostics.kt:52-67` `looksLikeRealGamepad()` (nombre, vendor/product, GAMEPAD+JOYSTICK, 8 ejes); no comprueba botones ni que el dispositivo siga vivo |
| Etapa 4: OpenNOW recibe el gamepad | **NO EXISTE** | Nada en el proyecto puede comprobarlo desde dentro; solo el usuario en un juego puede confirmarlo |
| `dumpsys input` / `getevent` | NO EXISTE | No hay integración; solo se pueden correr a mano por ADB (ver Fase 0) |

### 2.10 Interfaz visual (§11) — las 7 pantallas

| Pantalla | Estado | Evidencia |
|---|---|---|
| 1. Mando | IMPLEMENTADO | `GamepadScreen.kt`: AMOLED negro puro, neón morado/cian, animaciones de pulsación (`Joystick.kt:69-87` `pressEffect`) |
| 2. Conexión | IMPLEMENTADO (dentro de Ajustes) | `SettingsScreen.kt` + `TransportSelectorRow.kt` + `QrScannerScreen`; no es pantalla propia |
| 3. Tipo de mando | NO EXISTE | — |
| 4. Perfiles | STUB-TODO | `TodoScreens.kt:20`, sin enlazar |
| 5. Prueba de mando | PARCIAL | TV `MainActivity.kt` + `Diagnostics.kt` (ver 2.9) |
| 6. Rendimiento | NO EXISTE | Solo ping en ms en Ajustes; no hay latencia/jitter/pérdida ni gráficos |
| 7. Ajustes | IMPLEMENTADO | `SettingsScreen.kt`: conexión, dead zone 0–0.15, editor de botones |

### 2.11 Entrega (§13)

| Punto | Estado |
|---|---|
| Código fuente completo | Sí (repo, `main`) |
| APK teléfono + TV | Sí, por CI (`.github/workflows/android.yml`: tests `:core`, `assembleDebug` teléfono y TV, release sin firmar, artifacts `app-phone-apks` / `app-tv-apks`); último run verde conocido: 42408588893 |
| Instrucciones de instalación | Sí (`docs/SETUP-SIN-PC.md`, `README.md`) |
| Configuración Shizuku/ADB | Sí, documentada; sin verificar en esta TV |
| Pruebas documentadas | Parcial: criterios Etapa 0 en `docs/SETUP-SIN-PC.md` §5 (7 sí/no); sin resultados reales todavía |
| Registro de errores | Parcial: `lastError` en servicios + diagnóstico con motivo; sin registro persistente |
| Compatibilidad documentada | Parcial: `docs/VIABILITY-OPENNOW.md`; falta la matriz por perfil (depende de la Fase 0) |

## 3. Hallazgos críticos de la auditoría (antes de programar nada)

### H1. TCP no inyecta (bug funcional, prioridad máxima)
`TcpReceiverService.kt:57-66` solo llama a
`UdpReceiverService.recordPacket()`; `deliver()` es `private` en
`UdpReceiverService.kt:204` y solo se llama desde el bucle UDP. Por
TCP la TV muestra la entrada en vivo pero OpenNOW no recibe nada.
El usuario pidió TCP como alternativa real: hoy no lo es.

### H2. No se liberan los controles al perder la conexión
Ni el teléfono envía un estado cero al desconectar ni la TV tiene
un watchdog que suelte el último estado. Con el mando virtual
uinput, el último reporte queda activo (botón pulsado, stick
desviado) hasta que llegue otro paquete. En un juego eso es un
personaje corriendo solo o un botón atascado. La especificación
§5 lo exige explícito: "liberar inmediatamente todos los
controles".

### H3. El HID no tiene gatillos analógicos ni envío a frecuencia fija
El descriptor actual no incluye ejes de gatillo y los reportes HID
solo se envían cuando cambia la entrada (`GamepadViewModel.kt:151-153`).
Si el usuario mantiene un stick quieto, no hay reenvío; si se
pierde un reporte, no hay corrección hasta el siguiente cambio.
Además la API de `BluetoothHidDeviceAppSdpSettings` no permite
fijar VID/PID: por HID nunca habrá identidad 0x045E/0x028E.

### H4. El protocolo no tiene campo de perfil y el bitmask está lleno
Los 32 bytes están completos y los 16 bits de botones están todos
usados. L4/R4, touchpad, giroscopio y cualquier botón nuevo exigen
protocolo v2 (o reutilizar los 4 bytes reservados, hoy a 0, para un
`profileId` + 2 bits de botones — ver Fase 2). Sin eso no hay
perfiles de emulación.

### H5. Comentarios y documentación desactualizados (riesgo de confusión)
- `GamepadUserService.kt:20-33` dice que la librería uinput "NO está
  en Etapa 1" y que `createVirtualGamepad()` "devuelve false hasta
  que la Etapa 0 lo demuestre", pero el código real YA llama a
  `UinputGamepad.create()` y el `.so` se compila en CI.
- `README.md:62` dice que no hay "joystick derecho, gatillos
  analógicos LT/RT, LB/RB, L3/R3, mando virtual uinput nativo
  terminado": el stick derecho, los gatillos y el código uinput YA
  existen; lo que falta es la verificación en la TV.
- `docs/ARCHITECTURE.md` y `GamepadSenderService.kt` hablan de 60 Hz;
  el envío real es a 120 Hz desde la auditoría de red.
- `GamepadViewModel.kt` usa zona muerta 0.05 por defecto
  (`LayoutConfig.kt:68`); la documentación dice 0.12.

### H6. El `.so` se compila en CI, pero nada de la Fase 0 está probado
`app-tv/build.gradle.kts` declara `externalNativeBuild` con CMake y
el workflow compila `:app-tv:assembleDebug` (run 42408588893 en
verde): el `.so` va dentro del APK. La inyección está activada en el
build (`ReceiverConfig.kt:25`, `SHIZUKU_PHASE2_ENABLED = true`): el
receptor intenta A y luego B en cada paquete. Lo que nadie ha
comprobado es:
que `System.loadLibrary("uinput_gamepad")` cargue en la Hyundai,
que `/dev/uinput` exista y se abra desde uid 2000, y que el kernel
acepte `UI_DEV_CREATE`. Todo el plan depende de esa respuesta.

### H7. La TV no tiene forma de apagar el mando virtual a mano
`destroyVirtualGamepad()` existe pero nadie lo llama. Si el
UserService muere, el fd se cierra y el kernel destruye el
dispositivo, pero no hay un botón "destruir mando" ni recreación
controlada. Para cambiar de perfil en caliente hará falta
destruir + recrear (Fase 2).

### H8. Sin registro persistente de errores ni telemetría de red
`lastError` es volátil y se pisa; no hay log persistente, ni
estadísticas de pérdida/jitter. La pantalla "Rendimiento" pedida
no existe: hoy solo se ve el ping en ms.

## 4. Plan por fases

Regla general: ninguna fase se da por terminada sin su criterio de
aceptación comprobado en la TV real del usuario. CI en verde no es
verificación en dispositivo.

### Fase 0 — VERIFICACIÓN en la TV real (puerta de salida de todo lo demás)

**Objetivo:** responder con sí/no, en esta Hyundai concreta, si el
mando virtual uinput es posible, y con qué identidad lo ve Android
y OpenNOW. Sin esto, las Fases 2–4 son apuestas.

**No se escribe código nuevo** (salvo el que ya existe). Trabajo:
instalar los APK actuales (run 42408588893), arrancar Shizuku por
ADB :5555 con Bugjaeger, conectar el teléfono por Wi-Fi UDP y
recorrer esta lista, anotando cada resultado.

Comprobaciones exactas (desde Bugjaeger, shell ADB contra la TV):

1. `adb shell ls -l /dev/uinput` — existe el nodo y con qué permisos.
   Si no existe: el kernel no tiene uinput → ir al §6 (alternativas).
2. `adb shell getenforce` — `Enforcing` o `Permissive`. Si es
   `Enforcing`, anotarlo: SELinux puede denegar la apertura desde
   shell aunque el nodo exista.
3. `adb shell id` — confirmar uid 2000 en el proceso de Shizuku
   (el UserService corre como shell; si Shizuku no arrancó, esto no
   aplica).
4. `adb shell dumpsys input` — con el receptor corriendo y el
   teléfono enviando: ¿aparece un dispositivo `"Xbox 360
   Controller"`? Anotar su id, vendor/product y sources.
5. `adb shell getevent -l` — pulsar A y mover sticks en el teléfono:
   ¿salen eventos `EV_KEY BTN_A` y `EV_ABS ABS_X` del dispositivo
   virtual? Anotar el `/dev/input/eventN` que corresponde.
6. En la TV: `Diagnostics.render()` — ¿dice "Parece mando real: SÍ"?
   Si dice NO, anotar el motivo exacto que muestra.
7. Abrir OpenNOW, ir a su pantalla de mandos / abrir un juego con
   mando: ¿lo lista como controller? ¿Responde A y el stick en el
   menú y en el juego? Anotar sí/no por cada cosa.
8. Probar el fallback B: forzar fallo de uinput (p. ej. parar
   Shizuku un momento) y comprobar si `injectInputEvent` mueve el
   menú de OpenNOW. Anotar si sirve para jugar o solo para menús.
9. Probar Bluetooth HID: emparejar "Xbox 360 Controller" desde la
   TV (Ajustes → Mandos): ¿aparece? ¿OpenNOW responde? ¿Se cae?
10. Probar TCP: conectar por TCP y comprobar en la TV que la entrada
    llega (hoy se ve en vivo) y anotar que NO inyecta (H1 confirmado).

**Criterios de aceptación (qué confirma o descarta cada cosa):**
- 1+2+4+5 en SÍ → uinput es viable en esta TV; seguir a Fase 1 y 2.
- 1 en NO (sin `/dev/uinput`) o 4 en NO con motivo de SELinux →
  uinput no es viable aquí; ir al §6 y reorientar el plan (el mando
  real por uinput no existe en esta TV; las opciones son
  injectInputEvent, HID o hardware externo).
- 7 en NO con 4 en SÍ → Android registra el mando pero OpenNOW no
  lo acepta: hay que analizar qué le falta (descriptor, fuentes,
  momento de enumeración) antes de seguir.
- 8 en "solo menús" → el fallback B no basta para jugar; uinput es
  obligatorio para el objetivo.

**Riesgos:** la TV puede no exponer ADB por red (entonces Shizuku
no arranca y la Fase 0 se bloquea: anotarlo como resultado, no
inventar que funciona); Shizuku no sobrevive a reinicios (hay que
repetir el arranque); el `.so` puede no cargar en la arquitectura
de la TV (el APK lleva todas las ABI por defecto, sin `abiFilters`).

### Fase 1 — Correcciones de la base (sin perfiles todavía)

**Objetivo:** que lo ya construido funcione al 100% antes de añadir
perfiles. Archivos:

- `app-tv/.../receiver/TcpReceiverService.kt`: llamar a la entrega
  de paquetes (hoy solo `recordPacket()`). La forma limpia es hacer
  `deliver()` accesible desde ambos receptores (moverla a un punto
  único, p. ej. un `deliver(state, rawPacket)` compartido en
  `UdpReceiverService` o en una clase `PacketDeliverer`), sin
  duplicar la lógica de inyección. Criterio: conectar por TCP,
  pulsar A y comprobar por `getevent` que el evento sale del
  dispositivo virtual (hoy no sale).
- Liberación de controles al perder la conexión (H2): en el teléfono,
  `GamepadViewModel.disconnect()` y `GamepadSenderService.stopSending()`
  envían un estado cero (botones 0, sticks 0, gatillos 0) antes de
  cerrar; en la TV, un watchdog en `UdpReceiverService` (p. ej. una
  corrutina que cada 500 ms comprueba `lastPacketAtMs`): si pasan
  > 2 s sin paquetes, envía un estado cero al mando virtual
  (uinput e inject) y marca `lastState = GamepadState()`. Criterio:
  desconectar el Wi-Fi 3 s con un botón pulsado y comprobar por
  `getevent` que no queda ningún botón activo.
- Heartbeat explícito: el ping UDP cada 500 ms ya existe; añadir el
  eco TCP (hoy el ping por TCP es "—", `TcpReceiverService.kt:24`)
  para que el fallback y el estado "Conectado" sean reales por TCP.
  Criterio: ping con valor por TCP, no "—".
- HID: gatillos analógicos ya implementados (ronda Bluetooth dos
  vías, 2026-11-11): descriptor con Brake/Accelerator, reporte de
  9 bytes; la UI ya no debe decir que por HID no hay gatillos
  analógicos.
- Limpieza de documentación: actualizar `GamepadUserService.kt`
  (comentario obsoleto), `README.md:62` y `docs/ARCHITECTURE.md`
  (60 Hz → 120 Hz, zona muerta 0.05, uinput implementado). Criterio:
  que ningún documento afirme algo que el código ya desmiente.

**Riesgos:** el watchdog de la TV debe correr en el mismo hilo
que `deliver()` para no competir con los paquetes; el estado cero
no debe dispararse por un simple cambio de red momentáneo (usar el
mismo timeout de 2 s de `ConnectionHealth`).

### Fase 2 — Protocolo v2 y perfiles de emulación (el núcleo de PRO)

**Objetivo:** que cambiar de perfil cambie el dispositivo virtual
real, no solo los dibujos.

**IMPLEMENTADO 2026-11-07 (commit Fase 2):** decisión de protocolo
tomada: 1 byte reservado (offset 28) como `profileId` (0 = Xbox 360,
1 = Xbox One, 2 = DS4, 3 = DualSense, 4 = genérico); compatible hacia
atrás, sin subir a versión 2. `GamepadProfile` en `:core`;
`uinput_gamepad.c` parametrizado por perfil (nombre, vendor, product);
`createVirtualGamepad(profileId)` en `IGamepadInputService` (interfaz,
Stub y Proxy a la vez); un solo `HidGamepadDescriptor` (16 botones,
igual para todos los perfiles; el perfil solo cambia el nombre
expuesto); el teléfono envía el `profileId` en cada paquete (UDP y TCP) y la TV recrea el dispositivo
si cambia; el perfil activo se guarda en el teléfono
(SharedPreferences) y la pantalla TIPO DE MANDO (Fase 3) lo cambiará
con `GamepadViewModel.setActiveProfile()`. Touchpad: opción (a) — NO
implementado en esta versión y dicho claro en UI/docs; tampoco
háptica avanzada ni gatillos adaptativos del DualSense. L4/R4 NO es
Fase 2: es Fase 4; el bitmask (16/16) NO se tocó. Sin verificar en la
TV real: que el nombre y vendor/product cambien por perfil
(`dumpsys input`), que `Diagnostics` lo reconozca y que OpenNOW
responda por perfil; tampoco está probado si OpenNOW re-enumera el
mando nuevo sin reiniciarse.

Diseño (decisión que hay que tomar al empezar la fase, no después):
- El protocolo de 32 bytes está lleno. Opción recomendada: usar 1
  de los 4 bytes reservados (offset 28, hoy siempre 0) como
  `profileId` (0 = Xbox 360, 1 = Xbox One, 2 = DS4, 3 = DualSense,
  4 = genérico) y reservar 2 bits del bitmask para L4/R4 en el
  futuro; el resto de bytes reservados quedan a 0. Es compatible
  hacia atrás: los paquetes viejos (perfil 0) siguen siendo Xbox 360.
  Alternativa: subir a versión 2 con otro layout; más limpia pero
  rompe la compatibilidad con los APK ya instalados. Decidir con el
  usuario antes de programar.
- `core`: nuevo `GamepadProfile` (id, nombre, vendor/product,
  nombre de dispositivo, mapa de botones). `GamepadState` no cambia
  (el estado es neutro); el perfil vive en el emisor (teléfono) y
  en el receptor (TV), que es quien crea el dispositivo.
- `app-tv/src/main/cpp/uinput_gamepad.c`: parametrizar `ug_create()`
  con el perfil (nombre, vendor, product, tabla de botones y ejes).
  Mantener el Xbox 360 como está y añadir: Xbox One (mismo mapa,
  product 0x02DD), DS4 (vendor 0x054C, product 0x05C4, Options/Share/
  PS; touchpad en fase aparte, ver abajo), DualSense (0x0CE6, botón
  Create), genérico (nombre neutro, p. ej. `"Generic Gamepad"`,
  vendor/product genéricos). JNI: `nativeCreate(profileId)`.
- `app-tv/.../shizuku/UinputGamepad.kt` + `GamepadUserService.kt`:
  `createVirtualGamepad(profileId)`; al cambiar de perfil,
  `destroyVirtualGamepad()` + `create()` (recreación en caliente).
  El `IGamepadInputService` necesita el parámetro nuevo (cambiar
  la interfaz y su `Stub`/`Proxy` a la vez).
- `core/.../GamepadClassification.kt`: un `HidGamepadDescriptor`
  por perfil (el actual sirve para Xbox; DS4/DualSense necesitan
  su descriptor con los botones correctos y, si se implementa el
  touchpad, sus usages). `BluetoothHidService.kt` usa el descriptor
  del perfil activo.
- `InputMapper.kt` (fallback B): el mapeo de botones a
  `KEYCODE_BUTTON_*` ya es neutro; el touchpad no se puede inyectar
  por `injectInputEvent` como tal (no hay eje de toque de mando en
  `MotionEvent` de joystick): documentarlo como límite del fallback.

**Touchpad (DS4/DualSense):** es el sub-proyecto más difícil de la
fase. Opciones, en orden de viabilidad: (a) no implementarlo y
decirlo claro (el touchpad no es necesario para jugar a GeForce NOW;
muchos juegos no lo usan); (b) implementarlo como segundo
dispositivo uinput táctil (BTN_TOUCH + ABS_X/ABS_Y de toque) — es
otro dispositivo virtual, no parte del gamepad; (c) mapear el
touchpad a los sticks en el teléfono (toque en el panel táctil =
movimiento de stick). La (a) es la recomendada para la primera
versión; la (b) es trabajo aparte.

**Criterios de aceptación (en la TV real, por perfil):** cambiar a
Xbox One / DS4 / DualSense / genérico, comprobar por
`dumpsys input` que el nombre y vendor/product cambian, que
`Diagnostics` lo reconoce como mando, y que OpenNOW responde a A
y a los sticks en un juego. Si un perfil no aparece o no responde,
anotar el evento (`getevent`) y corregir su descriptor o su mapa.

**Riesgos:** recrear el dispositivo uinput mientras OpenNOW lo está
usando puede hacer que OpenNOW no lo re-enumere hasta reiniciar la
app; hay que probar si el cambio en caliente funciona o si hay que
avisar "reinicia OpenNOW". El nombre del dispositivo debe cambiar
de verdad: si Android cachea el nombre viejo, es un falso positivo.

### Fase 3 — Pantallas: TIPO DE MANDO, PRUEBA DE MANDO, Perfiles, Rendimiento

**IMPLEMENTADO 2026-11-07 (Fase 3, commits 712ab99e + eee86b07 + lotes teléfono/TV):**
- **TIPO DE MANDO** (teléfono, `app-phone/.../ui/ControllerTypeScreen.kt`, desde Ajustes > Mando PRO): tarjetas de los 5 perfiles con nombre de dispositivo y VID/PID; al elegir, `setActiveProfile()` cambia visual y emulación a la vez y guarda el anterior para "Volver al perfil anterior". La comprobación de que Android reconoce el mando nuevo se hace mirando el diagnóstico de la TV (no hay canal de estado TV → teléfono que lo devuelva; la pantalla lo dice en vez de fingirlo) y avisa de reiniciar OpenNOW si no re-enumera.
- **PRUEBA DE MANDO** (TV, botón "Prueba de mando" en `MainActivity.kt`; teléfono, `ControllerTestScreen.kt`): dispositivo virtual actual leído del sistema (nombre, id, vendor/product, ejes y **botones con `InputDevice.hasKeys`**, nuevo en `Diagnostics.kt`), checklist interactiva de 20 controles (`ControllerTestChecklist` en `:core`, etiquetas según el perfil) con valores analógicos en vivo del **último paquete recibido por red** — Android no expone valores en vivo del dispositivo por API, así que no se finge lectura de `/dev/input` y la pantalla lo dice. Las 4 etapas con veredicto propio; la 4ª (OpenNOW) SOLO se marca con el botón manual tras probar un juego (guardado en `TvTestPrefs`), nunca sola. El teléfono muestra la misma prueba en pequeño (qué se envía) y sus etapas 2–3 remiten a la TV.
- **Perfiles por juego** (teléfono, `GameProfilesScreen.kt` + `GameProfile` en `:core` + `GameProfilePrefs.kt`): un perfil por juego (tipo de mando, zona muerta, curva, sensibilidad de gatillos, vibración guardada — Fase 4 —, remapeo A/B/X/Y aplicado al enviar, diseño de botones guardado). Selección manual, sin detección de app en primer plano. `TodoScreens.kt:20` (ProfilesTodoScreen) ahora delega en la pantalla real. **Almacenamiento: SharedPreferences JSON** (como LayoutPrefs y GamepadProfilePrefs), NO DataStore: DataStore está declarado como dependencia pero no se usa en ningún archivo del proyecto y para una lista pequeña de JSON no aporta nada frente al patrón existente; decisión documentada en `GameProfile.kt` y `GameProfilePrefs.kt`.
- **Rendimiento** (teléfono, `PerformanceScreen.kt` + stats en `GamepadSenderService`): latencia (ping por eco), jitter (media de |ping[i]-ping[i-1]|, últimas 20 muestras, `NetworkStats` en `:core`), paquetes/s enviados (ventana de 1 s) y total enviado. **Pérdida: solo se mide en la TV** por huecos de secuencia (`NetworkStats.sequenceGap`, contador en `UdpReceiverService.recordPacket`, visible en la prueba de la TV); el teléfono no la puede medir por UDP y la pantalla lo dice en vez de inventar un 0%.
- Sin verificar en la TV real: todo lo de la Fase 3 (que el cambio de perfil se vea en el diagnóstico, que la checklist marque al pulsar, que OpenNOW responda por perfil, jitter/pérdida reales en una sesión de juego).

**Objetivo:** las 7 pantallas pedidas, sin botones decorativos.

- **TIPO DE MANDO** (teléfono, nueva): tarjetas visuales Xbox 360,
  Xbox One, DualShock 4, DualSense, genérico. Al elegir: guarda la
  selección (DataStore, ver abajo), aplica el mapeo, pide a la TV
  recrear el dispositivo (o avisa si hay que reiniciar el servicio),
  comprueba por el estado que devuelve la TV que Android lo reconoce
  y permite volver al anterior. Archivos nuevos: pantalla en
  `app-phone/.../ui/ControllerTypeScreen.kt`, `GamepadProfile` en
  `:core`, guardado en DataStore. Criterio: cambiar de perfil y ver
  el nombre nuevo en el diagnóstico de la TV.
- **PRUEBA DE MANDO** (TV y teléfono): en la TV, una pantalla que
  (1) muestra el dispositivo virtual actual (nombre, id, vendor/
  product, ejes y botones leídos del sistema), (2) una prueba
  interactiva: checklist por control (cada stick movido, cada botón
  pulsado, cada gatillo) con valores analógicos en vivo leídos del
  dispositivo (no de la red), (3) las 4 etapas con su veredicto
  propio: teléfono envía / receptor recibe / Android registra /
  OpenNOW recibe (la 4ª solo se puede marcar tras probar un juego;
  la pantalla debe decirlo así, no marcarla sola). En el teléfono,
  la misma prueba en pequeño (qué se está enviando). Archivos:
  ampliar `Diagnostics.kt` (botones, valores en vivo) y una
  `TestScreen` en la TV; en el teléfono, una pantalla de prueba
  simple. Criterio: pulsar cada botón y verlo marcado en la
  checklist, con el valor analógico real.
- **Perfiles por juego** (teléfono): DataStore con un perfil por
  juego (layout, tipo de mando, sensibilidad, zona muerta, curva,
  gatillos, vibración, remap). Detección de app en primer plano en
  el teléfono no sirve para GeForce NOW (el juego corre en la nube);
  queda selección manual por juego, como pide la especificación.
  Archivos nuevos: `GameProfiles` (DataStore) + pantalla Perfiles
  (sustituir `TodoScreens.kt:20`). Criterio: dos juegos con
  sensibilidades distintas guardadas y aplicadas.
- **Rendimiento** (teléfono): latencia (ping), jitter (variación del
  ping), paquetes/s, paquetes perdidos (por secuencia), método
  activo. Los datos ya existen en parte (`lastPingMs`,
  `packetsReceived`, secuencia); falta exponerlos y graficarlos.
  Criterio: ver el jitter real en una sesión de 1 minuto.

**Riesgos:** la lectura de valores en vivo del dispositivo virtual
requiere leer `/dev/input/event*` desde el proceso shell (Shizuku)
o usar `InputDevice.getMotionRange()` (que da rangos, no valores
actuales); si no se puede, la prueba interactiva se hace contra el
último estado recibido por red, y hay que decirlo claro en la
pantalla.

### Fase 4 — Controles extra (L4/R4, turbo, macros, vibración, teclado, ratón, giroscopio)

> **ESTADO — IMPLEMENTADO (2026-11-07):** código real en `main`, CI en
> verde (runs 42423601658 y 42424085842). **SIN VERIFICAR en TV
> real:** todo lo decisivo (L4/R4 por `getevent`, turbo, macros,
> vibración al pulsar, teclado en el chat de GeForce NOW, ratón en
> un menú, giroscopio) sigue pendiente de la Fase 0/6 — que además
> sigue bloqueada: Shizuku no está instalado en la TV del usuario
> y el teléfono aún no ha conectado (0 paquetes). CI en verde no es
> verificación en dispositivo.
>
> 1. **L4/R4 — IMPLEMENTADO:** byte 29 del protocolo (bit 0 = L4,
>    bit 1 = R4; fuera del CRC, como el profileId del byte 28),
>    botones en pantalla editables/borrables en el editor, evdev
>    `BTN_TRIGGER_HAPPY2/3` (0x2C1/0x2C2) en `uinput_gamepad.c`.
>    El bitmask de 16 NO se tocó. El remapeo por juego NO cubre
>    L4/R4. Por Bluetooth HID no van como botones propios (el
>    descriptor no tiene hueco), pero llegan como L3/R3 por el
>    remapeo de paletas (ronda REA GFN+OpenNOW, 2026-11-11).
> 2. **Turbo — IMPLEMENTADO (solo teléfono):** `Turbo.kt` en
>    `:core` + generador en `GamepadViewModel` (10 Hz por defecto,
>    5–20 Hz en la UI, botones A/B/X/Y/LB/RB), aplicado ANTES del
>    remapeo. Sin cambios en la TV.
> 3. **Macros — IMPLEMENTADO (solo teléfono):** `Macros.kt`
>    (graba botones físicos con tiempos, máx. 30 s / 200 eventos)
>    + UI grabar/nombrar/reproducir/borrar en `ExtrasScreen`.
> 4. **Vibración — IMPLEMENTADO (ronda REA GFN+OpenNOW, 2026-11-11):** vibración al pulsar en el teléfono
>    SÍ (`Vibrator`, 15 ms, respeta el ajuste del perfil de juego).
>    **Vibración DEL JUEGO (rumble) SÍ, solo por Bluetooth HID:** el descriptor HID declara force feedback
>    (colección PID USB 1.0, solo fuerza constante, reportes 2-7; `RumbleReport.kt` en `:core`) y
>    `BluetoothHidService` recibe los reportes del host (Set Effect + Set Constant Force + Effect Operation,
>    por control o por interrupción) y `GamepadViewModel.triggerGameRumble()` hace vibrar el teléfono (un solo
>    motor: fuerte y débil colapsan al máximo; interruptor propio en Extras, por defecto SÍ). **Por Wi-Fi →
>    uinput NO se puede:** leer EV_FF del dispositivo virtual exige /dev/uinput en lectura y Shizuku está
>    descartado en esta TV; queda como límite documentado, no como hecho. Sin probar en dispositivo real: que
>    el kernel de la TV cree el vibrador (`dumpsys input`), que el juego mande rumble y que la entrada no se
>    haya movido con el Report ID (1) nuevo.
> 5. **Teclado virtual y modo ratón — IMPLEMENTADO por uinput en
>    la TV:** dispositivos SEPARADOS ("Ultimate Gamepad Keyboard"
>    0x1234/0x5679, "Ultimate Gamepad Mouse" 0x1234/0x567A), nunca
>    mezclados con el gamepad; paquetes de 8 bytes `UK`/`UM` por
>    UDP 51999; la TV los crea perezosamente y los destruye con
>    ACTION_DESTROY. Vía HID descartada (segundo `registerApp`,
>    sin probar). Solo UDP.
> 6. **Giroscopio — IMPLEMENTADO (solo teléfono + protocolo
>    normal):** sensor de rotación → stick derecho (`GyroMapper`),
>    interruptor + sensibilidad 0,5–2. **Desviación del plan:** NO
>    se inventaron ejes nuevos de protocolo (el plan pedía
>    "protocolo v2: 2-3 floats más"); los bytes reservados son
>    pocos y ya los usan profileId y L4/R4. La convención de signo
>    exacta está pendiente de verificar en el teléfono real.

**Objetivo:** completar el §9, en orden de valor/riesgo. Cada uno
es un mini-proyecto; no se hacen todos a la vez.

1. **L4/R4**: necesitan 2 bits del protocolo (Fase 2, byte
   reservado) + 2 botones en la pantalla + 2 códigos evdev
   (`BTN_TRIGGER_HAPPY2/3` o los que use el perfil). Criterio:
   pulsar L4 y ver el evento por `getevent`.
2. **Turbo**: generador de pulsos en el teléfono (p. ej. 10 Hz
   mientras se mantiene el botón). Solo teléfono + protocolo, sin
   cambios en la TV. Criterio: mantener A con turbo y ver pulsos
   por `getevent`.
3. **Macros**: secuencia grabada de botones con tiempos, reproducida
   por el teléfono. Solo teléfono. Criterio: grabar A-B y
   reproducirla.
4. **Vibración**: el permiso ya existe; vibrar al pulsar (teléfono)
   SÍ. La vibración DEL JUEGO (la que pide el juego) SÍ por Bluetooth
   HID (ronda REA GFN+OpenNOW, 2026-11-11): descriptor PID + reportes
   del host + `Vibrator` en el teléfono. Por Wi-Fi → uinput NO se
   puede (leer `EV_FF`/`FF_RUMBLE` del mando virtual exige /dev/uinput
   en lectura y Shizuku está descartado en esta TV). Criterio:
   vibración al pulsar (teléfono) sí; vibración del juego por HID,
   solo tras probarla en la TV con un juego que vibre.
5. **Teclado virtual y modo ratón**: cada uno es OTRO dispositivo
   virtual (uinput teclado / uinput ratón) o un HID aparte. No van
   dentro del gamepad. Decidir si van por uinput (TV) o por HID
   (teléfono): por HID el teléfono puede exponer un teclado/ratón
   además del gamepad (otro `registerApp`); por uinput la TV crea
   los dispositivos. Criterio: escribir en el chat de GeForce NOW
   con el teclado virtual y mover el cursor en un menú con el modo
   ratón.
6. **Giroscopio**: leer el sensor del teléfono y enviarlo como ejes
   nuevos (protocolo v2: 2-3 floats más) y mapearlos en la TV a
   ejes del mando (p. ej. al stick derecho). Criterio: inclinar el
   teléfono y ver moverse el stick en `getevent`.

**Riesgos:** cada extra alarga el protocolo y el dispositivo; el
teclado/ratón pueden hacer que Android reclasifique el conjunto
como "control de TV" si se mezclan usages (mantener dispositivos
separados y no mezclar teclas de remoto en el gamepad).

### Fase 5 — Bluetooth datos SPP (implementada)

**Objetivo:** completar el transporte 5 (respaldo si falla TODO el
Wi-Fi). Trabajo documentado en `BluetoothDataTransport.kt:15-24`:
BLE con GATT server en la TV + cliente en el teléfono (el paquete
de 32 bytes no cabe en una notificación de 20 bytes: fragmentar en
2 o negociar MTU > 32) o SPP/RFCOMM (más simple, emparejamiento
clásico). El receptor TV (`BluetoothDataReceiverStub`) ya está
listo para recibir el mismo paquete.

**Criterio:** conectar por SPP y jugar 5 minutos sin Wi-Fi.
**Riesgo:** es el transporte con más trabajo y menos valor si el
Wi-Fi ya es estable; hacerlo solo si la Fase 0 muestra que el
Wi-Fi falla en la red del usuario. No es prioritario.

### Fase 6 — Verificación final en la Hyundai (la prueba definitiva)

Repetir la prueba del §12 de la especificación, con los 10 pasos,
por perfil (Xbox 360, DS4, DualSense):

1. Instalar el receptor en Google TV.
2. Iniciar el servicio de gamepad virtual.
3. Conectar el teléfono por Wi-Fi.
4. Seleccionar Xbox 360.
5. Comprobar que Android TV registra un gamepad.
6. Abrir GeForce NOW por OpenNOW.
7. Comprobar que el mando es reconocido.
8. Probar un juego compatible con mando.
9. Repetir con DualShock 4 y DualSense.
10. Comprobar sticks, cruceta y gatillos analógicos.

Cada paso con su sí/no y su evidencia (`dumpsys input`,
`getevent`, captura del diagnóstico). Si un perfil falla, se
analizan sus eventos y se corrige su descriptor o su mapa, no se
sigue adelante con el siguiente perfil.

## 5. Perfiles visuales vs. perfiles de emulación (distinción obligatoria)

- **Perfil visual:** cómo se dibuja el mando en el teléfono
  (colores y etiquetas Xbox/PlayStation, posición de los botones).
  Cambiarlo solo cambia la UI. Es barato y no afecta a la TV.
- **Perfil de emulación:** qué dispositivo virtual crea la TV
  (nombre, vendor/product, descriptor HID, tabla de botones/ejes)
  y qué mapeo usa el protocolo. Cambiarlo recrea el dispositivo
  uinput y cambia el descriptor HID. Es lo que OpenNOW ve.
- Regla: la pantalla TIPO DE MANDO cambia **los dos a la vez** y lo
  dice así; nunca se muestra un mando de PlayStation mientras la TV
  expone un Xbox 360. El estado guardado incluye el `profileId` y
  la TV lo aplica al conectar (el teléfono lo envía en el byte
  reservado del protocolo, Fase 2).
- El cambio en caliente es: teléfono envía `profileId` nuevo →
  TV destruye el dispositivo actual (`destroyVirtualGamepad()`) →
  crea el nuevo con su definición → comprueba por `Diagnostics`
  que el nombre cambió → informa al teléfono. Si OpenNOW no
  re-enumera el mando nuevo sin reiniciarse, la UI debe decir
  "reinicia OpenNOW" (a verificar en la Fase 0/2).

## 6. Limitaciones: si /dev/uinput no es accesible en esta TV

Lo que se sabe hoy: nada. No hay ninguna comprobación en el código
ni en la documentación sobre si esta Hyundai tiene `/dev/uinput`,
qué SELinux aplica, ni qué Android TV lleva. La Fase 0 responde
esto. Según su resultado:

| Resultado de la Fase 0 | Qué se necesita / alternativa |
|---|---|
| `/dev/uinput` existe y se abre desde shell (uid 2000) | Nada más: Shizuku + ADB :5555 basta. Seguir el plan. |
| El nodo existe pero SELinux lo deniega (`Enforcing` + `avc: denied` en logcat) | Sin root no hay forma de cambiar la política SELinux. Alternativas: `injectInputEvent` (B) o Bluetooth HID. Anotar el `avc` exacto como evidencia. |
| No existe `/dev/uinput` (kernel sin `CONFIG_INPUT_UINPUT`) | No hay mando virtual por uinput en esta TV. Alternativas: B, HID, o un TV Box externo / dongle con kernel con uinput. |
| No hay ADB por red en la TV (Shizuku no arranca) | Sin Shizuku no hay ni A ni B. Alternativas: Bluetooth HID directo (sin receptora) o un mando físico Bluetooth. |
| Shizuku arranca pero se muere / es inestable | Repetir arranque tras cada reinicio; documentar la inestabilidad; el fallback es HID. |

**Límites del fallback B (`injectInputEvent`):** inyecta
`KeyEvent`/`MotionEvent` con `SOURCE_GAMEPAD` en la tubería global,
pero **no crea un `InputDevice` persistente**. OpenNOW enumera
mandos al arrancar: si no hay ninguno, puede no aceptar la entrada
en juego aunque el menú se mueva. Por eso la Fase 0 prueba B en un
juego, no solo en un menú. Además el `MotionEvent` inyectado lleva
`deviceId = 0` (`InputMapper.kt:78`); si OpenNOW lo rechaza por
eso, es señal de que hace falta uinput.

**Lo que NUNCA es alternativa válida:** AccessibilityService (no
lee sticks ni gatillos analógicos, `docs/VIABILITY-OPENNOW.md` §2)
ni pulsaciones de teclado / eventos D-pad (eso es exactamente el
bug del "control de TV").

## 7. Lo que NO se debe prometer

- **Háptica avanzada ni gatillos adaptativos del DualSense** sin
  implementación real: necesitan reportes HID feature que uinput
  no expone. No anunciarlos en la UI hasta que existan.
- **Compatibilidad con juegos que no admiten mando**: si el juego
  no admite mando, ningún perfil lo arregla. La UI debe decirlo.
- **Cero pérdidas en UDP**: imposible por protocolo. Lo que se
  promete es: reenvío del estado completo a 120 Hz (una pérdida se
  corrige en 8 ms), fallback a TCP y liberación de controles al
  perder la conexión.
- **Que el Bluetooth de la TV no se caiga**: la radio es hardware.
  Lo que se promete es reconexión automática + fallback inmediato
  a Wi-Fi (ya implementado).
- **Que OpenNOW reconozca el mando sin probarlo en la TV**: es la
  Fase 0 y la Fase 6. CI en verde no es verificación en dispositivo.
- **Touchpad de DS4/DualSense en la primera versión**: solo si se
  implementa como dispositivo aparte (Fase 2, opción b) y se prueba.

## 8. Riesgos críticos (resumen)

1. **`/dev/uinput` inaccesible en esta Hyundai** → todo el plan de
   emulación real cae; queda B/HID. Es el riesgo nº 1 y solo la
   Fase 0 lo resuelve.
2. **OpenNOW no re-enumera el mando al cambiar de perfil** → el
   cambio en caliente exige reiniciar OpenNOW; hay que probarlo.
3. **TCP no inyecta hoy (H1)** → si no se corrige en la Fase 1, el
   "respaldo TCP" es falso.
4. **Botones atascados al perder la conexión (H2)** → sin el
   watchdog, un corte de Wi-Fi deja el mando pulsado en el juego.
5. **El descriptor HID lo rechaza la TV** → Bluetooth HID es
   firmware-dependiente; probarlo en la Fase 0 antes de prometerlo.
6. **Alcance**: 6 fases con 2 dispositivos virtuales extra
   (teclado/ratón), touchpad y SPP son mucho trabajo; el orden
   es Fase 0 → Fase 1 → Fase 2 (perfiles) → Fase 3 (pantallas) →
   Fase 4 (extras por valor) → Fase 5 (SPP, implementada)
   → Fase 6 (verificación final). No empezar la Fase 2 sin la 0.

## 9. Primera fase recomendada

**Fase 0, sin escribir código.** Instalar los APK actuales
(run 42408588893), arrancar Shizuku por ADB :5555 con Bugjaeger y
recorrer los 10 puntos de la Fase 0, anotando cada sí/no con su
evidencia (`dumpsys input`, `getevent`, diagnóstico de la TV,
OpenNOW). Esa lista decide si el plan sigue como está (uinput
viable) o se reorienta (B/HID). En paralelo, y sin esperar a la
TV, se puede hacer la Fase 1 en código (TCP, liberación de
controles, heartbeat TCP, limpieza de documentación), porque no
depende de la respuesta de la Fase 0.

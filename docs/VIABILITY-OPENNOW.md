# Viabilidad — OpenNOW, Wi-Fi y mando virtual

Fecha: 2026-11-07. Decisión del usuario: Wi-Fi local como ruta principal, Bluetooth no fiable, OpenNOW como cliente objetivo.

## 1. Qué es OpenNOW y qué necesita

OpenNOW Android es un cliente no oficial y open-source de GeForce NOW (Kotlin + Jetpack Compose, WebRTC/MediaCodec). Tiene controles táctiles propios y lee eventos de mando Android — `KeyEvent` / `MotionEvent` con source gamepad — y los codifica a XInput para enviarlos al servidor en la nube.

Consecuencia directa: **el receptor en la TV todavía necesita que Android vea un mando real**. No basta con que una app normal reciba paquetes UDP y los "reenvíe" por dentro: una app normal no puede crear un `InputDevice` virtual ni inyectar joystick a otra app. Si OpenNOW enumera mandos al arrancar y no encuentra ninguno, no hay entrada que codificar.

No se modifica OpenNOW en este proyecto y no se inventan APIs suyas. Todo el trabajo está en nuestro lado: teléfono → UDP → receptor TV → mando virtual del sistema.

## 2. Por qué AccessibilityService no sirve

`AccessibilityService` puede hacer gestos, clics y acciones de sistema, pero **no puede leer valores analógicos de sticks ni de gatillos**. Sirve para mapear un mando físico a toques en pantalla, no para crear un mando virtual que GeForce NOW / OpenNOW reconozca. Por eso no se usa como vía de entrada en este proyecto.

## 3. Vía viable sin root: Shizuku + /dev/uinput

Shizuku usa la infraestructura de depuración de Android para lanzar un servicio persistente como usuario shell (uid 2000). Las apps con permiso de Shizuku pueden enlazarse a ese servicio por Binder y actuar como shell, sin root.

Eso desbloquea dos cosas:
- `InputManager.injectInputEvent()` — el mismo método que usa `adb shell input`. Inyecta `KeyEvent` y `MotionEvent` en la tubería global de entrada.
- `/dev/uinput` — abrirlo desde el proceso shell y registrar un dispositivo virtual con `UI_DEV_SETUP` / `UI_DEV_CREATE`.

La segunda es la vía principal. El proyecto de referencia Virtual-Xbox-Controller-Android crea así un mando virtual Xbox 360 de todo el sistema vía Shizuku (uid shell). Un mando así aparece en `InputDevice.getDeviceIds()` y en Ajustes → Mandos, y es lo que OpenNOW necesita.

La primera, `injectInputEvent`, es solo fallback: inyecta eventos, pero **no registra un InputDevice**. Puede servir para navegar menús y puede que algunos clientes lo acepten en juego, pero no se puede afirmar que OpenNOW lo acepte al arrancar. La Etapa 0 debe probar ambas vías en la Hyundai real.

## 4. La limitación concreta de la Hyundai

Dato del usuario (2026-11-07): **la Hyundai Google TV no tiene la opción de depuración inalámbrica** en Opciones de desarrollador.

Por tanto:
- El arranque de Shizuku por "Wireless Debugging" (emparejamiento con código) **no aplica** aquí.
- El arranque válido es **ADB por red clásico, puerto 5555**, con el teléfono Samsung como host ADB (app Bugjaeger, gratuita, sin root):
  - TV: activar depuración USB/ADB y ADB por red en `:5555`.
  - Samsung con Bugjaeger: conectar a `<IP-TV>:5555` y ejecutar `sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`.
  - Conceder permiso a Ultimate Gamepad TV dentro de Shizuku.
  - Tras cada reinicio de la TV, hay que repetir el arranque de Shizuku. Sin root no hay arranque automático.
- Si la Hyundai no expone ADB por red o lo bloquea en su firmware, la Ruta B no arranca y habrá que documentarlo en Etapa 0 y buscar alternativa (por ejemplo, un mando Bluetooth físico como respaldo, o un TV Box externo). No se inventa que funciona.

## 5. Qué NO se ha verificado todavía

- Que el mando virtual uinput Xbox 360 se cree en esta Hyundai concreta.
- Que OpenNOW lo enumere y que un juego responda a A, sticks y gatillos.
- Latencia real por Wi-Fi local en la red del usuario (el ping del teléfono es eco UDP teléfono↔TV, no latencia de juego en la nube).
- Que Shizuku se mantenga estable en segundo plano en esta TV.

Ninguna de esas afirmaciones se hace en Etapa 1. La Etapa 0 es justamente esa prueba, con criterios de éxito escritos en `docs/SETUP-SIN-PC.md`.

## 6. Multi-transporte (decisión 2026-11-07): todos los métodos como fallback

"Hagamos todos los métodos de conexión posible por si algo falla".
Orden: 1) Wi-Fi UDP misma red, 2) Wi-Fi TCP misma red, 3) hotspot del
teléfono (mismo UDP/TCP, sin router), 5) Bluetooth datos a la app
receptora (Etapa 2), 4) Bluetooth HID directo. Modo Automático prueba
1 → 2 → 5 → 4. Inyección: A) Shizuku+uinput Xbox 360, B) Shizuku+
injectInputEvent, C) Bluetooth HID (directa). Ver `docs/ARCHITECTURE.md`.

## 7. Por qué otras apps se ven como control de TV y cómo lo evitamos

Bug reportado (2026-11-07): el usuario probó una app de gamepad de
Play Store y la TV la reconoció como si fuera un control de TV /
mando a distancia, no como mando de juego. OpenNOW / GeForce NOW
entonces solo la usa para navegar menús, no para jugar.

Causa probable: esa app enviaba eventos con SOURCE_DPAD /
SOURCE_KEYBOARD, o un descriptor HID genérico / de control remoto,
y Android la clasificó como mando a distancia.

Cómo lo evitamos (reglas obligatorias, ver `GamepadClassification.kt`):

1. Todos los eventos llevan `SOURCE_GAMEPAD | SOURCE_JOYSTICK`, nunca
   SOURCE_DPAD solo, nunca SOURCE_KEYBOARD, nunca SOURCE_TOUCHSCREEN.
2. KeyEvents: `KEYCODE_BUTTON_A/B/X/Y`, `BUTTON_L1/R1`, `BUTTON_START/
   SELECT`, `BUTTON_THUMBL/R`, cruceta como `KEYCODE_DPAD_*` siempre
   con source GAMEPAD y además por `AXIS_HAT_X/Y`. Nunca
   `KEYCODE_ENTER` ni `KEYCODE_BACK` para jugar.
3. MotionEvents: `AXIS_X/Y`, `AXIS_Z/RZ`, `AXIS_LTRIGGER/RTRIGGER`,
   `AXIS_HAT_X/Y`, siempre con `SOURCE_JOYSTICK`.
4. uinput: nombre "Xbox 360 Controller", vendor 0x045E, product
   0x028E, bustype BUS_USB. Solo EV_KEY y EV_ABS de mando.
   PROHIBIDO: KEY_POWER, KEY_HOME, KEY_VOLUME*, KEY_MENU, KEY_BACK,
   Usage Page Consumer / Remote Control.
5. Descriptor HID Bluetooth: Usage Page 0x01, Usage 0x05 Game Pad.
   Sin servicios extra de teclado/ratón/remoto.

## 8. Criterio de éxito de la Etapa 0: que lo reconozca como si fuera un mando real

No es "llegó el paquete". Es todo esto a la vez:

- El dispositivo expuesto se llama **"Xbox 360 Controller"** — nunca
  "Virtual", nunca "Ultimate Gamepad" como nombre de dispositivo.
- Identidad: vendor 0x045E, product 0x028E, bustype USB (uinput),
  mismo mapeo que un Xbox 360 físico. En Bluetooth HID igual.
- InputManager lo lista, `getSources()` incluye GAMEPAD+JOYSTICK.
- OpenNOW lo lista como controller y lo codifica a XInput como un
  mando físico.
- La pantalla de diagnóstico de la TV (`Diagnostics.kt`) dice
  **"Parece mando real: SÍ"** con el motivo comprobado, no con lo
  que cree la app.

Si un método no logra esa identidad, se marca **FALLO** en la UI
aunque el Wi-Fi funcione.

## 9. Bluetooth: por qué no es el principal

El usuario reportó que Bluetooth a veces no funciona en su caso. Además, el soporte de `BluetoothHidDevice` depende del firmware del teléfono. Por eso Bluetooth es respaldo (HID directo y datos a la receptora), no el principal. El principal sigue siendo Wi-Fi local.

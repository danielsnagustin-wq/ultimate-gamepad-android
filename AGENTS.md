# AGENTS.md — Ultimate Gamepad Android

> Lee este archivo antes de tocar código. Está en español simple, a propósito.
> Si algo de aquí contradice al README, manda lo de aquí: el README de la
> Etapa 1 quedó desactualizado en varios puntos.

## Qué es esta app

Mando virtual para Android: el teléfono se convierte en un mando real para
una **Hyundai Google TV** con **OpenNOW** (cliente no oficial y open-source
de GeForce NOW, **no** la app oficial de NVIDIA).

- App del teléfono (`:app-phone`, `com.ultimate.gamepad.phone`): mando
  virtual, editor de layout, perfiles, extras.
- App de la TV (`:app-tv`, `com.ultimate.gamepad.tv`): receptora, muestra
  diagnóstico en pantalla.
- Librería común (`:core`): protocolo, transportes, descubrimiento,
  emparejamiento.

El usuario **no tiene PC**: todo se compila solo con GitHub Actions.
Nunca hay emulador ni pruebas en local: CI en verde **no** significa
probado en el teléfono ni en la TV. Decirlo siempre así.

## Reglas que no se rompen

1. **Nada de adorno.** Cada botón, ajuste y pantalla hace algo real o se
   quita. No simular que una pulsación funciona si el juego no puede
   recibirla.
2. **Nunca entregar un APK como "listo" con errores pendientes.** Después
   de cada run de GitHub Actions: causa exacta del error → arreglo →
   nuevo run, en bucle, error por error. Reportar por arreglo verificado
   (commit + run + artefactos).
3. **Leer el código antes de tocarlo.** Verificar nombres, valores y
   comportamientos reales. No inventar APIs ni funciones.
4. **No quitar funciones sin consultar.** No sustituir implementaciones
   reales por simulaciones.
5. **No mencionar el modelo del teléfono** en descripciones, README ni
   comentarios. Solo "teléfono (MediaTek Helio G99 / Mali-G57 MC2)".
   En descubrimiento, la TV se anuncia como "TV Ultimate Gamepad".
6. **Shizuku está DESCARTADO del todo en esta TV** (no está adaptado a TV
   y la TV no tiene depuración de red ni inalámbrica, solo USB). La vía
   uinput/Shizuku está cerrada. La única vía para que el juego vea un
   mando es el **Bluetooth HID directo del teléfono**. No reabrir
   Shizuku ni proponerlo.

## Cómo se reconoce como mando real (criterio de éxito)

- Nombre expuesto exactamente **"Xbox 360 Controller"**, nunca
  "Virtual" ni "Ultimate Gamepad" como nombre de dispositivo.
- Vendor **0x045E**, product **0x028E**.
- Todas las entradas con `SOURCE_GAMEPAD | SOURCE_JOYSTICK`.
- Descriptor HID como Game Pad (Usage 0x05). Nunca teclas de mando de
  TV ni de teclado. El D-pad también por `AXIS_HAT_X/Y`.
- La TV muestra el diagnóstico "Parece mando real: SÍ / NO".

## Protocolo y transportes (no cambiar sin motivo)

- Paquete de **32 bytes**, magic `UG`, versión 1, CRC-16/CCITT-FALSE
  **solo sobre los bytes 0..25**, little-endian.
- Byte 28 = profileId, byte 29 = extraButtons (bit 0 = L4, bit 1 = R4).
- **Wi-Fi UDP puerto 51999** (principal), **Wi-Fi TCP puerto 52000**
  (respaldo), hotspot del teléfono, Bluetooth datos (SPP), Bluetooth HID.
- Orden automático: UDP → TCP → Bluetooth datos → Bluetooth HID.
- Descubrimiento: petición `UGDISC1`, respuesta
  `UGRSP1|<nombre>|<ip>|<puerto>|<pin>`. QR: `UGP1|<ip>|<puerto>|<pin4>`.
- Ningún método se muestra "Conectado" sin paquetes reales. El watchdog
  de la TV (2 s) suelta los controles si dejan de llegar paquetes.
- El servicio en primer plano debe llamar `startForeground()` de
  inmediato (si no, Android mata la app a los ~5-10 s:
  `ForegroundServiceDidNotStartInTimeException`).

## Cómo se compila (GitHub Actions, `.github/workflows/android.yml`)

- JDK 17 (temurin), Gradle 8.7.
- Tests: `gradle :core:testDebugUnitTest --stacktrace`
- Teléfono: `gradle :app-phone:assembleDebug --stacktrace`
- TV: `gradle :app-tv:assembleDebug --stacktrace`
- Artefactos: `app-phone-apks` y `app-tv-apks`. Se instala el
  `app-debug.apk` de cada artefacto **del mismo run**. El
  `app-release-unsigned.apk` no se puede instalar.
- Keystore debug fijo en el repo (`debug.keystore.b64`, alias
  `androiddebugkey`, contraseña `android`): no tocar, es lo que permite
  instalar una build encima de la anterior.

## Subir cambios

- Los archivos de workflow (`.github/workflows/*`) y los binarios (PNG)
  **solo se suben por el navegador** (github.com web UI). La API de
  `push_files` da 403 con ellos.
- `push_files` exige `files` como array de `{path, content}` y `path`
  siempre relativo al repo, nunca la ruta local.
- Verificar siempre el run del commit exacto pusheado antes de reportar.

## Referencias para no inventar

- Documentación al día de librerías: **Context7** (ver
  `~/workspace/custom-skills/context7-docs/SKILL.md` en la computadora
  de la asistente; sin clave, por API REST).
- Ejemplos oficiales de Google, clonados en local como consulta (no
  copiar código sin revisar licencia):
  `~/workspace/references/nowinandroid`,
  `~/workspace/references/compose-samples`,
  `~/workspace/references/architecture-samples`,
  `~/workspace/references/awesome-android-ui`.
- Estado del proyecto y decisiones: `docs/ARCHITECTURE.md`,
  `docs/VIABILITY-OPENNOW.md`, `docs/PLAN-GAMEPAD-PRO.md`.

<!-- antislop:start -->
## antislop

Para trabajo de UI, textos visibles o comentarios: lee `~/workspace/custom-skills/anti-slop/SKILL.md` (reglas anti-slop v3.2.28 adaptadas a Android/Compose; original en `upstream-antislop.md` en la misma carpeta). Modo por defecto: `after` para auditar lo que ya existe (lista numerada, el usuario elige qué arreglar), `during` al construir UI nueva. Antes de entregar UI, pasa la Delivery Gate de la skill. Dirección de diseño: mando estilo Xbox Series, AMOLED negro puro, acentos neón (acento configurable por el usuario); nada es decorativo, cada botón envía su bit real.
<!-- antislop:end -->

## Lecciones run #211 (ronda 2, 2026-11-11)
- **`PointerEventType.Cancel` NO existe en el Compose del proyecto.** La cancelación de un gesto llega como cancelación de la corrutina `pointerInput` (el `finally` libera el botón), no como un evento. No inventar miembros de un enum: si CI dice `Unresolved reference`, verificar el tipo en el Compose real antes de "arreglarlo" con una rama que no compila.
- **`compareByDescending { ... }.thenBy { ... }` encadenado no siempre infiere `T`.** Poner el tipo explícito (`compareByDescending<DiscoveredDevice> { ... }`) o guardar el comparador en un `val` con nombre. La puerta `check_push.py` NO caza errores de tipos Kotlin: solo CI los ve.

## Lección run #227 (ronda anti-slop, 2026-11-12)
- **Cambiar una constante de LayoutGeometry puede romper el suelo del ajuste.** `SHOULDER_ROW_H` 40→48 quitó 8 dp de alto disponible y el ajuste honesto en las pantallas más bajas con escala 1.6 cayó a 0.437, por debajo del suelo `MIN_FIT` 0.45: el ajuste se quedaba en 0.45 y el contenido se pasaba 8 dp. El test `en horizontal el contenido escalado cabe en alto y ancho` lo cazó (1 de 90). Arreglo: `MIN_FIT` 0.45→0.43 (el peor caso honesto es 0.437, verificado por simulación).
- **Al tocar constantes de geometría, simular TODAS las aserciones del test, no solo los valores de ancla.** La simulación de la ronda solo recalculó anclas (192f/0.7077) y no corrió la aserción del ajuste. Patrón: `~/workspace/ug-fixes/sim-fit-227.py` reproduce los 40 casos del test con las constantes viejas (0 fallos) y nuevas (6 fallos) antes de pushear.

## Lección run #240 (hueco ronda 2, 2026-11-12)
- **Los nombres de test con backticks NO pueden llevar `:`.** El test nuevo `contrato de acciones del raton: 0 mover-clic, 2 crear, 3 destruir` tumbó el run #240 con `Name contains illegal characters: :` en `:core:compileDebugUnitTestKotlin` (cero tests ejecutados, phone/TV ni corrieron). El `:` es ilegal en nombres de método JVM aunque vaya entre backticks. Arreglo: `:` → ` - `. Regla: en nombres de test con backticks, solo letras, números, espacios, guiones y comas; nunca `:`, `<`, `>` ni `/`. La puerta `check_push.py` NO caza este error: solo CI lo ve.

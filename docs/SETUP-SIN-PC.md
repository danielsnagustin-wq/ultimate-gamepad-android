# Configuración sin PC — solo con el teléfono Android

> **ESTADO ACTUAL 2026-11-11 (ronda 2):** la §3 de Shizuku de este documento está **anulada** (Shizuku descartado: no está adaptado a TV y la TV no tiene depuración de red). El camino para jugar es el **Bluetooth HID directo** (§3 nueva). Por Wi-Fi la TV recibe y muestra los paquetes en vivo, pero no los inyecta en el juego.

## 1. Crear el repositorio (1 minuto, desde la app de GitHub)

> Bloqueo conocido (2026-11-07): la creación automática del repo desde la integración de GitHub falló con `403 Resource not accessible by integration`. La integración no tiene permiso para crear repositorios nuevos en la cuenta. Por eso este paso lo hace el usuario a mano; es un solo repo y no se vuelve a repetir.

1. En el teléfono, abre la app de GitHub → `+` → **New repository**.
2. Nombre: `ultimate-gamepad-android`. Descripción: la del README. **Public**. Sin README inicial, sin .gitignore, sin licencia (ya vienen en los archivos).
3. **Create repository**.
4. Sube los archivos de Etapa 1: en la web de GitHub (Chrome en el teléfono) → el repo nuevo → **Add file → Upload files** → selecciona la carpeta del proyecto, o usa la app de GitHub para subir por lotes. Alternativa: pide a Coco que suba los archivos una vez creado el repo vacío.
5. Ve a **Actions** → espera a que termine el workflow `Android CI` → **Artifacts** → descarga `app-phone-apks` y `app-tv-apks`.

Si el workflow falla, abre el log del paso que falló y copia el error: sin ese log no se puede diagnosticar.

## 2. Instalar las apps

- **Teléfono:** instala `app-phone-debug.apk`. Acepta instalar apps desconocidas solo para esa instalación.
- **TV Hyundai:** instala `app-tv-debug.apk`. Si la TV no deja instalar APKs por USB, usa la app **Send Files to TV** (o similar) desde el teléfono, o un USB.

Versiones: teléfono con procesador MediaTek Helio G99 (Mali-G57 MC2), Android con minSdk 26. TV Hyundai Google TV, Android TV / Google TV.

## 3. Bluetooth HID directo — el camino para jugar (Etapa 0)

La Hyundai **no tiene depuración inalámbrica** (solo "Depuración por USB") y **Shizuku está descartado del todo**: no hay ADB por red (:5555) que arrancar ni Shizuku que instalar. No busques esa opción: no está y no se usa.

### 3.1 Emparejar el teléfono como mando

Hay dos direcciones y las dos funcionan (usa la que te resulte más fácil):

**A. La TV busca al teléfono (recomendada):**

1. Teléfono: abre Ultimate Gamepad → Ajustes → **Bluetooth HID**. El teléfono se registra como "Xbox 360 Controller" y se hace visible 300 s (acepta el diálogo "hacer visible" si aparece).
2. TV: Ajustes → Bluetooth → buscar dispositivos → empareja **"Xbox 360 Controller"**.
3. El teléfono debe decir "Conectado" por Bluetooth HID.

**B. El teléfono busca a la TV:**

1. Teléfono: Ultimate Gamepad → Ajustes → **"Buscar TV por Bluetooth"** → elige tu TV en la lista (las que parecen TV salen primero con 📺) → emparejar.
2. La TV completa el emparejamiento HID y el teléfono queda "Conectado".

> Si cambiaste de versión del mando, **re-empareja**: la TV guarda el descriptor HID viejo en caché y sin re-emparejar el rumble y los botones nuevos no aparecen.

### 3.2 Si el Bluetooth de la TV se desconecta solo

La radio Bluetooth de esta TV es débil (hardware/firmware, la app no puede arreglarla). La app lo compensa sola: WifiLock, reconexión y fallback inmediato a Wi-Fi UDP. Consejos: teléfono cerca de la TV, y **pon el teléfono en la Wi-Fi de 5 GHz** (el Bluetooth y la Wi-Fi de 2,4 GHz se estorban; en 5 GHz el Bluetooth respira mejor).

## 4. Conectar teléfono y TV

1. En la TV, abre Ultimate Gamepad TV → **Iniciar receptor UDP**. Anota IP, puerto y PIN (o el QR en texto).
2. En el teléfono, abre Ultimate Gamepad → **Conectar** → **Escanear QR** (apunta al QR de la TV; es CameraX + ML Kit, real) o **IP / PIN** → escribe IP y PIN de 4 dígitos.
3. La barra debe decir **Conectado** y el ping debe dejar de ser "—" cuando la TV devuelva el eco. Si el ping sigue en "—", la TV recibe pero no devuelve eco: revisa que ambas estén en la misma red y que el receptor esté iniciado.

## 5. Etapa 0 — criterios de éxito (anotar sí/no)

**Fase 1 (Wi-Fi, sin jugar):** 1–4. **Fase 2 (mando real por Bluetooth HID):** 5–7.
Probar en este orden y anotar el resultado de cada uno:

1. [ ] Teléfono conectado, ping con valor (no "—").
2. [ ] Al mover el joystick / pulsar A, la pantalla de la TV muestra la entrada en vivo y cuenta paquetes recibidos.
3. [ ] Si se apaga el Wi-Fi 3 s, la TV dice "Sin conexión"; al volver, se reconecta sola.
4. [ ] Hotspot (si se prueba): TV conectada al hotspot del teléfono, mismo resultado que con router.
5. [ ] Teléfono emparejado como **"Xbox 360 Controller"** por Bluetooth HID (desde la TV o desde el teléfono: ambas direcciones valen).
6. [ ] **Parece mando real: SÍ** en el diagnóstico de la TV (nombre "Xbox 360 Controller", GAMEPAD+JOYSTICK, ejes completos). Matiz: el vendor/product 0x045E/0x028E por HID no se puede fijar por la API (son los del teléfono); lo que decide es que OpenNOW lo liste como controller.
7. [ ] En OpenNOW, con el mando por Bluetooth HID, ¿responde el menú / el juego a A y al joystick? ¿Lo lista OpenNOW como controller?

Con esos 7 sí/no, se decide si falta algo en el descriptor, en el emparejamiento o en el fallback. No se da nada por terminado sin esos datos. **Si Wi-Fi funciona pero "Parece mando real" es NO, el método es FALLO, no éxito.**

## 6. Qué preguntar a Coco con el resultado

Copia y pega: versión de Android de la TV (Ajustes → Información), modelo exacto de Hyundai, si el emparejamiento Bluetooth HID funcionó o qué error dio, y los 7 sí/no de arriba.

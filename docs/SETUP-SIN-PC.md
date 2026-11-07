# Configuración sin PC — solo con el teléfono Android

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

## 3. Shizuku en la Hyundai — paso crítico (Etapa 0)

La Hyundai **no tiene depuración inalámbrica**. No busques esa opción: no está.

### 3.1 Instalar Shizuku y Bugjaeger

- En la TV: instala **Shizuku** (`moe.shizuku.privileged.api`) desde Play Store. Si no aparece en la TV, descarga su APK en el teléfono y envíalo a la TV.
- En el teléfono: instala **Bugjaeger** (gratis, sin root) desde Play Store. Es el host ADB.

### 3.2 Activar ADB por red en la TV

1. TV → Ajustes → Sistema → Información → pulsa 7 veces en **Compilación** hasta activar Opciones de desarrollador.
2. Ajustes → Opciones de desarrollador → activa **Depuración USB / ADB**.
3. Activa **ADB por red** o **Depuración por red** si aparece, y anota la IP de la TV (Ajustes → Red → Wi-Fi → tu red). El puerto es el **5555**.

Si la TV no muestra ninguna opción de ADB por red, anótalo: es un resultado de Etapa 0 (bloqueo) y habrá que buscar otra vía.

### 3.3 Arrancar Shizuku con Bugjaeger

1. Teléfono y TV en la **misma Wi-Fi**.
2. Bugjaeger → icono de enchufe / `adb connect` → escribe `<IP-TV>:5555` → Connect.
3. Cuando conecte, abre la consola de Bugjaeger (shell) y ejecuta exactamente:
   ```
   sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
   ```
4. En la TV, abre Shizuku: debe decir que el servicio está corriendo.
5. En Shizuku → busca **Ultimate Gamepad TV** → **Permitir**.
6. Abre Ultimate Gamepad TV: la pantalla debe decir **Shizuku: ACTIVO**. Si dice otra cosa, sigue su guía en pantalla.

> Tras cada reinicio de la TV, Shizuku se detiene. Repite el paso 3.3. Sin root no hay arranque automático.

## 4. Conectar teléfono y TV

1. En la TV, abre Ultimate Gamepad TV → **Iniciar receptor UDP**. Anota IP, puerto y PIN (o el QR en texto).
2. En el teléfono, abre Ultimate Gamepad → **Conectar** → **Escanear QR** (apunta al QR si la TV lo muestra en una etapa posterior; en Etapa 1 el QR es texto, usa **IP / PIN**) → escribe IP y PIN de 4 dígitos.
3. La barra debe decir **Conectado** y el ping debe dejar de ser "—" cuando la TV devuelva el eco. Si el ping sigue en "—", la TV recibe pero no devuelve eco: revisa que ambas estén en la misma red y que el receptor esté iniciado.

## 5. Etapa 0 — criterios de éxito (anotar sí/no)

**Fase 1 (Wi-Fi, sin Shizuku):** 1–4. **Fase 2 (mando real):** 5–7.
Probar en este orden y anotar el resultado de cada uno:

1. [ ] Teléfono conectado, ping con valor (no "—").
2. [ ] Al mover el joystick / pulsar A, la pantalla de la TV muestra la entrada en vivo y cuenta paquetes recibidos.
3. [ ] Si se apaga el Wi-Fi 3 s, la TV dice "Sin conexión"; al volver, se reconecta sola.
4. [ ] Hotspot (si se prueba): TV conectada al hotspot del teléfono, mismo resultado que con router.
5. [ ] Shizuku activo en la TV tras el arranque por Bugjaeger (ADB :5555; la Hyundai no tiene depuración inalámbrica).
6. [ ] **Parece mando real: SÍ** en el diagnóstico de la TV (nombre "Xbox 360 Controller", vendor 0x045E, product 0x028E, GAMEPAD+JOYSTICK, ejes completos). En Etapa 1 se espera **NO** — la librería uinput nativa falta. Anotar NO con su motivo es un resultado válido, no un fracaso: es lo que define el trabajo de Etapa 0.
7. [ ] En OpenNOW, con el mando virtual o el fallback, ¿responde el menú / el juego a A y al joystick? ¿Lo lista OpenNOW como controller?

Con esos 7 sí/no, se decide si Etapa 0 es uinput nativo, si basta injectInputEvent, o si hace falta otra vía. No se pasa a Etapa 2 (editor, stick derecho, gatillos) sin esos datos. **Si Wi-Fi funciona pero "Parece mando real" es NO, el método es FALLO, no éxito.**

## 6. Qué preguntar a Coco con el resultado

Copia y pega: versión de Android de la TV (Ajustes → Información), modelo exacto de Hyundai, si Shizuku arrancó o qué error dio Bugjaeger, y los 6 sí/no de arriba.

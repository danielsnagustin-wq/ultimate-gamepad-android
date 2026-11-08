package com.ultimate.gamepad.tv.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.ultimate.gamepad.core.GamepadProtocol

/**
 * Anuncio NSD/mDNS de la TV (descubrimiento automático, NUEVO).
 *
 * La TV se anuncia en la red local como servicio DNS-SD para que
 * el teléfono la encuentre sin broadcast UDP y sin escribir la IP:
 * tipo "_ultimate-gamepad._tcp.", nombre "TV Ultimate Gamepad",
 * puerto 51999 (el mismo que UDP/TCP de juego: el teléfono
 * conecta al puerto anunciado, no a uno distinto).
 *
 * El broadcast UDP ("UGDISC1" -> "UGRSP1|...|pin") SIGUE siendo
 * el camino principal y queda intacto: NSD es ADICIONAL. El
 * broadcast es el único que entrega el PIN; NSD no lo anuncia
 * nunca (ver abajo), así que una TV encontrada solo por NSD
 * necesita que el usuario escriba el PIN que muestra la TV (o
 * que el broadcast la haya visto también y aporte el PIN al
 * fusionar resultados en el teléfono).
 *
 * SIN atributos TXT: a propósito. El nombre visible es el nombre
 * del servicio (NsdServiceInfo.serviceName); no hay nada más que
 * anunciar. El PIN NO va en TXT: mDNS lo vería cualquiera en la
 * red y el PIN es el único secreto que protege la conexión.
 *
 * Permisos: NINGUNO nuevo. Registrar un servicio NSD solo exige
 * INTERNET + ACCESS_NETWORK_STATE, que la app de TV ya declara.
 * No hace falta MulticastLock para anunciar (eso es solo para
 * descubrir en el teléfono) ni permiso de localización: NSD no
 * es un escaneo Wi-Fi.
 *
 * Ciclo de vida: start() al arrancar el receptor, stop() al
 * pararlo (ver cableado en el manifiesto w6: UdpReceiverService
 * .onStartCommand/onDestroy). El registro vive mientras el
 * receptor escucha; si la TV se apaga o se detiene el receptor,
 * el anuncio desaparece de la red (unregisterService).
 *
 * Nombre duplicado: si hay dos TVs "TV Ultimate Gamepad" en la
 * red, Android renombra el segundo anuncio a
 * "TV Ultimate Gamepad (2)" y registeredName guarda el nombre
 * real registrado. El teléfono distingue las TVs por IP, no por
 * nombre, así que el renombre no rompe nada.
 *
 * Re-registro: si el registro falla (p. ej. la red aún no está
 * lista al arrancar), se reintenta con backoff 1 s, 2 s, 4 s,
 * 8 s, 16 s (5 intentos). Pasados los 5 intentos se deja de
 * reintentar y lastError queda visible para la pantalla de la TV;
 * el broadcast UDP sigue funcionando mientras tanto. start() lo
 * puede volver a intentar en cualquier momento (p. ej. al pulsar
 * "Iniciar receptor" de nuevo).
 *
 * Hilos: NsdManager entrega sus callbacks en el hilo principal.
 * start()/stop() se pueden llamar desde cualquier hilo; el estado
 * expuesto (isRegistered / registeredName / lastError) es
 * @Volatile para que MainActivity lo lea sin más.
 */
object NsdAdvertiser {
    /** Tipo de servicio DNS-SD. Debe terminar en "._tcp.". */
    const val SERVICE_TYPE: String = "_ultimate-gamepad._tcp."

    /** Nombre con el que la TV se anuncia (sin modelo de TV). */
    const val SERVICE_NAME: String = "TV Ultimate Gamepad"

    /** Puerto anunciado: el mismo que UDP/TCP de juego. */
    const val SERVICE_PORT: Int = GamepadProtocol.DEFAULT_PORT // 51999

    /** Intentos máximos de re-registro tras un fallo. */
    private const val MAX_RETRY_ATTEMPTS: Int = 5

    /** ¿Está el servicio anunciado ahora mismo en la red? */
    @Volatile
    var isRegistered: Boolean = false
        private set

    /** Nombre real registrado (puede llevar " (2)" si había otra TV igual). */
    @Volatile
    var registeredName: String? = null
        private set

    /** Último error de registro/desregistro, para mostrar en la TV. null si todo bien. */
    @Volatile
    var lastError: String? = null
        private set

    private var nsdManager: NsdManager? = null
    private var appContext: Context? = null
    private var registering: Boolean = false
    /** Parada pedida mientras el registro estaba en curso (ver stop()). */
    private var stopPending: Boolean = false
    private var retryAttempt: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { retryRegister() }

    private val registrationListener: NsdManager.RegistrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            if (stopPending) {
                // Se paró mientras se registraba: quitar el anuncio
                // ahora mismo con el manager que sigue guardado. Sin
                // esto, el anuncio quedaba colgado en la red y un
                // stop() posterior no podía quitarlo (nsdManager ya
                // era null).
                stopPending = false
                registering = false
                runCatching { nsdManager?.unregisterService(this) }
                nsdManager = null
                appContext = null
                isRegistered = false
                registeredName = null
                return
            }
            isRegistered = true
            registeredName = serviceInfo.serviceName
            lastError = null
            retryAttempt = 0
            registering = false
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            isRegistered = false
            registeredName = null
            registering = false
            if (stopPending) {
                // Se paró mientras se registraba: no reintentar ni
                // dejar error; solo limpiar.
                stopPending = false
                nsdManager = null
                appContext = null
                return
            }
            lastError = "NSD: no se pudo anunciar la TV (${reason(errorCode)})."
            scheduleRetry()
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            isRegistered = false
            registeredName = null
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            isRegistered = false
            registeredName = null
            lastError = "NSD: no se pudo dejar de anunciar la TV (${reason(errorCode)})."
        }
    }

    /**
     * Empieza a anunciar la TV en la red local. Idempotente: si ya
     * está anunciada o en curso de registro, no hace nada. Guarda
     * solo el applicationContext (nunca una Activity) para no
     * retener la pantalla.
     */
    fun start(context: Context) {
        if (isRegistered || registering) return
        mainHandler.removeCallbacks(retryRunnable)
        retryAttempt = 0
        appContext = context.applicationContext
        registerNow()
    }

    /**
     * Deja de anunciar la TV y cancela los reintentos pendientes.
     * Idempotente: se puede llamar aunque nunca se anunciara.
     * unregisterService puede lanzar IllegalArgumentException si el
     * listener no estaba registrado: se traga a propósito.
     */
    fun stop() {
        mainHandler.removeCallbacks(retryRunnable)
        retryAttempt = 0
        // Si el registro está en curso (registerService ya llamado,
        // callback pendiente), no se puede desregistrar todavía:
        // unregisterService lanzaría IllegalArgumentException (el
        // listener aún no está registrado). Se marca pendiente y el
        // callback (onServiceRegistered / onRegistrationFailed)
        // termina de parar; el manager y el contexto se conservan
        // hasta entonces para poder desregistrar.
        if (registering && !isRegistered) {
            stopPending = true
            return
        }
        registering = false
        val manager = nsdManager
        val wasRegistered = isRegistered
        nsdManager = null
        appContext = null
        // Solo se desregistra si llegó a registrarse: si el registro
        // aún estaba en curso, unregisterService lanzaría
        // IllegalArgumentException (el listener no estaba registrado).
        if (manager != null && wasRegistered) {
            runCatching { manager.unregisterService(registrationListener) }
        }
        isRegistered = false
        registeredName = null
    }

    private fun registerNow() {
        val ctx = appContext ?: return
        val manager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            lastError = "NSD: no disponible en esta TV."
            return
        }
        nsdManager = manager
        registering = true
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = SERVICE_PORT
            // Sin setAttribute: el PIN no se anuncia (ver cabecera).
        }
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            registering = false
            lastError = "NSD: no se pudo anunciar la TV (${e.message ?: e.javaClass.simpleName})."
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (appContext == null || retryAttempt >= MAX_RETRY_ATTEMPTS) return
        // Backoff 1 s, 2 s, 4 s, 8 s, 16 s.
        val delayMs = 1000L shl retryAttempt
        retryAttempt++
        mainHandler.postDelayed(retryRunnable, delayMs)
    }

    private fun retryRegister() {
        if (isRegistered || registering || appContext == null) return
        registerNow()
    }

    private fun reason(errorCode: Int): String = when (errorCode) {
        NsdManager.FAILURE_INTERNAL_ERROR -> "error interno"
        NsdManager.FAILURE_ALREADY_ACTIVE -> "ya hay un anuncio activo"
        NsdManager.FAILURE_MAX_LIMIT -> "límite de servicios alcanzado"
        else -> "código $errorCode"
    }
}

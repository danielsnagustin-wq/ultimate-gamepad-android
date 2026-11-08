package com.ultimate.gamepad.phone.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.ultimate.gamepad.core.DiscoveredTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Descubrimiento de TVs por NSD/mDNS (NUEVO, adicional).
 *
 * Busca servicios "_ultimate-gamepad._tcp." en la red local con
 * NsdManager.discoverServices + resolveService por cada servicio
 * encontrado, y devuelve las TVs con host/puerto/nombre REALES
 * resueltos. Es el camino que cubre el caso que el broadcast UDP
 * no cubre: routers con aislamiento AP o que filtran el broadcast
 * (mDNS usa multicast 224.0.0.251, que muchos routers dejan pasar
 * aunque bloqueen el broadcast dirigido).
 *
 * El broadcast UDP (DiscoveryClient.discover, "UGDISC1") SIGUE
 * siendo el camino principal y queda intacto: NSD es ADICIONAL.
 * El cableado (ver manifiesto w6) llama a ambos y fusiona los
 * resultados por IP, sin duplicados.
 *
 * EL PIN NO VIAJA POR NSD. NsdServiceInfo no lleva el PIN (a
 * propósito: mDNS lo vería cualquiera en la red) y por eso los
 * DiscoveredTv devueltos aquí llevan pin = "" (vacío). Eso NO
 * significa "sin PIN": significa "PIN pendiente". Al fusionar
 * con el broadcast, si la misma IP aparece en ambos, se queda la
 * entrada del broadcast (que trae el PIN real). Una TV vista SOLO
 * por NSD necesita que el usuario escriba el PIN que muestra la
 * TV (o escanee su QR); el cableado NO debe conectar con pin ""
 * sin pedirlo antes. Nunca se devuelve una TV "encontrada" sin
 * IP y puerto resueltos de verdad: solo se añade en
 * onServiceResolved, con host no nulo y puerto válido; un
 * onServiceFound sin resolver no produce ninguna entrada.
 *
 * Timeout garantizado: la búsqueda dura timeoutMs (por defecto
 * 4 s) y siempre vuelve, haya TVs o no, con lista vacía si no se
 * resolvió ninguna. La parada es limpia en todos los casos
 * (finally): stopServiceDiscovery + soltar el MulticastLock,
 * también si la corrutina se cancela a mitad (el finally corre
 * igualmente).
 *
 * MulticastLock: se adquiere un WifiManager.MulticastLock durante
 * la búsqueda y se suelta en el finally. En este dispositivo hace
 * falta porque, con el ahorro de energía del Wi-Fi, Android puede
 * descartar los paquetes multicast mDNS y la búsqueda saldría
 * vacía aunque la TV esté anunciando. El permiso
 * CHANGE_WIFI_MULTICAST_STATE ya está declarado en el manifiesto
 * del teléfono; si adquirir el lock fallara, la búsqueda sigue
 * igualmente sin él (best effort, nunca crashea).
 *
 * Permisos: NINGUNO nuevo. NSD no exige permiso de localización
 * (no es un escaneo Wi-Fi); basta INTERNET + ACCESS_NETWORK_STATE
 * + CHANGE_WIFI_MULTICAST_STATE, los tres ya declarados en el
 * manifiesto del teléfono.
 *
 * Detalles:
 * - Solo IPv4: el juego va por UDP IPv4 (Discovery solo maneja
 *   IPv4 privadas). Una dirección resuelta IPv6 (contiene ":")
 *   se descarta, no se inventa una entrada IPv4.
 * - onServiceLost se ignora a propósito: la ventana es de 4 s y
 *   una TV que desaparece a mitad solo podría quedar listada
 *   esos segundos; borrar por nombre sería inseguro (el nombre
 *   puede llevar " (2)" si hay dos TVs iguales).
 * - NsdManager entrega los callbacks en el hilo principal y esta
 *   función corre en Dispatchers.IO: el mapa `found` se toca bajo
 *   synchronized en ambos lados.
 * - Si NsdManager no existe en el dispositivo o discoverServices
 *   falla al arrancar, la lista sale vacía y lastError lo dice
 *   (el broadcast sigue siendo el respaldo).
 */
object NsdDiscovery {
    /** Tipo de servicio DNS-SD que anuncia la TV (NsdAdvertiser). */
    const val SERVICE_TYPE: String = "_ultimate-gamepad._tcp."

    /** Duración por defecto de la búsqueda NSD. */
    const val DEFAULT_TIMEOUT_MS: Long = 4000L

    /** Último error al arrancar la búsqueda, para diagnóstico. null si todo bien. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Busca TVs por NSD durante [timeoutMs] y devuelve las
     * resueltas de verdad (nombre + IP + puerto). Nunca null,
     * nunca una entrada sin resolver. El PIN viene vacío (ver
     * cabecera): el cableado debe fusionar con el broadcast o
     * pedir el PIN al usuario.
     */
    suspend fun discover(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<DiscoveredTv> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                lastError = "NSD: no disponible en este teléfono."
                return@withContext emptyList()
            }
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("ultimate-gamepad-nsd")?.apply {
                setReferenceCounted(false)
            }
            val found = LinkedHashMap<String, DiscoveredTv>()

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    // Sin host/puerto resueltos: no se añade nada.
                    // Un servicio anunciado pero no resoluble no es
                    // una TV encontrada.
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    if (host.contains(":")) return // Solo IPv4 (ver cabecera).
                    if (serviceInfo.port <= 0) return
                    synchronized(found) {
                        found[host] = DiscoveredTv(
                            name = serviceInfo.serviceName,
                            ip = host,
                            port = serviceInfo.port,
                            pin = "", // PIN pendiente: nunca viaja por NSD.
                        )
                    }
                }
            }

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    lastError = null
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    // Solo se anuncia el hallazgo: la entrada real
                    // llega en onServiceResolved, nunca aquí.
                    runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit // Ver cabecera.

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    lastError = "NSD: no se pudo iniciar la búsqueda (código $errorCode)."
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }

            try {
                runCatching { lock?.acquire() }
                try {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (e: Exception) {
                    lastError = "NSD: no se pudo iniciar la búsqueda (${e.message ?: e.javaClass.simpleName})."
                    return@withContext emptyList()
                }
                // Ventana de búsqueda: los resultados llegan por
                // callbacks mientras se espera. El finally de abajo
                // para la búsqueda siempre, también si cancelan.
                delay(timeoutMs)
            } finally {
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                runCatching { if (lock?.isHeld == true) lock.release() }
            }
            synchronized(found) { found.values.toList() }
        }
}

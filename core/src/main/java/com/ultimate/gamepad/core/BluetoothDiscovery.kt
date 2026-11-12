package com.ultimate.gamepad.core

/**
 * Descubrimiento Bluetooth desde el teléfono (ronda Bluetooth dos
 * vías, 2026-11-11) — lógica pura en :core, sin Android: fusiona
 * emparejados + encontrados, sin duplicados (por MAC), y ordena la
 * lista para la UI. El escaneo real (startDiscovery +
 * BroadcastReceiver ACTION_FOUND) vive en app-phone
 * (BluetoothScanner); aquí solo va la parte que se puede probar
 * sin Android.
 *
 * Límite honesto: el teléfono puede descubrir y emparejar la TV,
 * pero en el perfil HID es el HOST (la TV) quien inicia la
 * conexión HID. Por eso el flujo desde el teléfono es: descubrir
 * → emparejar (createBond) → conectar por SPP si aplica → dejar
 * el HID registrado + el teléfono visible para que la TV complete
 * el emparejado HID de vuelta.
 */
data class DiscoveredDevice(
    val name: String?,
    /** MAC en mayúsculas; es la identidad única del dispositivo. */
    val address: String,
    val bonded: Boolean,
    /** dBm, solo en dispositivos encontrados (null en emparejados). */
    val rssi: Int? = null,
)

object BluetoothDiscovery {
    /**
     * Fusiona emparejados + encontrados sin duplicar por MAC. Si
     * un dispositivo aparece en ambas listas, manda el emparejado
     * (su nombre es el guardado) y se conserva el RSSI del
     * encontrado, si lo hay.
     */
    fun merge(bonded: List<DiscoveredDevice>, found: List<DiscoveredDevice>): List<DiscoveredDevice> {
        val byAddress = LinkedHashMap<String, DiscoveredDevice>()
        for (d in bonded) byAddress[d.address.uppercase()] = d.copy(bonded = true)
        for (d in found) {
            val key = d.address.uppercase()
            val existing = byAddress[key]
            if (existing == null) {
                byAddress[key] = d.copy(bonded = false)
            } else if (existing.rssi == null && d.rssi != null) {
                byAddress[key] = existing.copy(rssi = d.rssi)
            }
        }
        return byAddress.values.toList()
    }

    /**
     * Orden para la lista de la UI: emparejados primero, luego por
     * señal (más fuerte primero); los sin nombre, al final de su
     * grupo, ordenados por MAC.
     */
    fun sortForDisplay(devices: List<DiscoveredDevice>): List<DiscoveredDevice> =
        devices.sortedWith(
            compareByDescending<DiscoveredDevice> { it.bonded }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.name == null }
                .thenBy { it.name.orEmpty() }
                .thenBy { it.address },
        )

    /**
     * ¿Parece una TV? Solo para RESALTARLA en la lista, no es un
     * filtro: cualquier dispositivo se puede elegir. Se busca la
     * palabra "tv" (o marca/modelo de TV), no la subcadena: "Active"
     * o "Motv" no son TVs (ronda 2 P2-15).
     */
    fun looksLikeTv(device: DiscoveredDevice): Boolean {
        val name = device.name.orEmpty().lowercase()
        val words = name.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if ("tv" in words || "television" in words || "televisor" in words) return true
        return "bravia" in name || "webos" in name || "tizen" in name ||
            "google tv" in name || "android tv" in name
    }
}

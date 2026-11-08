package com.ultimate.gamepad.core

/**
 * Transportes Bluetooth — Etapa 1: estructura e interfaces, sin
 * fingir que funcionan antes de probarlos en el teléfono (Helio G99).
 *
 * Hay DOS cosas distintas que no hay que confundir:
 *
 * 1. Bluetooth HID (teléfono = mando real):
 *    El teléfono se registra como dispositivo HID ante la TV con
 *    BluetoothHidDevice (API 28+). La TV lo ve como un mando
 *    Bluetooth más, sin app receptora y sin Shizuku. La inyección
 *    es directa: no pasa por OpenNOW ni por /dev/uinput.
 *    Requisito: que el firmware del teléfono (Helio G99) exponga el perfil
 *    HID_DEVICE. Eso se comprueba en tiempo de ejecución
 *    (ver app-phone BluetoothHidService) y la pantalla dice
 *    "Disponible" o "No disponible en este teléfono". Si no está
 *    expuesto, el método queda en FAILED, no en "Conectado".
 *
 * 2. Bluetooth datos a la app receptora (BLE / SPP):
 *    El teléfono envía el mismo paquete de 32 bytes por BLE o por
 *    RFCOMM/SPP a la app de la TV, que lo recibe y lo inyecta como
 *    en Wi-Fi. Es el respaldo si falla TODO el Wi-Fi.
 *    IMPLEMENTADO en la Fase 5 (SPP/RFCOMM real, 2026-11-07):
 *    BluetoothDataTransport (teléfono, cliente) y
 *    BluetoothDataReceiverImpl (TV, servidor) con el mismo paquete
 *    de 32 bytes. BLE/GATT queda como plan B si la pila BT de la
 *    TV rechaza el listen() o el connect() da "Service discovery
 *    failed" estando emparejados.
 */
enum class HidAvailability {
    UNKNOWN,        // Aún no comprobado.
    AVAILABLE,      // El perfil HID_DEVICE está expuesto.
    UNAVAILABLE,    // El firmware no expone HID_DEVICE en este teléfono.
    PERMISSION_DENIED, // Falta BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE.
}

/** Descriptor HID de gamepad estándar (Xbox 360 genérico). Ver BluetoothHidService en :app-phone. */
object HidDescriptor {
    /**
     * NOMBRE EXPUESTO — criterio de éxito de la Etapa 0:
     * "Xbox 360 Controller". NUNCA "Virtual", NUNCA "Ultimate
     * Gamepad" como nombre de dispositivo. El nombre de la app
     * (Ultimate Gamepad) es otra cosa y no se expone por Bluetooth
     * ni por uinput. Si Android/OpenNOW ve otro nombre, es FALLO
     * aunque lleguen paquetes por Wi-Fi.
     */
    const val VENDOR_ID: Int = 0x045E
    const val PRODUCT_ID: Int = 0x028E
    const val DEVICE_NAME: String = "Xbox 360 Controller"
    const val DEVICE_DESCRIPTION: String = "Xbox 360 Controller"
}

/** Receptor Bluetooth-datos en la TV: misma entrada, distinto canal. */
interface BluetoothDataReceiver {
    val method: TransportMethod get() = TransportMethod.BLUETOOTH_DATA
    /** Estado real: en Etapa 1 siempre PENDING_STAGE2 hasta que se implemente. */
    val status: TransportStatus
    fun start(onPacket: (GamepadState) -> Unit)
    fun stop()
}

package com.ultimate.gamepad.core

/**
 * Reportes PID de force feedback (USB Device Class Definition for
 * Physical Interface Devices 1.0, §5.1/§6/§9) — parser puro, sin
 * Android, para que :core pueda testearlo.
 *
 * El descriptor HID (HidGamepadDescriptor.BYTES) añade a la
 * colección Game Pad una segunda colección Application (Usage Page
 * 0x0F, Physical Interface Device) con SOLO fuerza constante: el
 * teléfono tiene UN motor, así que periódico, rampa, resorte,
 * amortiguador, etc. no se declaran y el host no los puede pedir.
 *
 * Reportes (el byte del Report ID NUNCA va en `data`: la API de
 * BluetoothHidDevice lo entrega aparte en onSetReport /
 * onInterruptData / onGetReport, y sendReport / replyReport lo
 * reciben aparte — "Report data, not including Report Id"):
 *   ID 1: entrada del mando (9 bytes de datos, sin el ID).
 *   ID 2: Set Effect Report (Output, 17 bytes): índice del
 *     efecto (7 bits) + ROM Flag, tipo (1 = fuerza constante),
 *     duración ms (u16 LE, 0xFFFF = infinita), intervalo de
 *     repetición, periodo de muestreo, ganancia, botón de
 *     disparo, ejes, dirección, offsets de bloque.
 *   ID 3: Set Constant Force Report (Output, 3 bytes): offset
 *     de bloque (15 bits) + ROM Flag, magnitud 0..255.
 *   ID 4: Effect Operation Report (Output, 3 bytes): índice
 *     (7 bits) + ROM Flag, operación (1 = Start, 2 = Start Solo,
 *     3 = Stop), repeticiones (Loop Count).
 *   ID 5: Create New Effect Report (Feature, 2 bytes): tipo +
 *     Byte Count. El host lo manda con SET_REPORT (Feature) y
 *     luego lee el Block Load con GET_REPORT (Feature): así el
 *     dispositivo gestiona los índices (§9).
 *   ID 6: PID Block Load Report (Feature, 4 bytes): índice,
 *     estado (1 = éxito, 2 = lleno, 3 = error), RAM disponible
 *     (u16 LE). Solo se RESPONDE, nunca se parsea.
 *   ID 7: Device Gain Report (Feature, 1 byte): ganancia 0..255.
 *
 * Un solo motor: fuerte y débil colapsan al mismo valor (el
 * máximo de los dos). Los efectos periódicos / de rampa / de
 * condición NO se soportan: el host no puede crearlos porque no
 * están en el descriptor.
 */
object RumbleReport {
    const val REPORT_ID_INPUT: Int = 1
    const val REPORT_ID_SET_EFFECT: Int = 2
    const val REPORT_ID_SET_CONSTANT_FORCE: Int = 3
    const val REPORT_ID_EFFECT_OPERATION: Int = 4
    const val REPORT_ID_CREATE_NEW_EFFECT: Int = 5
    const val REPORT_ID_BLOCK_LOAD: Int = 6
    const val REPORT_ID_DEVICE_GAIN: Int = 7

    const val SET_EFFECT_SIZE: Int = 17
    const val SET_CONSTANT_FORCE_SIZE: Int = 3
    const val EFFECT_OPERATION_SIZE: Int = 3
    const val CREATE_NEW_EFFECT_SIZE: Int = 2
    const val BLOCK_LOAD_SIZE: Int = 4
    const val DEVICE_GAIN_SIZE: Int = 1

    /** Único tipo de efecto declarado: fuerza constante (§5, 0x26). */
    const val EFFECT_TYPE_CONSTANT_FORCE: Int = 1
    /** Operaciones del Effect Operation Report (§5.9, 0x79/0x7A/0x7B). */
    const val OP_START: Int = 1
    const val OP_START_SOLO: Int = 2
    const val OP_STOP: Int = 3
    /** Estados del Block Load Report (§9). */
    const val BLOCK_LOAD_SUCCESS: Int = 1
    const val BLOCK_LOAD_FULL: Int = 2
    const val BLOCK_LOAD_ERROR: Int = 3
    /** Duración infinita en el Set Effect Report. */
    const val DURATION_INFINITE: Int = 0xFFFF

    sealed interface Event {
        /** Parámetros de un efecto (solo se usa la fuerza constante). */
        data class EffectSet(val blockIndex: Int, val effectType: Int, val durationMs: Int, val gain: Int) : Event
        /** Magnitud 0..255 del efecto de fuerza constante. */
        data class ConstantForceSet(val magnitude: Int) : Event
        /** Arrancar (1/2) o parar (3) un efecto. */
        data class Operation(val blockIndex: Int, val operation: Int, val loopCount: Int) : Event
        /** El host pide crear un efecto nuevo (Feature). */
        data class CreateNewEffect(val effectType: Int) : Event
        /** Ganancia global del dispositivo (Feature). */
        data class DeviceGainSet(val gain: Int) : Event
    }

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    /**
     * Parsea un reporte de SALIDA o FEATURE recibido del host.
     * `data` son los bytes SIN el Report ID (así los entrega
     * Android). Devuelve null si el id no es de rumble o el
     * tamaño no cuadra con el descriptor: un reporte mal formado
     * se ignora, nunca se inventa una vibración.
     */
    fun parseOutput(reportId: Int, data: ByteArray): Event? = when (reportId) {
        REPORT_ID_SET_EFFECT -> {
            if (data.size != SET_EFFECT_SIZE) null else Event.EffectSet(
                blockIndex = data[0].toInt() and 0x7F,
                effectType = data[1].toInt() and 0xFF,
                durationMs = u16le(data, 2),
                gain = data[8].toInt() and 0xFF,
            )
        }
        REPORT_ID_SET_CONSTANT_FORCE -> {
            if (data.size != SET_CONSTANT_FORCE_SIZE) null else Event.ConstantForceSet(
                magnitude = data[2].toInt() and 0xFF,
            )
        }
        REPORT_ID_EFFECT_OPERATION -> {
            if (data.size != EFFECT_OPERATION_SIZE) null else Event.Operation(
                blockIndex = data[0].toInt() and 0x7F,
                operation = data[1].toInt() and 0xFF,
                loopCount = data[2].toInt() and 0xFF,
            )
        }
        REPORT_ID_CREATE_NEW_EFFECT -> {
            if (data.size != CREATE_NEW_EFFECT_SIZE) null else Event.CreateNewEffect(
                effectType = data[0].toInt() and 0xFF,
            )
        }
        REPORT_ID_DEVICE_GAIN -> {
            if (data.size != DEVICE_GAIN_SIZE) null else Event.DeviceGainSet(
                gain = data[0].toInt() and 0xFF,
            )
        }
        else -> null
    }

    /**
     * Respuesta al GET_REPORT (Feature) del PID Block Load Report:
     * índice asignado + éxito + RAM disponible (256, u16 LE).
     * El teléfono tiene un solo motor: todos los efectos colapsan
     * al bloque 1; nunca se responde "lleno" ni "error".
     */
    fun blockLoadReply(blockIndex: Int): ByteArray = byteArrayOf(
        blockIndex.coerceIn(1, 127).toByte(),
        BLOCK_LOAD_SUCCESS.toByte(),
        0x00.toByte(), 0x01.toByte(), // RAM Pool Available = 256
    )

    /** Respuesta al GET_REPORT (Feature) del Device Gain Report. */
    fun deviceGainReply(gain: Int): ByteArray =
        byteArrayOf(gain.coerceIn(0, 255).toByte())
}

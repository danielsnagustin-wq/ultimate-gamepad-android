package com.ultimate.gamepad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser de reportes PID de rumble (USB PID 1.0 §5.1/§6/§9).
 * Los datos de entrada son SIN el byte del Report ID, como los
 * entrega Android en onSetReport / onInterruptData.
 */
class RumbleReportTest {

    @Test fun `Set Effect de 17 bytes se parsea (indice, tipo, duracion, ganancia)`() {
        val data = ByteArray(RumbleReport.SET_EFFECT_SIZE)
        data[0] = 0x01 // índice 1, ROM Flag 0
        data[1] = 0x01 // fuerza constante
        data[2] = 0x2C; data[3] = 0x01 // duración 300 ms (LE)
        data[8] = 0x7F // ganancia 127
        val ev = RumbleReport.parseOutput(RumbleReport.REPORT_ID_SET_EFFECT, data)
        assertTrue(ev is RumbleReport.Event.EffectSet)
        val set = ev as RumbleReport.Event.EffectSet
        assertEquals(1, set.blockIndex)
        assertEquals(1, set.effectType)
        assertEquals(300, set.durationMs)
        assertEquals(127, set.gain)
    }

    @Test fun `Set Effect con ROM Flag enmascara el indice a 7 bits`() {
        val data = ByteArray(RumbleReport.SET_EFFECT_SIZE)
        data[0] = 0x85.toByte() // índice 5 + ROM Flag
        data[1] = 0x01
        val ev = RumbleReport.parseOutput(RumbleReport.REPORT_ID_SET_EFFECT, data)
        assertEquals(5, (ev as RumbleReport.Event.EffectSet).blockIndex)
    }

    @Test fun `Set Constant Force de 3 bytes da la magnitud`() {
        val data = byteArrayOf(0x00, 0x00, 0xC8.toByte()) // offset 0, magnitud 200
        val ev = RumbleReport.parseOutput(RumbleReport.REPORT_ID_SET_CONSTANT_FORCE, data)
        assertEquals(200, (ev as RumbleReport.Event.ConstantForceSet).magnitude)
    }

    @Test fun `Effect Operation Start y Stop se distinguen`() {
        val start = RumbleReport.parseOutput(
            RumbleReport.REPORT_ID_EFFECT_OPERATION,
            byteArrayOf(0x01, 0x01, 0x00),
        ) as RumbleReport.Event.Operation
        assertEquals(1, start.blockIndex)
        assertEquals(RumbleReport.OP_START, start.operation)
        val stop = RumbleReport.parseOutput(
            RumbleReport.REPORT_ID_EFFECT_OPERATION,
            byteArrayOf(0x01, 0x03, 0x00),
        ) as RumbleReport.Event.Operation
        assertEquals(RumbleReport.OP_STOP, stop.operation)
    }

    @Test fun `Create New Effect y Device Gain (Feature) se parsean`() {
        val create = RumbleReport.parseOutput(
            RumbleReport.REPORT_ID_CREATE_NEW_EFFECT,
            byteArrayOf(0x01, 0x00),
        ) as RumbleReport.Event.CreateNewEffect
        assertEquals(1, create.effectType)
        val gain = RumbleReport.parseOutput(
            RumbleReport.REPORT_ID_DEVICE_GAIN,
            byteArrayOf(0x80.toByte()),
        ) as RumbleReport.Event.DeviceGainSet
        assertEquals(128, gain.gain)
    }

    @Test fun `tamano equivocado o id desconocido devuelven null (nunca se inventa vibracion)`() {
        assertNull(RumbleReport.parseOutput(RumbleReport.REPORT_ID_SET_EFFECT, ByteArray(16)))
        assertNull(RumbleReport.parseOutput(RumbleReport.REPORT_ID_SET_EFFECT, ByteArray(18)))
        assertNull(RumbleReport.parseOutput(RumbleReport.REPORT_ID_SET_CONSTANT_FORCE, ByteArray(2)))
        assertNull(RumbleReport.parseOutput(RumbleReport.REPORT_ID_EFFECT_OPERATION, ByteArray(4)))
        assertNull(RumbleReport.parseOutput(RumbleReport.REPORT_ID_BLOCK_LOAD, ByteArray(4)))
        assertNull(RumbleReport.parseOutput(RumbleReport.REPORT_ID_INPUT, ByteArray(7)))
        assertNull(RumbleReport.parseOutput(9, ByteArray(3)))
        assertNull(RumbleReport.parseOutput(0, ByteArray(0)))
    }

    @Test fun `Block Load responde indice + exito + RAM 256`() {
        val reply = RumbleReport.blockLoadReply(1)
        assertEquals(RumbleReport.BLOCK_LOAD_SIZE, reply.size)
        assertEquals(1.toByte(), reply[0])
        assertEquals(RumbleReport.BLOCK_LOAD_SUCCESS.toByte(), reply[1])
        assertEquals(0x00.toByte(), reply[2])
        assertEquals(0x01.toByte(), reply[3]) // 256 en LE
    }

    @Test fun `Device Gain responde un byte con la ganancia`() {
        val reply = RumbleReport.deviceGainReply(200)
        assertEquals(1, reply.size)
        assertEquals(0xC8.toByte(), reply[0])
    }

    @Test fun `Block Load y Device Gain fijan valores fuera de rango`() {
        // blockLoadReply y deviceGainReply recortan (coerceIn), no
        // rechazan: fuera de rango se fija al límite, nunca se
        // inventa un índice o una ganancia (ronda 2, hueco de tests).
        assertEquals(1.toByte(), RumbleReport.blockLoadReply(0)[0])
        assertEquals(127.toByte(), RumbleReport.blockLoadReply(200)[0])
        assertEquals(0.toByte(), RumbleReport.deviceGainReply(-5)[0])
        assertEquals(0xFF.toByte(), RumbleReport.deviceGainReply(300)[0])
    }
}

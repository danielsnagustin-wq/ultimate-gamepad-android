package com.ultimate.gamepad.phone.network

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Escáner Bluetooth clásico desde el teléfono (ronda Bluetooth dos
 * vías, 2026-11-11): el teléfono BUSCA dispositivos cercanos.
 * Antes solo se hacía visible y esperaba a que la TV buscara
 * ("El Bluetooth ni busca nada"): ahora la app escanea, lista la
 * TV y permite emparejar desde el teléfono.
 *
 * Flujo real (sin simular nada): startDiscovery (~12 s) +
 * BroadcastReceiver de ACTION_FOUND; emparejados con
 * adapter.bondedDevices; emparejar con device.createBond().
 * El emparejado se completa en segundo plano; el estado llega por
 * ACTION_BOND_STATE_CHANGED (lo escucha el ViewModel).
 *
 * Límite honesto: en el perfil HID es el HOST (la TV) quien inicia
 * la conexión HID; el teléfono no puede forzar a la TV a
 * conectarse como HID. El flujo desde el teléfono es: descubrir →
 * emparejar → conectar por SPP (BluetoothDataTransport) si aplica
 * → dejar el HID registrado + el teléfono visible para que la TV
 * complete el emparejado HID de vuelta. Para que la TV aparezca
 * en el escaneo, tiene que tener su pantalla de ajustes Bluetooth
 * abierta (visible) o estar ya emparejada.
 */
class BluetoothScanner(private val context: Context) {

    /** Permiso que falta para escanear, o null si ya se puede. */
    fun missingScanPermission(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) null
            else Manifest.permission.BLUETOOTH_SCAN
        } else {
            // En Android 11 y anteriores el escaneo clásico exige ubicación.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) null
            else Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    fun isBluetoothOn(): Boolean {
        val adapter = adapter() ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    /** Dispositivos ya emparejados, ordenados por nombre. */
    fun bondedDevices(): List<BluetoothDevice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val adapter = adapter() ?: return emptyList()
        return runCatching { adapter.bondedDevices.sortedBy { it.name ?: it.address } }.getOrDefault(emptyList())
    }

    @Volatile var isScanning: Boolean = false
        private set

    private var receiver: BroadcastReceiver? = null

    /**
     * Empieza el escaneo clásico (~12 s, lo que dura un
     * startDiscovery). `onDevice` por cada dispositivo encontrado
     * (puede repetirse: el llamador fusiona con
     * BluetoothDiscovery.merge); `onFinished` cuando el sistema
     * termina el escaneo o se para a mano. Devuelve false si no se
     * pudo empezar (Bluetooth apagado, sin permiso, o ya
     * escaneando).
     */
    fun startScan(onDevice: (BluetoothDevice, Int?) -> Unit, onFinished: () -> Unit): Boolean {
        if (isScanning) return false
        if (missingScanPermission() != null) return false
        val adapter = adapter() ?: return false
        if (!isBluetoothOn()) return false
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        val recv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null) {
                            val rssi = if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) {
                                intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                            } else {
                                null
                            }
                            onDevice(device, rssi)
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        stopScan()
                        onFinished()
                    }
                }
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(context, recv, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            true
        }.getOrDefault(false)
        if (!registered) return false
        receiver = recv
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (!started) {
            stopScan()
            return false
        }
        isScanning = true
        return true
    }

    /** Para el escaneo (a mano o al terminar). Idempotente. */
    fun stopScan() {
        if (!isScanning && receiver == null) return
        isScanning = false
        val recv = receiver
        receiver = null
        runCatching { adapter()?.cancelDiscovery() }
        if (recv != null) runCatching { context.unregisterReceiver(recv) }
    }

    /**
     * Empareja con un dispositivo encontrado (createBond). El
     * emparejado se completa en segundo plano; el estado llega por
     * ACTION_BOND_STATE_CHANGED. Devuelve false si no se pudo ni
     * lanzar (sin permiso o error del sistema).
     */
    fun createBond(device: BluetoothDevice): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { device.createBond() }.getOrDefault(false)
    }

    /** Dispositivo remoto por MAC (para emparejar uno encontrado en el escaneo). */
    fun remoteDevice(address: String): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return null
        val adapter = adapter() ?: return null
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

package org.staacks.alpharemote.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives one-byte shutter commands from a paired HC-05 over Bluetooth Classic SPP.
 *
 * Protocol:
 *   'S' = trigger one shutter action
 *
 * Other bytes (including CR/LF) are ignored.
 */
class BluetoothSppManager(
    context: Context,
    private val onConnected: (String) -> Unit,
    private val onShutterCommand: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null

    companion object {
        private const val TAG = "AlphaRemote-HC05"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    @SuppressLint("MissingPermission")
    fun connectToPairedHC05() {
        if (!running.compareAndSet(false, true)) return

        executor.execute {
            try {
                val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter: BluetoothAdapter = manager.adapter
                    ?: throw IOException("Bluetooth tidak tersedia")

                if (!adapter.isEnabled) {
                    throw IOException("Bluetooth Android belum aktif")
                }

                val device: BluetoothDevice = adapter.bondedDevices.firstOrNull { bonded ->
                    val name = bonded.name.orEmpty()
                    name.contains("HC-05", ignoreCase = true) ||
                        name.contains("HC05", ignoreCase = true)
                } ?: throw IOException("HC-05 belum dipasangkan. Pair HC-05 dari pengaturan Bluetooth Android terlebih dahulu.")

                adapter.cancelDiscovery()
                val connectedSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = connectedSocket
                connectedSocket.connect()

                if (!running.get()) {
                    connectedSocket.close()
                    return@execute
                }

                Log.i(TAG, "SPP connected: ${device.name} (${device.address})")
                onConnected("${device.name} (${device.address})")

                connectedSocket.inputStream.use { input ->
                    while (running.get()) {
                        val value = input.read()
                        if (value == -1) break
                        if (value.toChar() == 'S') {
                            Log.d(TAG, "Received shutter command")
                            onShutterCommand()
                        }
                    }
                }
                if (running.get()) onError("Koneksi HC-05 terputus")
            } catch (error: SecurityException) {
                if (running.get()) onError("Izin Bluetooth belum diberikan")
                Log.e(TAG, "Bluetooth permission error", error)
            } catch (error: IOException) {
                if (running.get()) onError(error.message ?: "Gagal menghubungkan HC-05")
                Log.w(TAG, "SPP connection ended", error)
            } catch (error: Exception) {
                if (running.get()) onError(error.message ?: "Kesalahan koneksi HC-05")
                Log.e(TAG, "Unexpected SPP error", error)
            } finally {
                try { socket?.close() } catch (_: IOException) {}
                socket = null
                running.set(false)
            }
        }
    }

    fun close() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        executor.shutdownNow()
    }
}

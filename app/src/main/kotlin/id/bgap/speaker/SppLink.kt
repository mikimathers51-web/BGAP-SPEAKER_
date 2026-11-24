package id.bgap.speaker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Satu sesi koneksi SPP ke firmware. Baca baris teks (\n atau \r\n), tulis perintah satu per satu
 * dengan jeda kecil agar buffer baris firmware (200 byte) tidak kewalahan.
 */
class SppLink(
    private val scope: CoroutineScope,
    private val onLine: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private val queue = Channel<String>(Channel.UNLIMITED)
    @Volatile private var closing = false

    val isOpen: Boolean get() = socket?.isConnected == true && !closing

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closing = false
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            val input = s.inputStream
            val output = s.outputStream
            readJob = scope.launch(Dispatchers.IO) { readLoop(input) }
            writeJob = scope.launch(Dispatchers.IO) { writeLoop(output) }
        }.onFailure { runCatching { socket?.close() }; socket = null }
    }

    fun send(cmd: String): Boolean {
        val c = cmd.replace(Regex("[\\r\\n]+"), " ").trim().take(120)
        if (c.isEmpty() || !isOpen) return false
        return queue.trySend(c).isSuccess
    }

    /** Buang perintah antrean yang belum terkirim (mis. saat pindah mode). */
    fun clearQueue() {
        while (queue.tryReceive().isSuccess) { /* kosongkan */ }
    }

    fun close() {
        closing = true
        readJob?.cancel()
        writeJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun readLoop(input: InputStream) {
        val buf = ByteArray(512)
        val line = StringBuilder()
        var overflow = false
        try {
            while (true) {
                val n = withContext(Dispatchers.IO) { input.read(buf) }
                if (n < 0) break
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c == '\n' || c == '\r') {
                        if (!overflow && line.isNotEmpty()) onLine(line.toString().trim())
                        line.setLength(0)
                        overflow = false
                    } else if (line.length < 600) {
                        line.append(c)
                    } else {
                        overflow = true
                    }
                }
            }
        } catch (e: Exception) {
            // koneksi putus / socket ditutup
        }
        if (!closing) {
            closing = true
            runCatching { socket?.close() }
            onClosed("Koneksi terputus")
        }
    }

    private suspend fun writeLoop(output: OutputStream) {
        try {
            for (cmd in queue) {
                withContext(Dispatchers.IO) {
                    output.write((cmd + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                delay(25)
            }
        } catch (e: Exception) {
            if (!closing) {
                closing = true
                runCatching { socket?.close() }
                onClosed("Gagal menulis ke speaker")
            }
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

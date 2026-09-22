package app.systemresponse

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
class BleClient(private val context: Context, private val secret: ByteArray,
    private val status: (String, Boolean) -> Unit, private val received: (Packet) -> Unit,
    private val lost: () -> Unit) {
    companion object {
        val SERVICE: UUID = UUID.fromString("845E1000-7F5A-4CB5-9AE8-2DC18A64BB01")
        val WRITE: UUID = UUID.fromString("845E1001-7F5A-4CB5-9AE8-2DC18A64BB01")
        val NOTIFY: UUID = UUID.fromString("845E1002-7F5A-4CB5-9AE8-2DC18A64BB01")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private var buffer = FrameBuffer()
    private var wire: SecureWire? = null
    private val writes = ArrayDeque<ByteArray>()
    private var writing = false
    private var scanning = false
    private var lastSeen = 0L
    private var generation = 0
    var trusted = false; private set
    private val scanner = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post {
            if (!scanning || gatt != null) return@post
            stopScan(); status("Mac найден. Проверяем доверие…", false)
            gatt = result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } }
        override fun onScanFailed(errorCode: Int) { handler.post { fail("Поиск BLE не запустился: $errorCode") } }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) { handler.post {
            if (g !== gatt) return@post
            if (code != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) { fail("BLE-связь с Mac прервана"); return@post }
            if (state == BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) fail("Не удалось прочитать BLE-сервис")
        } }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) { handler.post {
            if (g !== gatt) return@post
            val service = g.getService(SERVICE)
            write = service?.getCharacteristic(WRITE)
            val notify = service?.getCharacteristic(NOTIFY)
            val descriptor = notify?.getDescriptor(CCCD)
            if (code != BluetoothGatt.GATT_SUCCESS || write == null || notify == null || descriptor == null) { fail("На Mac другой BLE-сервис"); return@post }
            if (!g.setCharacteristicNotification(notify, true)) { fail("Не удалось включить ответы Mac"); return@post }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!g.writeDescriptor(descriptor)) fail("Подписка BLE отклонена")
        } }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) { handler.post {
            if (g === gatt && code != BluetoothGatt.GATT_SUCCESS) fail("Mac не разрешил подписку")
        } }
        @Deprecated("Compatibility callback for API 31–32")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.clone() ?: return
            handler.post { if (g === gatt && characteristic.uuid == NOTIFY) receiveBytes(value) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { if (g === gatt && characteristic.uuid == NOTIFY) receiveBytes(value) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, code: Int) { handler.post {
            if (g !== gatt) return@post
            if (code != BluetoothGatt.GATT_SUCCESS) { fail("Mac отклонил BLE-пакет"); return@post }
            if (writes.isNotEmpty()) writes.removeFirst()
            writing = false; flush()
        } }
    }
    fun start() {
        stop()
        if (adapter == null || !adapter.isEnabled) { fail("Включи Bluetooth на телефоне"); return }
        status("Ищем Mac рядом…", false); scanning = true
        try { adapter.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanner) }
        catch (e: Exception) { fail("Нет доступа к Bluetooth: ${e.javaClass.simpleName}"); return }
        val token = generation
        handler.postDelayed({ if (token == generation && !trusted) fail("Mac не ответил за 25 секунд. Проверь ключ и включи связь на Mac") }, 25_000)
    }
    private fun stopScan() { if (scanning) { runCatching { adapter?.bluetoothLeScanner?.stopScan(scanner) }; scanning = false } }
    fun stop() {
        generation++; stopScan(); trusted = false; wire = null; buffer = FrameBuffer(); writes.clear(); writing = false; write = null
        val old = gatt; gatt = null; runCatching { old?.disconnect(); old?.close() }
    }
    private fun fail(reason: String) { stop(); status(reason, false); lost() }
    private fun receiveBytes(value: ByteArray) {
        try {
            for (frame in buffer.append(value)) {
                if (frame.startsWith("HELLO|")) {
                    require(wire == null && !trusted)
                    val session = frame.substringAfter('|'); require(UUID.fromString(session).toString().equals(session, true))
                    wire = SecureWire(secret, session, "android"); enqueue(wire!!.encode(Packet("hello")))
                } else {
                    val packet = wire?.decode(frame) ?: error("No session")
                    if (!trusted) {
                        require(packet.type == "hello"); trusted = true; status("Mac • доверенная связь", true); lastSeen = System.currentTimeMillis(); heartbeat(generation)
                    } else {
                        lastSeen = System.currentTimeMillis()
                        when (packet.type) { "ping" -> send(Packet("pong")); "pong" -> Unit; else -> received(packet) }
                    }
                }
            }
        } catch (_: Exception) { fail("Проверка доверия не пройдена. Проверь ключ с Mac") }
    }
    fun send(packet: Packet) { if (trusted) { runCatching { enqueue(wire!!.encode(packet)) }.onFailure { fail("Ошибка BLE-протокола") } } }
    private fun enqueue(data: ByteArray) {
        // Minimum ATT MTU 23 guarantees 20 payload bytes; no MTU race during subscription.
        data.asList().chunked(20).forEach { writes.add(it.toByteArray()) }
        if (writes.size > 512) { fail("Очередь BLE переполнена"); return }
        flush()
    }
    private fun flush() {
        if (writing || writes.isEmpty()) return
        val characteristic = write ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = writes.first
        writing = true
        if (gatt?.writeCharacteristic(characteristic) != true) fail("Не удалось отправить BLE-команду")
    }
    private fun heartbeat(token: Int) {
        handler.postDelayed({
            if (generation != token || !trusted) return@postDelayed
            if (System.currentTimeMillis() - lastSeen > 14_000) fail("Mac перестал отвечать. Текущий звук не меняем")
            else { send(Packet("ping")); heartbeat(token) }
        }, 4_000)
    }
}

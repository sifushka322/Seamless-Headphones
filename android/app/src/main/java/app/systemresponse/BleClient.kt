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
    private val lost: () -> Unit, private val trace: (String) -> Unit = {}) {
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
    private val packets = PacketQueue()
    private var payloadSize = 20
    private var discovering = false
    private var writeStarted = 0L
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
            trace("GATT state=$state status=$code")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(185)) discover(g)
                else handler.postDelayed({ if (g === gatt) discover(g) }, 2000)
            }
        } }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, code: Int) { handler.post {
            if (g !== gatt) return@post
            if (code == BluetoothGatt.GATT_SUCCESS) payloadSize = (mtu - 3).coerceIn(20, 180)
            trace("GATT MTU=$mtu status=$code payload=$payloadSize")
            discover(g)
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
            if (writes.isEmpty()) trace("TX frame complete elapsedMs=${android.os.SystemClock.elapsedRealtime() - writeStarted} queued=${packets.size}")
            writing = false; flush()
        } }
    }
    private fun discover(g: BluetoothGatt) {
        if (discovering) return
        discovering = true
        if (!g.discoverServices()) fail("Не удалось прочитать BLE-сервис")
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
        generation++; stopScan(); trusted = false; wire = null; buffer = FrameBuffer(); writes.clear(); packets.clear(); payloadSize = 20; discovering = false; writing = false; write = null
        val old = gatt; gatt = null; runCatching { old?.disconnect(); old?.close() }
    }
    private fun fail(reason: String) { trace("BLE failure: $reason"); stop(); status(reason, false); lost() }
    private fun receiveBytes(value: ByteArray) {
        try {
            for (frame in buffer.append(value)) {
                if (frame.startsWith("HELLO|")) {
                    require(wire == null && !trusted)
                    val session = frame.substringAfter('|'); require(UUID.fromString(session).toString().equals(session, true))
                    wire = SecureWire(secret, session, "android"); packets.add(Packet("hello")); flush()
                } else {
                    val packet = wire?.decode(frame) ?: error("No session")
                    trace("RX type=${packet.type} tx=${packet.id} bytes=${frame.length}")
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
    fun send(packet: Packet) {
        if (!trusted) return
        if (!packets.add(packet)) { fail("Очередь BLE-команд переполнена"); return }
        flush()
    }
    private fun flush() {
        if (writing) return
        val characteristic = write ?: return
        if (writes.isEmpty()) {
            val packet = packets.next() ?: return
            val data = runCatching { wire!!.encode(packet) }.getOrElse { fail("Ошибка BLE-протокола"); return }
            data.asList().chunked(payloadSize).forEach { writes.add(it.toByteArray()) }
            writeStarted = android.os.SystemClock.elapsedRealtime()
            trace("TX type=${packet.type} tx=${packet.id} bytes=${data.size} chunks=${writes.size} queued=${packets.size}")
        }
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

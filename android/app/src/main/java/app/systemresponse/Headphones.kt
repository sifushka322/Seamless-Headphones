package app.systemresponse

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class Headphones(private val context: Context, private val trace: (String) -> Unit = {}) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var profile: BluetoothA2dp? = null
    private var operation = 0
    private var track: AudioTrack? = null
    private var closed = false
    init { adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(id: Int, proxy: BluetoothProfile) { main.post {
            if (closed) adapter.closeProfileProxy(id, proxy) else profile = proxy as? BluetoothA2dp
        } }
        override fun onServiceDisconnected(id: Int) { main.post { profile = null } }
    }, BluetoothProfile.A2DP) }
    fun paired(): List<BluetoothDevice> = adapter?.bondedDevices?.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }?.distinctBy { normalize(it.address) }?.sortedBy { it.name ?: it.address } ?: emptyList()
    fun connected(address: String): Boolean = runCatching { profile?.connectedDevices?.any { normalize(it.address) == normalize(address) } == true }.getOrDefault(false)
    fun preflight(address: String, acquiring: Boolean): String? {
        if (adapter?.isEnabled != true) return "Включи Bluetooth на телефоне"
        if (paired().none { normalize(it.address) == normalize(address) }) return "Выбери сопряжённые наушники на телефоне"
        val calls = CallGuard.read(context)
        if (calls.busy) return calls.reason
        val p = profile ?: return "Аудиопрофиль ещё не готов. Подожди несколько секунд"
        return try {
            if (p.connectedDevices.any { normalize(it.address) == normalize(address) } == acquiring) null
            else { p.javaClass.getMethod(if (acquiring) "connect" else "disconnect", BluetoothDevice::class.java); null }
        } catch (_: Exception) { "Эта версия Android запрещает управление A2DP. Подключи наушники в системных настройках" }
    }
    fun cancel() { operation++; stopProbe() }
    fun close() { closed = true; cancel(); profile?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }; profile = null; worker.shutdownNow() }
    private fun stopProbe() { runCatching { track?.stop() }; track?.release(); track = null }
    fun change(address: String, connect: Boolean, completion: (Boolean, String) -> Unit) {
        cancel(); val token = operation
        trace("A2DP ${if (connect) "acquire" else "release"} address=$address")
        preflight(address, connect)?.let { completion(false, it); return }
        val p = profile ?: return completion(false, "Аудиопрофиль недоступен")
        val device = paired().firstOrNull { normalize(it.address) == normalize(address) } ?: return completion(false, "Устройство не сопряжено")
        if (p.getConnectionState(device) == (if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED)) {
            if (connect) probe(address, token, completion) else completion(true, "Телефон освободил A2DP")
            return
        }
        worker.execute {
            val attempt = runCatching { p.javaClass.getMethod(if (connect) "connect" else "disconnect", BluetoothDevice::class.java).invoke(p, device) as? Boolean == true }
            main.post {
                if (token != operation || closed) return@post
                trace("A2DP invoke accepted=${attempt.getOrDefault(false)} error=${attempt.exceptionOrNull()?.javaClass?.simpleName ?: "none"}")
                if (attempt.getOrDefault(false)) poll(device, connect, token, 60, completion)
                else completion(false, "Android отклонил ${if (connect) "подключение" else "отключение"} A2DP. Используй настройки Bluetooth")
            }
        }
    }
    private fun poll(device: BluetoothDevice, connect: Boolean, token: Int, remaining: Int, completion: (Boolean, String) -> Unit) {
        if (token != operation || closed) return
        val connected = runCatching { profile?.getConnectionState(device) }.getOrNull()
        if (connected == if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED) {
            if (connect) probe(device.address, token, completion) else completion(true, "Телефон освободил A2DP")
        } else if (remaining <= 0) completion(false, "A2DP не подтвердил ${if (connect) "подключение" else "отключение"} за 12 секунд")
        else main.postDelayed({ poll(device, connect, token, remaining - 1, completion) }, 200)
    }
    private fun probe(address: String, token: Int, completion: (Boolean, String) -> Unit) {
        if (token != operation || closed) return
        if (CallGuard.read(context).busy) { completion(false, "Начался разговор. Проверка маршрута остановлена"); return }
        try {
            val pcm = ShortArray(44100)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track = t; check(t.write(pcm, 0, pcm.size) == pcm.size); t.setLoopPoints(0, pcm.size, -1); t.play()
            trace("A2DP profile ready; probing media route")
            checkRoute(address, token, t, 10, completion)
        } catch (_: Exception) { stopProbe(); completion(false, "Не удалось проверить аудиомаршрут Android") }
    }
    private fun checkRoute(address: String, token: Int, probe: AudioTrack, remaining: Int, completion: (Boolean, String) -> Unit) {
        if (token != operation || closed) return
        if (CallGuard.read(context).busy) { stopProbe(); completion(false, "Начался разговор. Проверка маршрута остановлена"); return }
        val routed = probe.routedDevice
        val verified = routed?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && normalize(routed.address) == normalize(address)
        if (!verified && remaining > 0) {
            main.postDelayed({ checkRoute(address, token, probe, remaining - 1, completion) }, 100)
            return
        }
        trace("A2DP route type=${routed?.type} address=${routed?.address} verified=$verified")
        stopProbe()
        completion(verified, if (verified) "A2DP подключён; тестовый медиапоток идёт в наушники. Проверь музыку в своём плеере" else "A2DP подключён, но маршрут тестового звука не подтверждён. Выбери наушники в системном аудиовыходе")
    }
    companion object { fun normalize(value: String) = value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' } }
}

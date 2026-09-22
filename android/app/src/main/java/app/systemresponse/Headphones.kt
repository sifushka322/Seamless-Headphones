package app.systemresponse

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.media.*
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object A2dpPendingPolicy {
    fun rejectionIsSettled(accepted: Boolean, state: Int?, operationToken: Int, pendingToken: Int): Boolean =
        operationToken == pendingToken && !accepted &&
        (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_DISCONNECTED)
}

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
    private var unsettled: BluetoothDevice? = null
    private var pendingToken = 0
    private var sawTransition = false
    private var adapterStarted = 0L
    private var lastRouteAddress = ""
    private var lastRouteAt = 0L
    fun diagnostics(address: String): String = "profileConnected=${connected(address)} pending=${unsettled != null} lastProbeAddress=$lastRouteAddress lastProbeAgeMs=${if (lastRouteAt > 0) SystemClock.elapsedRealtime() - lastRouteAt else -1}; probe is historical, not the player's current audible output"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (normalize(device.address) != normalize(unsettled?.address ?: "")) return
            // An old broadcast can arrive during a new attempt for the same address.
            // Re-read current profile state before releasing the in-flight guard.
            val state = runCatching { profile?.getConnectionState(device) }.getOrNull() ?: return
            trace("ADAPTER android state=$state elapsedMs=${SystemClock.elapsedRealtime() - adapterStarted}")
            if (state == BluetoothProfile.STATE_CONNECTING || state == BluetoothProfile.STATE_DISCONNECTING) sawTransition = true
            if (state == BluetoothProfile.STATE_CONNECTED || (state == BluetoothProfile.STATE_DISCONNECTED && sawTransition)) unsettled = null
        }
    }
    init {
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
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
    fun close() { context.unregisterReceiver(receiver); closed = true; cancel(); profile?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }; profile = null; worker.shutdownNow() }
    private fun stopProbe() { runCatching { track?.stop() }; track?.release(); track = null }
    fun change(address: String, connect: Boolean, completion: (Boolean, String, Boolean) -> Unit) {
        if (unsettled != null) { completion(false, "Предыдущая операция A2DP ещё завершается. Дождись состояния Bluetooth", false); return }
        cancel(); val token = operation
        adapterStarted = SystemClock.elapsedRealtime()
        trace("A2DP ${if (connect) "acquire" else "release"} address=$address")
        preflight(address, connect)?.let { completion(false, it, false); return }
        val p = profile ?: return completion(false, "Аудиопрофиль недоступен", false)
        val device = paired().firstOrNull { normalize(it.address) == normalize(address) } ?: return completion(false, "Устройство не сопряжено", false)
        if (p.getConnectionState(device) == (if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED)) {
            if (connect) probe(address, token, completion) else completion(true, "Телефон освободил A2DP", false)
            return
        }
        unsettled = device; pendingToken = token; sawTransition = false
        worker.execute {
            val attempt = runCatching { p.javaClass.getMethod(if (connect) "connect" else "disconnect", BluetoothDevice::class.java).invoke(p, device) as? Boolean == true }
            main.post {
                val state = runCatching { p.getConnectionState(device) }.getOrNull()
                // A rejected disconnect can leave the headset connected without a subsequent
                // broadcast. Both terminal states release the guard; uncertain transitions do not.
                // A cancelled call may settle itself, but cannot clear a newer attempt's guard.
                if (A2dpPendingPolicy.rejectionIsSettled(attempt.getOrDefault(false), state, token, pendingToken)) unsettled = null
                if (token != operation || closed) return@post
                trace("A2DP invoke accepted=${attempt.getOrDefault(false)} error=${attempt.exceptionOrNull()?.javaClass?.simpleName ?: "none"}")
                if (attempt.getOrDefault(false)) poll(device, connect, token, 60, completion)
                else completion(false, "Android отклонил ${if (connect) "подключение" else "отключение"} A2DP", connect && attempt.isSuccess && state == BluetoothProfile.STATE_DISCONNECTED)
            }
        }
    }
    private fun poll(device: BluetoothDevice, connect: Boolean, token: Int, remaining: Int, completion: (Boolean, String, Boolean) -> Unit) {
        if (token != operation || closed) return
        val connected = runCatching { profile?.getConnectionState(device) }.getOrNull()
        if (connected == BluetoothProfile.STATE_CONNECTING || connected == BluetoothProfile.STATE_DISCONNECTING) sawTransition = true
        if (connect && connected == BluetoothProfile.STATE_DISCONNECTED && sawTransition) {
            unsettled = null; completion(false, "A2DP завершил попытку без подключения", true); return
        }
        if (connected == if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED) {
            unsettled = null
            trace("ADAPTER android profile ready elapsedMs=${SystemClock.elapsedRealtime() - adapterStarted}")
            if (connect) probe(device.address, token, completion) else completion(true, "Телефон освободил A2DP", false)
        } else if (remaining <= 0) completion(false, "A2DP не подтвердил ${if (connect) "подключение" else "отключение"} за 12 секунд", false)
        else main.postDelayed({ poll(device, connect, token, remaining - 1, completion) }, 200)
    }
    private fun probe(address: String, token: Int, completion: (Boolean, String, Boolean) -> Unit) {
        if (token != operation || closed) return
        if (CallGuard.read(context).busy) { completion(false, "Начался разговор. Проверка маршрута остановлена", false); return }
        try {
            val pcm = ShortArray(44100)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track = t; check(t.write(pcm, 0, pcm.size) == pcm.size); t.setLoopPoints(0, pcm.size, -1); t.play()
            trace("A2DP profile ready; probing media route")
            checkRoute(address, token, t, 10, completion)
        } catch (_: Exception) { stopProbe(); completion(false, "Не удалось проверить аудиомаршрут Android", false) }
    }
    private fun checkRoute(address: String, token: Int, probe: AudioTrack, remaining: Int, completion: (Boolean, String, Boolean) -> Unit) {
        if (token != operation || closed) return
        if (CallGuard.read(context).busy) { stopProbe(); completion(false, "Начался разговор. Проверка маршрута остановлена", false); return }
        val routed = probe.routedDevice
        val verified = routed?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && normalize(routed.address) == normalize(address)
        if (!verified && remaining > 0) {
            main.postDelayed({ checkRoute(address, token, probe, remaining - 1, completion) }, 100)
            return
        }
        trace("A2DP route type=${routed?.type} address=${routed?.address} verified=$verified elapsedMs=${SystemClock.elapsedRealtime() - adapterStarted}")
        lastRouteAddress = routed?.address ?: "unknown"; lastRouteAt = SystemClock.elapsedRealtime()
        stopProbe()
        completion(verified, if (verified) "A2DP подключён; тестовый медиапоток идёт в наушники. Проверь музыку в своём плеере" else "A2DP подключён, но маршрут тестового звука не подтверждён. Выбери наушники в системном аудиовыходе", false)
    }
    companion object { fun normalize(value: String) = value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' } }
}

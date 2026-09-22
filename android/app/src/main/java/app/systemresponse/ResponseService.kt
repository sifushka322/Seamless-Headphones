package app.systemresponse

import android.app.*
import android.content.Intent
import android.os.*

class ResponseService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@ResponseService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var headphones: Headphones
    private var ble: BleClient? = null
    private var transaction: Transaction? = null
    private var requestAt = 0L
    private val completed = LinkedHashSet<String>()
    private var generation = 0
    private lateinit var media: MediaMonitor
    private val edges = MediaEdges()
    private var quietUntil = 0L
    private var remoteAt = 0L
    private var retryCount = 0
    private var nextSnapshot = 0L
    private val mediaTick = Runnable { tick() }
    private var lastTracedLocal: ActivityState? = null
    private var lastSent: ActivityState? = null
    private var remoteArmed = false
    var local = ActivityState(); private set
    var peer = ActivityState(); private set
    var voiceDiagnostics = "Ещё нет наблюдений"; private set
    var observedSources = "Ещё нет наблюдений"; private set
    private var lastObservation = ""
    var mediaAvailable = false; private set
    var callKnown = false; private set
    var discovered = emptySet<String>(); private set
    var autoReason = "Ожидаем связь с Mac"; private set
    var autoPaused = false; private set
    var owner = "unknown"; private set
    var lastDuration = "—"; private set
    val successful get() = prefs.getInt("transfers", 0)
    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) { prefs.edit().putBoolean("auto", value).apply(); settingsChanged() }
    var reconnect: Boolean
        get() = prefs.getBoolean("reconnect", true)
        set(value) { prefs.edit().putBoolean("reconnect", value).apply() }
    var delay: Int
        get() = prefs.getInt("delay", 2)
        set(value) { prefs.edit().putInt("delay", value.coerceIn(2, 5)).apply(); settingsChanged() }
    var fastDetection: Boolean
        get() = prefs.getBoolean("fastDetection", true)
        set(value) { prefs.edit().putBoolean("fastDetection", value).apply(); settingsChanged() }
    private val detectionDelay get() = if (fastDetection) 500L else delay * 1000L
    var allowed: Set<String>
        get() = prefs.getStringSet("sources", MediaMonitor.known.keys)?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet("sources", value.toSet()).apply(); settingsChanged() }
    private val ticker = object : Runnable {
        override fun run() { tick(); main.postDelayed(this, 1000) }
    }
    var status = "Связь выключена"; private set
    var detail = "Выбери наушники и добавь ключ с Mac."; private set
    var trusted = false; private set
    var running = false; private set
    var held = false; private set
    val busy get() = transaction != null || requestAt > 0
    var peerHeadset: String? = null; private set
    var peerHeadsetName = ""; private set
    val selectionMismatch get() = peerHeadset?.let { address.isEmpty() || it.isEmpty() || Headphones.normalize(it) != Headphones.normalize(address) } ?: false
    private val selectedHeadsetName get() = try { paired().firstOrNull { Headphones.normalize(it.address) == Headphones.normalize(address) }?.name ?: "Не выбраны" } catch (_: SecurityException) { "Нет разрешения Bluetooth" }
    val selectionSummary get() = "Android: ${selectedHeadsetName} [$address]\nMac: ${peerHeadsetName.ifEmpty { "ожидаем сведения" }} [${peerHeadset ?: "неизвестно"}]"
    val debugEvents = ArrayDeque<String>()
    var debugEnabled: Boolean
        get() = prefs.getBoolean("debug", false)
        set(value) { prefs.edit().putBoolean("debug", value).apply(); if (value) trace("Debug enabled; $selectionSummary"); changed?.invoke() }
    fun trace(message: String) {
        if (!debugEnabled) return
        if (listOf("TX type=ping ", "TX type=pong ", "RX type=ping ", "RX type=pong ", "TX type=activity ", "RX type=activity ", "TX type=autoStatus ", "RX type=autoStatus ").any { message.startsWith(it) }) return
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
        debugEvents.addFirst("$stamp [Android] $message")
        while (debugEvents.size > 1000) debugEvents.removeLast()
    }
    fun clearLogs() { events.clear(); debugEvents.clear(); changed?.invoke() }
    fun diagnostics(): String = "Seamless Headphones 0.4.0 · Android\nAndroid ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\nBLE: $status\nАвто: $autoReason\n$selectionSummary\nТранзакция: ${transaction?.id ?: "нет"} · ${transaction?.stage ?: if (requestAt > 0) "ожидаем Mac" else "нет"}\nАудиоадаптер: ${headphones.diagnostics(address)}\nСостояние Android: ${local.json()}\nСостояние Mac: ${peer.json()} ageMs=${if (remoteAt > 0) SystemClock.elapsedRealtime() - remoteAt else -1}\nЗащита Android: $voiceDiagnostics\nАктивные источники: $observedSources\nРазрешены: ${allowed.sorted().joinToString()}\nРазрешения: media=$mediaAvailable calls=$callKnown\nАвто: enabled=$autoEnabled delayMs=$detectionDelay fastDetection=$fastDetection\nТехнические логи: $debugEnabled\n\nИстория этого Android (источник в скобках):\n" + events.reversed().joinToString("\n") + "\n\nТехнический журнал Android:\n" + debugEvents.reversed().joinToString("\n")
    val events = ArrayDeque<String>()
    var changed: (() -> Unit)? = null
    private data class Transaction(val id: String, val target: String, val address: String, val automatic: Boolean, val started: Long = SystemClock.elapsedRealtime(), val commands: HandoffCommands = HandoffCommands(target)) { val stage get() = commands.stage }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    var address: String
        get() = prefs.getString("headphones", "") ?: ""
        set(value) { if (busy) return; trace("Selected headset=$value"); prefs.edit().putString("headphones", value).apply(); owner = "unknown"; settingsChanged() }

    override fun onCreate() {
        super.onCreate(); headphones = Headphones(this, ::trace); media = MediaMonitor(this) { main.removeCallbacks(mediaTick); main.post(mediaTick) }; main.post(ticker)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("connection", "Связь с Mac", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent) = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopLink(); stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification("Связываемся с Mac…"))
        if (!running) startLink()
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ResponseService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "connection").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Seamless Headphones").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
    }
    fun paired() = try { headphones.paired() } catch (_: SecurityException) { emptyList() }
    private fun startLink() {
        generation++; ble?.stop(); edges.reset(); lastSent = null; remoteAt = 0; remoteArmed = false; peerHeadset = null; peerHeadsetName = ""
        val secret = runCatching { SecretStore(this).load() }.getOrNull()
        if (secret == null) { detail = "Сначала сохрани ключ с Mac"; stopLink(); return }
        running = true
        ble = BleClient(this, secret, { text, auth ->
            val newSession = auth && !trusted
            status = text; trusted = auth; emit(text); trace("BLE trusted=$auth status=$text")
            if (newSession) { retryCount = 0; edges.reset(); lastSent = null }
            getSystemService(NotificationManager::class.java).notify(1, notification(text))
        }, ::receive, {
            abort("Связь потеряна. Текущий звук не меняем", notify = false); edges.reset(); peer = ActivityState(); remoteAt = 0
            if (running && reconnect && retryCount < 8 && !status.contains("ключ", true) && !status.contains("доверия", true)) {
                retryCount++; val wait = minOf(60, 5 * (1 shl minOf(4, retryCount - 1)))
                detail = "Восстановим связь через $wait с · попытка $retryCount из 8"; val token = generation
                main.postDelayed({ if (running && generation == token && !trusted) startLink() }, wait * 1000L)
            } else { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            changed?.invoke()
        }, ::trace)
        ble?.start(); changed?.invoke()
    }
    fun stopLink() {
        generation++; abort("Связь остановлена", notify = true); ble?.stop(); ble = null
        running = false; trusted = false; status = "Связь выключена"; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); changed?.invoke()
    }
    fun hold(value: Boolean) { held = value; if (value) abort("Передача заблокирована пользователем", true); changed?.invoke() }
    fun request(target: String) {
        if (!trusted) { detail = "Сначала свяжи телефон с Mac"; changed?.invoke(); return }
        if (held || busy) { detail = if (held) "Сними удержание на телефоне" else "Передача уже выполняется"; changed?.invoke(); return }
        if (address.isBlank() || selectionMismatch) { detail = "Выбери одну пару на обоих устройствах.\n$selectionSummary"; emit(detail); return }
        remoteArmed = false
        requestAt = SystemClock.elapsedRealtime()
        trace("REQUEST target=$target; $selectionSummary")
        ble?.send(Packet("request", target = target)); detail = "Ждём готовность Mac…"; changed?.invoke()
    }
    private fun receive(packet: Packet) {
        when (packet.type) {
            "activity" -> runCatching { ActivityState.parse(packet.detail) }.onSuccess { peer = it; remoteAt = SystemClock.elapsedRealtime(); peerHeadset = packet.device; peerHeadsetName = packet.target }
            "autoReset" -> { if (!busy) { quietUntil = 0; edges.reset(); lastSent = null; remoteArmed = false; trace("AUTO reset; waiting for coordinator and fresh playback") } }
            "autoStatus" -> { autoReason = packet.detail; autoPaused = packet.target == "paused"; remoteArmed = packet.device == "armed"; changed?.invoke() }
            "notice" -> { requestAt = 0; if (transaction == null) { detail = packet.detail; emit(packet.detail, "Mac") } }
            "prepare" -> {
                if (packet.id.isBlank() || completed.contains(packet.id)) return
                if (transaction != null) { ble?.send(Packet("error", id = packet.id, detail = "Телефон занят другой передачей")); return }
                requestAt = 0
                trace("PREPARE tx=${packet.id} target=${packet.target} remoteDevice=${packet.device} localDevice=$address")
                val reason = when {
                    held -> "На телефоне включено удержание"
                    packet.detail == "auto" && (!autoEnabled || !local.available || local.call || !peer.automation || peer.call || peer.held || SystemClock.elapsedRealtime() - remoteAt > 7000) -> "Автопереключение сейчас недоступно на телефоне"
                    packet.target !in listOf("mac", "android") -> "Неизвестное направление передачи"
                    Headphones.normalize(address) != Headphones.normalize(packet.device) -> "На устройствах выбраны разные наушники. Mac: ${packet.device.ifEmpty { "не выбраны" }}; Android: ${address.ifEmpty { "не выбраны" }}. Открой «Устройства» и сравни адреса"
                    else -> headphones.preflight(address, packet.target == "android")
                }
                if (reason != null) { detail = reason; trace("REJECT tx=${packet.id}: $reason"); ble?.send(Packet("error", id = packet.id, detail = reason)); emit(reason); return }
                val tx = Transaction(packet.id, packet.target, address, packet.detail == "auto"); transaction = tx
                ble?.send(Packet("ready", id = tx.id, device = address)); detail = "Готовы к передаче. Ждём команду Mac"; changed?.invoke()
                val token = generation
                main.postDelayed({ if (generation == token && transaction?.id == tx.id) abort("Время передачи истекло. Проверь текущий аудиовыход", true) }, 35_000)
            }
            "release", "acquire", "tryAcquire" -> {
                val tx = transaction ?: return
                if (packet.id != tx.id || !tx.commands.accept(packet.type)) return
                val acquire = packet.type != "release"
                if (held) { abort("На телефоне включено удержание", true); return }
                val calls = CallGuard.read(this)
                if (tx.automatic && (!autoEnabled || !calls.known || calls.busy || (acquire && !local.playing))) { abort("Условия автопереключения изменились", true); return }
                trace("STAGE tx=${tx.id} stage=${tx.stage} command=${packet.type}"); detail = if (acquire) "Активно подключаем наушники…" else "Освобождаем наушники…"; changed?.invoke()
                headphones.change(tx.address, acquire) { ok, reason, retrySafe ->
                    if (transaction?.id != tx.id) return@change
                    detail = reason; emit(reason)
                    val reply = tx.commands.result(packet.type, ok, retrySafe)
                    trace("ADAPTER_RESULT tx=${tx.id} command=${packet.type} result=$reply elapsedMs=${SystemClock.elapsedRealtime() - tx.started}")
                    ble?.send(Packet(reply, id = tx.id, target = if (packet.type == "tryAcquire") "early" else "normal", detail = reason))
                    if (reply == "error") clearTransaction()
                }
            }
            "complete" -> if (transaction?.id == packet.id && transaction?.commands?.canComplete() == true) {
                owner = packet.target; lastDuration = String.format(java.util.Locale.ROOT, "%.1f с", (SystemClock.elapsedRealtime() - transaction!!.started) / 1000.0)
                prefs.edit().putInt("transfers", successful + 1).apply(); quietUntil = 0; remoteArmed = false
                detail = packet.detail; clearTransaction(); emit(if (packet.target == "android") "Телефон принял наушники" else "Mac принял наушники")
            }
            "cancel" -> if (transaction?.id == packet.id) abort("Mac остановил ожидание. Уже начатое системное подключение может завершиться", false)
        }
    }
    private fun clearTransaction() {
        transaction?.let { completed.add(it.id) }
        while (completed.size > 64) completed.remove(completed.first())
        transaction = null; requestAt = 0; headphones.cancel(); changed?.invoke()
    }
    fun abort(reason: String, notify: Boolean = true) {
        transaction?.let { if (notify) ble?.send(Packet("error", id = it.id, detail = reason)) }
        clearTransaction(); quietUntil = SystemClock.elapsedRealtime() + 10_000; detail = reason; emit(reason)
    }
    fun resumeAuto() { if (busy || !trusted) return; ble?.send(Packet("resumeAuto")); remoteArmed = false; edges.reset() }
    fun setMode(idleOnly: Boolean) { if (!trusted) { detail = "Сначала свяжи телефон с Mac"; changed?.invoke(); return }; ble?.send(Packet("mode", target = if (idleOnly) "idle" else "follow")) }
    private fun settingsChanged() {
        edges.reset(); lastSent = null
        if (transaction?.automatic == true) abort("Настройки автоматизации изменены")
        changed?.invoke()
    }
    fun appName(pkg: String) = media.name(pkg)
    private fun tick() {
        val snapshotDue = SystemClock.elapsedRealtime() >= nextSnapshot
        if (snapshotDue) nextSnapshot = SystemClock.elapsedRealtime() + 3000
        if (requestAt > 0 && SystemClock.elapsedRealtime() - requestAt >= 35_000) {
            requestAt = 0; detail = "Mac не подтвердил команду за 35 секунд. Проверь журнал связи"; emit(detail)
        }
        val observed = media.sample(); val calls = CallGuard.read(this)
        voiceDiagnostics = "${calls.reason}; ${calls.diagnostics}"
        observedSources = observed.playing.sorted().joinToString { "${media.name(it)} [$it] · ${if (it in allowed) "разрешён" else "не выбран"}" }.ifEmpty { "Звук не обнаружен" }
        val observation = "$voiceDiagnostics; mediaAvailable=${observed.available}; active=$observedSources; remoteArmed=$remoteArmed"
        if (lastObservation != observation) { trace("OBSERVE $observation"); lastObservation = observation }
        mediaAvailable = observed.available; callKnown = calls.known; discovered = discovered + observed.discovered
        edges.sample(observed.playing.intersect(allowed), SystemClock.elapsedRealtime(), detectionDelay,
            !trusted || !autoEnabled || !remoteArmed || !calls.known || !observed.available || peer.call || peer.held || held || busy || calls.busy || SystemClock.elapsedRealtime() - remoteAt > 7000 || SystemClock.elapsedRealtime() < quietUntil)
        main.removeCallbacks(mediaTick)
        edges.nextDeadline(detectionDelay)?.let { main.postDelayed(mediaTick, maxOf(1L, it - SystemClock.elapsedRealtime())) }
        local = ActivityState(observed.available && calls.known, observed.playing.isNotEmpty(), calls.busy, held, autoEnabled,
            edges.event, media.name(edges.source).take(180), headphones.connected(address), calls.reason.take(180), 2)
        if (local != lastTracedLocal) { lastTracedLocal = local; trace("LOCAL event=${local.event} playing=${local.playing} source=${local.source} call=${local.call}") }
        if (busy && calls.busy) abort("Начался разговор. Передача остановлена")
        if (trusted && (local != lastSent || snapshotDue)) { ble?.send(Packet("activity", target = selectedHeadsetName, detail = local.json(), device = address)); lastSent = local }
        if (!trusted) autoReason = "Ожидаем связь с Mac"
        else if (!autoEnabled) autoReason = "Автопереключение выключено на телефоне"
        else if (!mediaAvailable) autoReason = "Разреши доступ к медиасессиям"
        else if (!callKnown) autoReason = "Разреши состояние вызовов для защиты разговора"
        else if (calls.busy) autoReason = calls.reason
        changed?.invoke()
    }
    private fun emit(text: String, source: String = "Android") {
        events.addFirst("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} [$source]  $text")
        while (events.size > 60) events.removeLast()
        changed?.invoke()
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); stopLink(); headphones.close(); media.close(); super.onDestroy() }
}

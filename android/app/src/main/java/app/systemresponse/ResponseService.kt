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
    private val completed = LinkedHashSet<String>()
    private var generation = 0
    private lateinit var media: MediaMonitor
    private val edges = MediaEdges()
    private var quietUntil = 0L
    private var remoteAt = 0L
    private var retryCount = 0
    private var ticks = 0
    private var lastSent: ActivityState? = null
    private var remoteArmed = false
    var local = ActivityState(); private set
    var peer = ActivityState(); private set
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
    val busy get() = transaction != null
    val events = ArrayDeque<String>()
    var changed: (() -> Unit)? = null
    private data class Transaction(val id: String, val target: String, val address: String, val automatic: Boolean, val started: Long = SystemClock.elapsedRealtime(), var stage: String = "prepared")
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    var address: String
        get() = prefs.getString("headphones", "") ?: ""
        set(value) { if (busy) return; prefs.edit().putString("headphones", value).apply(); owner = "unknown"; settingsChanged() }

    override fun onCreate() {
        super.onCreate(); headphones = Headphones(this); media = MediaMonitor(this); main.post(ticker)
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
    fun paired() = headphones.paired()
    private fun startLink() {
        generation++; ble?.stop(); edges.reset(); lastSent = null; remoteAt = 0; remoteArmed = false
        val secret = runCatching { SecretStore(this).load() }.getOrNull()
        if (secret == null) { detail = "Сначала сохрани ключ с Mac"; stopLink(); return }
        running = true
        ble = BleClient(this, secret, { text, auth ->
            val newSession = auth && !trusted
            status = text; trusted = auth; emit(text)
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
        })
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
        quietUntil = SystemClock.elapsedRealtime() + 120_000
        ble?.send(Packet("request", target = target)); detail = "Команда отправлена на Mac"; changed?.invoke()
    }
    private fun receive(packet: Packet) {
        when (packet.type) {
            "activity" -> runCatching { ActivityState.parse(packet.detail) }.onSuccess { peer = it; remoteAt = SystemClock.elapsedRealtime() }
            "autoStatus" -> { autoReason = packet.detail; autoPaused = packet.target == "paused"; remoteArmed = packet.device == "armed"; changed?.invoke() }
            "notice" -> { if (!busy) { detail = packet.detail; emit(packet.detail) } }
            "prepare" -> {
                if (packet.id.isBlank() || completed.contains(packet.id)) return
                if (busy) { ble?.send(Packet("error", id = packet.id, detail = "Телефон занят другой передачей")); return }
                val reason = when {
                    held -> "На телефоне включено удержание"
                    packet.detail == "auto" && (!autoEnabled || !local.available || local.call || !peer.automation || peer.call || peer.held || SystemClock.elapsedRealtime() - remoteAt > 7000) -> "Автопереключение сейчас недоступно на телефоне"
                    packet.target !in listOf("mac", "android") -> "Неизвестное направление передачи"
                    Headphones.normalize(address) != Headphones.normalize(packet.device) -> "Выбери одни и те же наушники на Mac и Android"
                    else -> headphones.preflight(address, packet.target == "android")
                }
                if (reason != null) { ble?.send(Packet("error", id = packet.id, detail = reason)); emit(reason); return }
                val tx = Transaction(packet.id, packet.target, address, packet.detail == "auto"); transaction = tx
                ble?.send(Packet("ready", id = tx.id, device = address)); detail = "Готовы к передаче. Ждём команду Mac"; changed?.invoke()
                val token = generation
                main.postDelayed({ if (generation == token && transaction?.id == tx.id) abort("Время передачи истекло. Проверь текущий аудиовыход", true) }, 35_000)
            }
            "release", "acquire" -> {
                val tx = transaction ?: return
                if (packet.id != tx.id || tx.stage != "prepared") return
                val acquire = packet.type == "acquire"
                if (acquire != (tx.target == "android")) return
                if (held) { abort("На телефоне включено удержание", true); return }
                val calls = CallGuard.read(this)
                if (tx.automatic && (!autoEnabled || !calls.known || calls.busy || (acquire && !local.playing))) { abort("Условия автопереключения изменились", true); return }
                tx.stage = "working"; detail = if (acquire) "Активно подключаем наушники…" else "Освобождаем наушники…"; changed?.invoke()
                headphones.change(tx.address, acquire) { ok, reason ->
                    if (transaction?.id != tx.id) return@change
                    detail = reason; emit(reason)
                    if (ok) { tx.stage = "awaiting-completion"; ble?.send(Packet(if (acquire) "result" else "released", id = tx.id, detail = reason)) }
                    else { ble?.send(Packet("error", id = tx.id, detail = reason)); clearTransaction() }
                }
            }
            "complete" -> if (transaction?.id == packet.id) {
                owner = packet.target; lastDuration = String.format(java.util.Locale.ROOT, "%.1f с", (SystemClock.elapsedRealtime() - transaction!!.started) / 1000.0)
                prefs.edit().putInt("transfers", successful + 1).apply(); quietUntil = SystemClock.elapsedRealtime() + 20_000
                detail = packet.detail; clearTransaction(); emit(if (packet.target == "android") "Телефон принял наушники" else "Mac принял наушники")
            }
            "cancel" -> if (transaction?.id == packet.id) abort("Mac остановил ожидание. Уже начатое системное подключение может завершиться", false)
        }
    }
    private fun clearTransaction() {
        transaction?.let { completed.add(it.id) }
        while (completed.size > 64) completed.remove(completed.first())
        transaction = null; headphones.cancel(); changed?.invoke()
    }
    fun abort(reason: String, notify: Boolean = true) {
        transaction?.let { if (notify) ble?.send(Packet("error", id = it.id, detail = reason)) }
        clearTransaction(); quietUntil = SystemClock.elapsedRealtime() + 10_000; detail = reason; emit(reason)
    }
    fun resumeAuto() { ble?.send(Packet("resumeAuto")); edges.reset() }
    fun setMode(idleOnly: Boolean) { if (!trusted) { detail = "Сначала свяжи телефон с Mac"; changed?.invoke(); return }; ble?.send(Packet("mode", target = if (idleOnly) "idle" else "follow")) }
    private fun settingsChanged() {
        edges.reset(); lastSent = null
        if (transaction?.automatic == true) abort("Настройки автоматизации изменены")
        changed?.invoke()
    }
    fun appName(pkg: String) = media.name(pkg)
    private fun tick() {
        ticks++
        val observed = media.sample(); val calls = CallGuard.read(this)
        mediaAvailable = observed.available; callKnown = calls.known; discovered = discovered + observed.discovered
        edges.sample(observed.playing.intersect(allowed), SystemClock.elapsedRealtime(), delay * 1000L,
            !trusted || !autoEnabled || !remoteArmed || !calls.known || !observed.available || peer.call || peer.held || held || busy || calls.busy || SystemClock.elapsedRealtime() - remoteAt > 7000 || SystemClock.elapsedRealtime() < quietUntil)
        local = ActivityState(observed.available && calls.known, observed.playing.isNotEmpty(), calls.busy, held, autoEnabled,
            edges.event, media.name(edges.source).take(180), headphones.connected(address))
        if (busy && calls.busy) abort("Начался разговор. Передача остановлена")
        if (trusted && (local != lastSent || ticks % 3 == 0)) { ble?.send(Packet("activity", detail = local.json())); lastSent = local }
        if (!trusted) autoReason = "Ожидаем связь с Mac"
        else if (!autoEnabled) autoReason = "Автопереключение выключено на телефоне"
        else if (!mediaAvailable) autoReason = "Разреши доступ к медиасессиям"
        else if (!callKnown) autoReason = "Разреши состояние вызовов для защиты разговора"
        changed?.invoke()
    }
    private fun emit(text: String) {
        events.addFirst("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}  $text")
        while (events.size > 60) events.removeLast()
        changed?.invoke()
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); stopLink(); headphones.close(); super.onDestroy() }
}

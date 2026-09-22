package app.systemresponse

import android.Manifest
import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*

class MainActivity : ComponentActivity() {
    private val appearance by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }
    private val settingsPrefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val dark get() = when (appearance.getString("theme", "system")) { "dark" -> true; "light" -> false; else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    private val ink get() = Color.parseColor(if (dark) "#EDF2F0" else "#1D2D2B")
    private val muted get() = Color.parseColor(if (dark) "#A1B0AC" else "#677C75")
    private val canvas get() = Color.parseColor(if (dark) "#101517" else "#F4F8F5")
    private val surface get() = Color.parseColor(if (dark) "#1A2224" else "#FFFFFF")
    private val accent get() = Color.parseColor(when (appearance.getString("accent", "mint")) { "cobalt" -> if (dark) "#96B4FF" else "#365ECD"; "iris" -> if (dark) "#D4ACFF" else "#814AB8"; else -> if (dark) "#80DFBC" else "#167557" })
    private fun tint(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
    private var service: ResponseService? = null
    private var bound = false
    private var page = 0
    private lateinit var content: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var scroll: ScrollView
    private var status: TextView? = null
    private var detail: TextView? = null
    private var autoStatus: TextView? = null
    private var mediaPermission: TextView? = null
    private var callPermission: TextView? = null
    private var logView: TextView? = null
    private var debugView: TextView? = null
    private var selectionView: TextView? = null
    private var matchButton: Button? = null
    private var resumeButton: Button? = null
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            val code = runCatching { PairingCode.parse(raw) }.getOrNull()
            if (code == null) toast("Это не QR-код связи Seamless Headphones")
            else confirmPairing(code)
        }
    }
    private fun confirmPairing(code: PairingCode) {
        if (service?.running == true) { toast("Сначала останови связь с Mac"); return }
        val headset = if (bluetoothGranted()) runCatching { service?.paired()?.firstOrNull { Headphones.normalize(it.address) == Headphones.normalize(code.device) } }.getOrNull() else null
        val headsetName = try { headset?.name ?: "наушники" } catch (_: SecurityException) { "наушники" }
        val message = if (headset != null) "Сохранить ключ Mac и выбрать $headsetName?" else "Сохранить ключ этого Mac? Наушники выбери отдельно в разделе «Устройства»."
        android.app.AlertDialog.Builder(this).setTitle("Связать с этим Mac?").setMessage(message)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                if (service?.running == true) { toast("Сначала останови связь"); return@setPositiveButton }
                try {
                    SecretStore(this).save(code.key)
                    if (headset != null) service?.address = headset.address
                    toast("Ключ сохранён. Нажми «Найти Mac»"); render()
                } catch (_: Exception) { toast("Не удалось сохранить ключ в защищённом хранилище") }
            }.show()
    }
    private var metrics: TextView? = null
    private var ownerText: TextView? = null
    private var connect: Button? = null
    private var toMac: Button? = null
    private var toPhone: Button? = null
    private var picker: Spinner? = null
    private var holdSwitch: Switch? = null
    private var devices = emptyList<android.bluetooth.BluetoothDevice>()
    private var pendingAction: (() -> Unit)? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ResponseService.LocalBinder).service; service?.changed = ::refresh; render()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; refresh() }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun text(value: String, size: Float = 14f, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (secondary) muted else ink)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun shape(color: Int, radius: Int = 20, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun stack(padding: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
    private fun gap(parent: LinearLayout, value: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(value))) }
    private fun card(parent: LinearLayout = content, action: (LinearLayout) -> Unit): LinearLayout {
        val view = stack(20).apply { background = shape(surface, 22, tint(ink, 15)) }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }); action(view); return view
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0; minHeight = dp(48); minimumHeight = dp(48)
        setTextColor(if (primary) if (dark) Color.parseColor("#102921") else Color.WHITE else accent)
        background = shape(if (primary) accent else tint(accent, 22), 13)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }
    private fun pill(value: String): TextView = text(value, 11f, true).apply {
        setTextColor(accent); setPadding(dp(11), dp(7), dp(11), dp(7)); background = shape(tint(accent, 22), 30)
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }
    private fun toggle(parent: LinearLayout, title: String, subtitle: String, value: Boolean, action: (Boolean) -> Unit): Switch {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(54) }
        val labels = stack().apply { addView(text(title, 15f, true)); gap(this, 5); addView(text(subtitle, 12f, secondary = true)) }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(14) })
        val control = Switch(this).apply { isChecked = value; contentDescription = title; minWidth = dp(48); minHeight = dp(48); thumbTintList = android.content.res.ColorStateList.valueOf(accent); setOnCheckedChangeListener { _, on -> action(on) } }
        row.addView(control); parent.addView(row); return control
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState); page = savedInstanceState?.getInt("page") ?: 0
        window.statusBarColor = canvas; window.navigationBarColor = canvas
        val root = stack().apply { setBackgroundColor(canvas) }
        root.setOnApplyWindowInsetsListener { view, insets -> val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets }
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = stack(18); scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation = LinearLayout(this).apply { setPadding(dp(8), dp(9), dp(8), dp(9)); background = shape(surface, 0) }
        root.addView(navigation); setContentView(root)
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        render()
        if (bluetoothGranted()) bind()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }
    private fun render() {
        content.removeAllViews(); navigation.removeAllViews()
        status = null; detail = null; autoStatus = null; mediaPermission = null; callPermission = null; logView = null; debugView = null; selectionView = null; matchButton = null; resumeButton = null; metrics = null; ownerText = null
        connect = null; toMac = null; toPhone = null; picker = null; holdSwitch = null
        val titles = listOf("Обзор", "Авто", "Устройства", "Настройки")
        val icons = listOf(R.drawable.nav_overview, R.drawable.nav_auto, R.drawable.nav_devices, R.drawable.nav_settings)
        titles.forEachIndexed { index, title ->
            val color = if (index == page) accent else muted
            navigation.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(72)
                isClickable = true; isFocusable = true; isSelected = index == page
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                background = shape(if (index == page) tint(accent, 24) else Color.TRANSPARENT, 14)
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icons[index]); imageTintList = android.content.res.ColorStateList.valueOf(color)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(title, 12f, index == page).apply {
                    gravity = Gravity.CENTER; setTextColor(color); setSingleLine(true)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                contentDescription = title; setOnClickListener { page = index; render(); scroll.scrollTo(0, 0) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(1); marginEnd = dp(1) })
        }
        content.addView(text("SEAMLESS  /  HEADPHONES", 11f, true).apply { letterSpacing = .16f; setTextColor(accent) }); gap(content, 16)
        content.addView(text(listOf("Твой звук.\nТвои правила.", "Само. Но по\nтвоим правилам.", "Два устройства.\nОдна пара.", "Сделай\nSeamless своим.")[page], 28f, true)); gap(content, 8)
        content.addView(text(listOf("Музыка продолжается. Устройства меняются.", "Разрешения, источники и защита от лишних передач.", "Настрой связь один раз. Дальше просто слушай.", "Оформление, связь и история событий.")[page], 13f, secondary = true)); gap(content, 24)
        content.addView(button("Скопировать диагностику") { copyDiagnostics() }); gap(content, 12)
        when (page) { 0 -> overview(); 1 -> automation(); 2 -> devicePage(); else -> settings() }
        gap(content, 8); content.addView(text("0.3.1  ·  БЕЗ ОБЛАКА  ·  БЕЗ ТЕЛЕМЕТРИИ", 10f, secondary = true).apply { letterSpacing = .10f }); refresh()
    }
    private fun overview() {
        val s = service
        if (s?.trusted != true || s.address.isEmpty()) card { c ->
            c.addView(text("Начнём с подключения", 17f, true)); gap(c, 7); c.addView(text("Выбери наушники и добавь ключ своего Mac.", 13f, secondary = true))
            c.addView(button("Настроить устройства", true) { page = 2; render() })
        }
        card { c ->
            c.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(blend(surface, accent, .10f), surface)).apply { cornerRadius = dp(22).toFloat() }
            ownerText = pill("ГОТОВИМСЯ К ПОДКЛЮЧЕНИЮ"); c.addView(ownerText); gap(c, 18)
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val labels = stack(); labels.addView(text(selectedName(), 23f, true)); gap(labels, 8); labels.addView(text("Слушай там, где удобно.", 12f, secondary = true))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(HeadphonesArt(this, accent), LinearLayout.LayoutParams(dp(102), dp(110))); c.addView(row); gap(c, 10)
            status = text("Связь выключена", 12f, secondary = true); c.addView(status)
        }
        card { c ->
            c.addView(text("Куда передать звук", 17f, true)); gap(c, 8)
            toPhone = button("Забрать на Android", true) { service?.request("android") }; c.addView(toPhone)
            toMac = button("Передать на Mac") { service?.request("mac") }; c.addView(toMac)
            detail = text("", 13f, secondary = true); gap(c, 12); c.addView(detail)
        }
        card { c ->
            c.addView(text("✦  Автопереключение", 17f, true)); gap(c, 8); autoStatus = text("Ожидаем подключение", 13f, secondary = true); c.addView(autoStatus)
            c.addView(button("Настроить автоматизацию") { page = 1; render() })
        }
        card { c -> holdSwitch = toggle(c, "Удерживать здесь", "Не передавать наушники до отключения удержания.", s?.held == true) { service?.hold(it) } }
        metrics = text("", 12f, secondary = true); content.addView(metrics); gap(content, 12)
    }
    private fun automation() {
        val s = service
        card { c ->
            toggle(c, "Автопереключение", "Работает вместе с автоматизацией на Mac.", s?.autoEnabled ?: settingsPrefs.getBoolean("auto", true)) { value ->
                service?.let { it.autoEnabled = value } ?: settingsPrefs.edit().putBoolean("auto", value).apply()
            }; gap(c, 16)
            autoStatus = text("", 13f, secondary = true); c.addView(autoStatus)
            resumeButton = button("Вернуть автоматику сейчас") { service?.resumeAuto() }; c.addView(resumeButton)
        }
        card { c ->
            c.addView(text("Два разрешения для автоматики", 17f, true)); gap(c, 15)
            mediaPermission = text("", 13f, true); c.addView(mediaPermission); gap(c, 6)
            c.addView(text("Медиасессии сообщают, когда ты нажимаешь Play/Pause. Содержимое уведомлений приложение не читает.", 12f, secondary = true))
            c.addView(button("Разрешить медиасессии") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }); gap(c, 18)
            callPermission = text("", 13f, true); c.addView(callPermission); gap(c, 6)
            c.addView(text("Состояние вызова нужно, чтобы не мешать разговору. Номера, контакты и журнал звонков не считываются.", 12f, secondary = true))
            c.addView(button("Разрешить защиту звонков") { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 2) })
        }
        card { c ->
            c.addView(text("Когда переключать", 17f, true)); gap(c, 8)
            c.addView(text("Общее правило хранится на Mac. Настрой его здесь, когда устройства связаны.", 12f, secondary = true))
            c.addView(button("Следовать новому воспроизведению") { service?.setMode(false) })
            c.addView(button("Только когда источник на паузе") { service?.setMode(true) })
            gap(c, 12); c.addView(text("Приоритет ручной команды и пауза настраиваются на Mac. «Вернуть автоматику сейчас» сбрасывает ожидание. Затем поставь музыку на паузу и запусти заново. Звонки и удержание по-прежнему защищены.", 12f, secondary = true))
        }
        card { c ->
            c.addView(text("Приложения на телефоне", 17f, true)); gap(c, 8)
            c.addView(text("Только выбранные плееры могут запросить передачу. Обычные уведомления не являются медиасессиями.", 12f, secondary = true)); gap(c, 14)
            (MediaMonitor.known.keys + (s?.discovered ?: emptySet())).distinct().forEach { pkg ->
                val item = CheckBox(this).apply { text = s?.appName(pkg) ?: MediaMonitor.known[pkg] ?: pkg; setTextColor(ink); textSize = 14f; minHeight = dp(48); buttonTintList = android.content.res.ColorStateList.valueOf(accent); isChecked = (s?.allowed ?: settingsPrefs.getStringSet("sources", MediaMonitor.known.keys)!!).contains(pkg)
                    setOnCheckedChangeListener { _, checked ->
                        val current = service?.allowed ?: settingsPrefs.getStringSet("sources", MediaMonitor.known.keys)!!.toSet()
                        val updated = if (checked) current + pkg else current - pkg
                        service?.let { it.allowed = updated } ?: settingsPrefs.edit().putStringSet("sources", updated).apply()
                    }
                }; c.addView(item)
            }
            c.addView(button("Обновить список плееров") { render() }); gap(c, 15)
            val savedDelay = s?.delay ?: settingsPrefs.getInt("delay", 2)
            val delayLabel = text("Проверять новый звук: $savedDelay с", 13f, true); c.addView(delayLabel)
            c.addView(SeekBar(this).apply { max = 3; progress = savedDelay - 2; minHeight = dp(48); contentDescription = "Задержка автопереключения"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar?, value: Int, user: Boolean) { delayLabel.text = "Проверять новый звук: ${value + 2} с"; if (user) { service?.let { it.delay = value + 2 } ?: settingsPrefs.edit().putInt("delay", value + 2).apply() } }
                    override fun onStartTrackingTouch(view: SeekBar?) {} ; override fun onStopTrackingTouch(view: SeekBar?) {}
                })
            })
        }
    }
    private fun devicePage() {
        card { c ->
            c.addView(text("01  /  Твои наушники", 17f, true)); gap(c, 8); c.addView(text("Сопряги одну и ту же пару с телефоном и Mac в настройках Bluetooth.", 13f, secondary = true)); gap(c, 12)
            picker = Spinner(this).apply { minimumHeight = dp(48) }; c.addView(picker)
            refreshDevices()
            picker?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { if (pos > 0 && pos <= devices.size && service?.address != devices[pos - 1].address) service?.address = devices[pos - 1].address }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            selectionView = text("", 12f, secondary = true).apply { setTextIsSelectable(true) }; gap(c, 10); c.addView(selectionView)
            matchButton = button("Выбрать наушники как на Mac") {
                val peer = service?.peerHeadset
                val match = devices.firstOrNull { Headphones.normalize(it.address) == Headphones.normalize(peer ?: "") }
                if (match != null) { service?.address = match.address; refreshDevices(); refresh() }
                else toast("Сначала сопряги эти наушники с телефоном в настройках Bluetooth")
            }; c.addView(matchButton)
            c.addView(button("Разрешить Bluetooth / обновить") { withBluetooth { if (!bound) bind() else refreshDevices() } })
            c.addView(button("Настройки Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        }
        card { c ->
            c.addView(text("02  /  Добавь свой Mac", 17f, true)); gap(c, 8); c.addView(text("На Mac открой «Устройства → Связать телефон» и перенеси ключ сюда.", 13f, secondary = true)); gap(c, 12)
            c.addView(button("Сканировать QR с Mac", true) {
                if (service?.running == true) { toast("Сначала останови связь"); return@button }
                qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("")
                    .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false)
                    .setCaptureActivity(PairingScannerActivity::class.java))
            }); gap(c, 12)
            c.addView(text("Или вставь ключ вручную", 12f, secondary = true))
            val key = EditText(this).apply { hint = "Ключ связи с Mac"; setSingleLine(); textSize = 14f; setTextColor(ink); setHintTextColor(muted); minHeight = dp(52); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO; isSaveEnabled = false }
            c.addView(key)
            c.addView(button("Сохранить ключ") {
                if (service?.running == true) { toast("Сначала останови связь"); return@button }
                try { SecretStore(this).save(key.text.toString()); key.text.clear(); toast("Ключ сохранён в защищённом хранилище") } catch (_: Exception) { toast("Проверь 44 символа ключа Base64 с Mac") }
            }); gap(c, 14)
            status = text("", 13f, true); c.addView(status)
            connect = button("Найти Mac", true) { if (service?.running == true) service?.stopLink() else withBluetooth { startForegroundService(Intent(this, ResponseService::class.java)) } }; c.addView(connect)
            c.addView(button("Забыть Mac") {
                android.app.AlertDialog.Builder(this).setTitle("Удалить ключ Mac?").setMessage("Для следующего подключения потребуется снова добавить ключ.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ -> service?.stopLink(); SecretStore(this).clear(); toast("Ключ удалён") }.show()
            })
        }
        card { c -> c.addView(text("Совместимость", 17f, true)); gap(c, 8); c.addView(text("Android может запрещать программное подключение A2DP. При отказе Seamless покажет причину и остановит автоматику. Первую передачу проверь со своей музыкой.", 13f, secondary = true)) }
        detail = text("", 12f, secondary = true); content.addView(detail)
    }
    private fun settings() {
        card { c ->
            c.addView(text("Оформление", 17f, true)); gap(c, 14)
            listOf("system" to "◐  Как в системе", "light" to "☀  Светлая", "dark" to "☾  Тёмная").forEach { (id, title) ->
                c.addView(button((if (appearance.getString("theme", "system") == id) "✓  " else "") + title) { appearance.edit().putString("theme", id).apply(); recreate() })
            }; gap(c, 20); c.addView(text("Акцент", 13f, true))
            listOf("mint" to "Мята", "cobalt" to "Кобальт", "iris" to "Ирис").forEach { (id, title) -> c.addView(button((if (appearance.getString("accent", "mint") == id) "✓  " else "") + title) { appearance.edit().putString("accent", id).apply(); recreate() }) }
        }
        card { c -> toggle(c, "Восстанавливать связь", "Повторять поиск Mac после временного обрыва. Остановить можно в уведомлении.", service?.reconnect ?: settingsPrefs.getBoolean("reconnect", true)) { value -> service?.let { it.reconnect = value } ?: settingsPrefs.edit().putBoolean("reconnect", value).apply() } }
        card { c ->
            c.addView(text("История Android", 17f, true)); gap(c, 6); c.addView(text("События этого телефона. Ответы компьютера отмечены [Mac].", 12f, secondary = true)); gap(c, 12); logView = text("Событий пока нет", 11f, secondary = true).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }; c.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, dp(200)))
            c.addView(button("Скопировать журнал") { copyDiagnostics() }); c.addView(button("Очистить историю") { service?.clearLogs(); refresh() })
        }
        card { c ->
            toggle(c, "Технические логи", "Локальный журнал BLE, очереди, адресов и этапов передачи. Ключ и QR не записываются.", service?.debugEnabled ?: settingsPrefs.getBoolean("debug", false)) {
                service?.let { s -> s.debugEnabled = it } ?: settingsPrefs.edit().putBoolean("debug", it).apply(); refresh()
            }; gap(c, 12)
            debugView = text("", 11f, secondary = true).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
            c.addView(ScrollView(this).apply { addView(debugView) }, LinearLayout.LayoutParams(-1, dp(220)))
            c.addView(text("Включи на обоих устройствах, повтори одну передачу и скопируй диагностику с каждого. Последние 1000 записей хранятся до остановки приложения.", 12f, secondary = true))
        }
        card { c -> c.addView(text("Приватность по умолчанию", 17f, true)); gap(c, 8); c.addView(text("Без аккаунта и интернета. Ключ хранится в Android Keystore. Звук не записывается, содержимое уведомлений не читается. По BLE передаются только команды и состояния воспроизведения.", 13f, secondary = true)) }
    }
    private fun copyDiagnostics() {
        val report = service?.diagnostics() ?: "Seamless Headphones 0.3.1 · Android ${Build.VERSION.RELEASE}\n${Build.MANUFACTURER} ${Build.MODEL}\nСервис ещё не запущен. Разрешение Bluetooth: ${bluetoothGranted()}"
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Seamless Headphones", report))
        toast("Диагностика скопирована — вставь её в сообщение")
    }
    private fun refresh() {
        val s = service
        status?.text = s?.status ?: "Нужно разрешение Bluetooth"
        detail?.text = s?.detail ?: "Начни с настройки устройств."
        autoStatus?.text = s?.let { "${it.autoReason}\n\nМузыка: ${it.observedSources}\nЗащита: ${it.local.guardReason ?: "ожидаем проверку"}" } ?: "Свяжи телефон с Mac"
        mediaPermission?.text = if (s?.mediaAvailable == true) "✓  Медиасессии доступны" else "1. Нужен доступ к медиасессиям"
        callPermission?.text = if (s?.callKnown == true) "✓  Защита вызовов доступна" else "2. Нужен доступ к состоянию вызовов"
        connect?.text = if (s?.running == true) "Остановить связь" else "Найти Mac"
        val enabled = s?.trusted == true && !s.busy && !s.held && s.address.isNotBlank()
        toMac?.isEnabled = enabled; toPhone?.isEnabled = enabled; toMac?.alpha = if (enabled) 1f else .45f; toPhone?.alpha = if (enabled) 1f else .45f
        picker?.isEnabled = s?.busy != true
        resumeButton?.isEnabled = s?.trusted == true && !s.busy
        selectionView?.text = s?.selectionSummary ?: "Разреши Bluetooth, чтобы выбрать наушники"
        selectionView?.setTextColor(if (s?.selectionMismatch == true) Color.rgb(200, 110, 50) else muted)
        matchButton?.visibility = if (s?.selectionMismatch == true) View.VISIBLE else View.GONE
        matchButton?.isEnabled = s?.busy != true
        debugView?.text = if (s?.debugEnabled == true) s.debugEvents.take(40).joinToString("\n\n").ifBlank { "Ожидаем события…" } else if (settingsPrefs.getBoolean("debug", false)) "Логи включены. Разреши Bluetooth, чтобы запустить сервис" else "Технический журнал выключен"
        if (s != null && holdSwitch?.isChecked != s.held) holdSwitch?.isChecked = s.held
        ownerText?.text = when { s?.busy == true -> "ПЕРЕДАЁМ НАУШНИКИ…"; s?.owner == "mac" -> "СЕЙЧАС НА MAC"; s?.owner == "android" -> "СЕЙЧАС НА ANDROID"; else -> "ЛОКАЛЬНО · MAC + ANDROID" }
        metrics?.text = "${s?.successful ?: 0} передач   ·   Последняя: ${s?.lastDuration ?: "—"}   ·   BLE"
        logView?.text = s?.events?.take(16)?.joinToString("\n\n")?.ifBlank { "Событий пока нет" } ?: "Событий пока нет"
    }
    private fun bluetoothGranted() = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun withBluetooth(action: () -> Unit) {
        val required = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { if (!bound) bind(); action() } else { pendingAction = action; requestPermissions(missing.toTypedArray(), 1) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) { val action = pendingAction; pendingAction = null; if (bluetoothGranted()) { if (!bound) bind(); action?.invoke() } else toast("Разреши устройства поблизости в настройках приложения") }
        refresh()
    }
    private fun bind() { if (!bound) bound = bindService(Intent(this, ResponseService::class.java), connection, BIND_AUTO_CREATE) }
    private fun refreshDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { picker?.visibility = View.GONE; return }
        picker?.visibility = View.VISIBLE
        devices = runCatching { service?.paired().orEmpty() }.getOrDefault(emptyList())
        val names = devices.map { try { "${it.name ?: "Наушники"}\n${it.address}" } catch (_: SecurityException) { "Нет разрешения" } }
        picker?.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, listOf("Выбрать наушники") + names) {
            private fun label(position: Int, dropdown: Boolean) = text(getItem(position).orEmpty(), 13f).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12)); minHeight = dp(54)
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                if (dropdown) setBackgroundColor(surface)
                layoutParams = AbsListView.LayoutParams(-1, -2)
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = label(position, false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = label(position, true)
        }
        picker?.setSelection(devices.indexOfFirst { it.address == service?.address }.let { if (it >= 0) it + 1 else 0 })
    }
    private fun selectedName(): String = try { service?.paired()?.firstOrNull { it.address == service?.address }?.name ?: "Твои наушники" } catch (_: SecurityException) { "Твои наушники" }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { service?.changed = null; if (bound) unbindService(connection); super.onDestroy() }
    private fun blend(a: Int, b: Int, f: Float) = Color.rgb((Color.red(a)*(1-f)+Color.red(b)*f).toInt(), (Color.green(a)*(1-f)+Color.green(b)*f).toInt(), (Color.blue(a)*(1-f)+Color.blue(b)*f).toInt())
}

private class HeadphonesArt(context: Context, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val scale = width / 120f; canvas.save(); canvas.scale(scale, scale)
        paint.style = Paint.Style.FILL; paint.color = (accent and 0x00FFFFFF) or 0x16000000
        canvas.drawCircle(60f, 60f, 53f, paint)
        val icon = context.getDrawable(R.drawable.icon_foreground)!!
        icon.setTint(accent); icon.setBounds(6, 6, 114, 114); icon.draw(canvas)
        canvas.restore()
    }
}

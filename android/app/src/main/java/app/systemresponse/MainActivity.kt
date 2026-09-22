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
    private var resumeExplanation: TextView? = null
    private var manualExplanation: TextView? = null
    private var modeStatus: TextView? = null
    private var controlFeedback: TextView? = null
    private var followButton: Button? = null
    private var idleButton: Button? = null
    private var mediaPermissionButton: Button? = null
    private var callPermissionButton: Button? = null
    private var holdExplanation: TextView? = null
    private var clearHistoryButton: Button? = null
    private var syncingControls = false
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
        android.app.AlertDialog.Builder(this).setTitle(L.text(this, "Связать с этим Mac?")).setMessage(L.text(this, message))
            .setNegativeButton(L.text(this, "Отмена"), null).setPositiveButton(L.text(this, "Сохранить")) { _, _ ->
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
    private fun text(value: String, size: Float = 14f, bold: Boolean = false, secondary: Boolean = false, localize: Boolean = true) = TextView(this).apply {
        text = if (localize) L.text(this@MainActivity, value) else value; textSize = size; setTextColor(if (secondary) muted else ink)
        if (!localize) tag = "user-content"
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun shape(color: Int, radius: Int = 20, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun controlColors(on: Int = accent, off: Int = muted) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(tint(muted, 95), on, off))
    private fun availability(view: View?, enabled: Boolean) { view?.isEnabled = enabled; view?.alpha = if (enabled) 1f else .45f }
    private fun stack(padding: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
    private fun gap(parent: LinearLayout, value: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(value))) }
    private fun card(parent: LinearLayout = content, action: (LinearLayout) -> Unit): LinearLayout {
        val view = stack(20).apply { background = shape(surface, 22, tint(ink, 15)) }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }); action(view); return view
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = L.text(this@MainActivity, label); isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0; minHeight = dp(48); minimumHeight = dp(48)
        setTextColor(if (primary) if (dark) Color.parseColor("#102921") else Color.WHITE else accent)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tint(if (primary) ink else accent, 45)), shape(if (primary) accent else tint(accent, 22), 13), null)
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
        val control = Switch(this).apply {
            isChecked = value; contentDescription = title; minWidth = dp(48); minHeight = dp(48)
            thumbTintList = controlColors(); trackTintList = controlColors(tint(accent, 105), tint(muted, 65))
            setOnCheckedChangeListener { _, on -> if (!syncingControls) action(on) }
        }
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
    override fun onResume() { super.onResume(); if (bluetoothGranted()) bind(); if (::content.isInitialized) refresh() }
    private fun rerenderKeepingScroll() { val offset = scroll.scrollY; render(); scroll.post { scroll.scrollTo(0, offset) } }
    private fun render() {
        content.removeAllViews(); navigation.removeAllViews()
        status = null; detail = null; autoStatus = null; mediaPermission = null; callPermission = null; logView = null; debugView = null; selectionView = null; matchButton = null; resumeButton = null; metrics = null; ownerText = null
        connect = null; toMac = null; toPhone = null; picker = null; holdSwitch = null
        resumeExplanation = null; manualExplanation = null; modeStatus = null; controlFeedback = null
        followButton = null; idleButton = null; mediaPermissionButton = null; callPermissionButton = null; holdExplanation = null; clearHistoryButton = null
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
        gap(content, 8); content.addView(text("0.5.0  ·  БЕЗ ОБЛАКА  ·  БЕЗ ТЕЛЕМЕТРИИ", 10f, secondary = true).apply { letterSpacing = .10f }); refresh()
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
            val labels = stack(); labels.addView(text(selectedName(), 23f, true, localize = false)); gap(labels, 8); labels.addView(text("Слушай там, где удобно.", 12f, secondary = true))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(HeadphonesArt(this, accent), LinearLayout.LayoutParams(dp(102), dp(110))); c.addView(row); gap(c, 10)
            status = text("Связь выключена", 12f, secondary = true); c.addView(status)
        }
        card { c ->
            c.addView(text("Куда передать звук", 17f, true)); gap(c, 8)
            toPhone = button("Забрать на Android", true) { service?.request("android") }; c.addView(toPhone)
            toMac = button("Передать на Mac") { service?.request("mac") }; c.addView(toMac)
            manualExplanation = text("", 13f, secondary = true); gap(c, 12); c.addView(manualExplanation)
            detail = text("", 13f, secondary = true); gap(c, 8); c.addView(detail)
        }
        card { c ->
            c.addView(text("✦  Автопереключение", 17f, true)); gap(c, 8); autoStatus = text("Ожидаем подключение", 13f, secondary = true); c.addView(autoStatus)
            c.addView(button("Настроить автоматизацию") { page = 1; render() })
        }
        card { c ->
            holdSwitch = toggle(c, "Запретить переключения", "Блокирует автоматические и ручные передачи. Само по себе не подключает наушники к телефону.", s?.held == true) { service?.hold(it) }
            holdExplanation = text("", 12f, secondary = true); gap(c, 10); c.addView(holdExplanation)
        }
        metrics = text("", 12f, secondary = true); content.addView(metrics); gap(content, 12)
    }
    private fun automation() {
        val s = service
        card { c ->
            toggle(c, "Автопереключение", "Для работы включи его на обоих устройствах. Ручные кнопки доступны и без автоматики.", s?.autoEnabled ?: settingsPrefs.getBoolean("auto", true)) { value ->
                service?.let { it.autoEnabled = value } ?: settingsPrefs.edit().putBoolean("auto", value).apply()
                refresh()
            }; gap(c, 16)
            autoStatus = text("", 13f, secondary = true); c.addView(autoStatus)
            resumeButton = button("Снять паузу автоматики") { service?.resumeAuto(); refresh() }; c.addView(resumeButton)
            resumeExplanation = text("", 12f, secondary = true); gap(c, 8); c.addView(resumeExplanation)
            controlFeedback = text("", 13f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; gap(c, 8); c.addView(controlFeedback)
        }
        card { c ->
            c.addView(text("Два разрешения для автоматики", 17f, true)); gap(c, 15)
            mediaPermission = text("", 13f, true); c.addView(mediaPermission); gap(c, 6)
            c.addView(text("Медиасессии сообщают, когда ты нажимаешь Play/Pause. Содержимое уведомлений приложение не читает.", 12f, secondary = true))
            mediaPermissionButton = button("Разрешить медиасессии") { openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }; c.addView(mediaPermissionButton); gap(c, 18)
            callPermission = text("", 13f, true); c.addView(callPermission); gap(c, 6)
            c.addView(text("Состояние вызова нужно, чтобы не мешать разговору. Номера, контакты и журнал звонков не считываются.", 12f, secondary = true))
            callPermissionButton = button("Разрешить защиту звонков") { requestCallPermission() }; c.addView(callPermissionButton)
        }
        card { c ->
            c.addView(text("Когда переключать", 17f, true)); gap(c, 8)
            c.addView(text("Общее правило подтверждает Mac. Это правило запуска автоматики, а не кнопка передачи звука.", 12f, secondary = true))
            modeStatus = text("", 13f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; gap(c, 12); c.addView(modeStatus)
            followButton = button("Следовать новому воспроизведению") { service?.setMode(false); refresh() }; c.addView(followButton)
            c.addView(text("Новое воспроизведение на другом устройстве может забрать наушники, даже если здесь ещё играет музыка.", 12f, secondary = true))
            idleButton = button("Только когда источник на паузе") { service?.setMode(true); refresh() }; c.addView(idleButton)
            c.addView(text("Передача ждёт, пока музыка на текущем устройстве остановится.", 12f, secondary = true))
            gap(c, 14); c.addView(text("Подключение — параллельно: принимающее устройство подключается одновременно с отключением источника. Это общий способ передачи в новой версии на обоих устройствах.", 12f, secondary = true))
            gap(c, 10); c.addView(text("После снятия паузы поставь музыку на паузу и запусти заново. Защита звонков и запрет переключений остаются активны.", 12f, secondary = true))
        }
        card { c ->
            c.addView(text("Приложения на телефоне", 17f, true)); gap(c, 8)
            c.addView(text("Только выбранные плееры могут запросить передачу. Обычные уведомления не являются медиасессиями.", 12f, secondary = true)); gap(c, 14)
            (MediaMonitor.known.keys + (s?.discovered ?: emptySet())).distinct().forEach { pkg ->
                val item = CheckBox(this).apply { text = s?.appName(pkg) ?: MediaMonitor.known[pkg] ?: pkg; tag = "user-content"; setTextColor(ink); textSize = 14f; minHeight = dp(48); buttonTintList = controlColors(); isChecked = (s?.allowed ?: settingsPrefs.getStringSet("sources", MediaMonitor.known.keys)!!).contains(pkg)
                    setOnCheckedChangeListener { _, checked ->
                        val current = service?.allowed ?: settingsPrefs.getStringSet("sources", MediaMonitor.known.keys)!!.toSet()
                        val updated = if (checked) current + pkg else current - pkg
                        service?.let { it.allowed = updated } ?: settingsPrefs.edit().putStringSet("sources", updated).apply()
                    }
                }; c.addView(item)
            }
            c.addView(button("Обновить список плееров") { rerenderKeepingScroll(); toast(if (service?.mediaAvailable == true) "Список обновлён. Новые плееры появляются после запуска музыки" else "Для поиска плееров разреши доступ к медиасессиям") }); gap(c, 15)
            toggle(c, "Быстрое обнаружение · 0,5 с", "Проверять начало музыки полсекунды. Само подключение Bluetooth занимает дополнительное время.", s?.fastDetection ?: settingsPrefs.getBoolean("fastDetection", true)) { value ->
                service?.let { it.fastDetection = value } ?: settingsPrefs.edit().putBoolean("fastDetection", value).apply()
                rerenderKeepingScroll()
            }; gap(c, 12)
            val savedDelay = s?.delay ?: settingsPrefs.getInt("delay", 2)
            val fast = s?.fastDetection ?: settingsPrefs.getBoolean("fastDetection", true)
            val delayLabel = text(if (fast) "Сейчас: 0,5 с · выключи быстрый режим для настройки" else "Проверять новый звук: $savedDelay с", 13f, true); c.addView(delayLabel)
            c.addView(SeekBar(this).apply { isEnabled = !(s?.fastDetection ?: settingsPrefs.getBoolean("fastDetection", true)); max = 3; progress = savedDelay - 2; minHeight = dp(48); contentDescription = "Задержка автопереключения"
                progressTintList = android.content.res.ColorStateList.valueOf(accent); thumbTintList = android.content.res.ColorStateList.valueOf(accent); alpha = if (fast) .45f else 1f
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar?, value: Int, user: Boolean) { delayLabel.text = L.text(this@MainActivity, "Проверять новый звук: ${value + 2} с"); if (user) { service?.let { it.delay = value + 2 } ?: settingsPrefs.edit().putInt("delay", value + 2).apply() } }
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
            c.addView(button(if (bluetoothGranted()) "Обновить список наушников" else "Разрешить Bluetooth") {
                withBluetooth {
                    if (!bound) bind() else {
                        refreshDevices()
                        toast(if (devices.isEmpty()) "Сопряжённых наушников пока нет. Добавь их в настройках Bluetooth" else "Список обновлён: ${devices.size}")
                    }
                }
            })
            c.addView(button("Настройки Bluetooth") { openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
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
            val key = EditText(this).apply { hint = "Ключ связи с Mac"; setSingleLine(); textSize = 14f; setTextColor(ink); setHintTextColor(muted); backgroundTintList = android.content.res.ColorStateList.valueOf(accent); minHeight = dp(52); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO; isSaveEnabled = false }
            c.addView(key)
            c.addView(button("Сохранить ключ") {
                if (service?.running == true) { toast("Сначала останови связь"); return@button }
                try { SecretStore(this).save(key.text.toString()); key.text.clear(); toast("Ключ сохранён в защищённом хранилище") } catch (_: Exception) { toast("Проверь 44 символа ключа Base64 с Mac") }
            }); gap(c, 14)
            status = text("", 13f, true); c.addView(status)
            connect = button("Найти Mac", true) {
                if (service?.running == true) service?.stopLink()
                else if (runCatching { SecretStore(this).load() }.getOrNull() == null) toast("Сначала сканируй QR-код или сохрани ключ своего Mac")
                else withBluetooth { startForegroundService(Intent(this, ResponseService::class.java)) }
            }; c.addView(connect)
            c.addView(button("Забыть Mac") {
                android.app.AlertDialog.Builder(this).setTitle(L.text(this, "Удалить ключ Mac?")).setMessage(L.text(this, "Для следующего подключения потребуется снова добавить ключ."))
                    .setNegativeButton(L.text(this, "Отмена"), null).setPositiveButton(L.text(this, "Удалить")) { _, _ -> service?.stopLink(); SecretStore(this).clear(); toast("Ключ удалён"); refresh() }.show()
            })
        }
        card { c -> c.addView(text("Совместимость", 17f, true)); gap(c, 8); c.addView(text("Android может запрещать программное подключение A2DP. При отказе Seamless покажет причину и остановит автоматику. Первую передачу проверь со своей музыкой.", 13f, secondary = true)) }
        detail = text("", 12f, secondary = true); content.addView(detail)
    }
    private fun settings() {
        card { c ->
            c.addView(text("Язык", 17f, true)); gap(c, 8)
            c.addView(text("Язык интерфейса и уведомлений. Настройки связи сохраняются.", 12f, secondary = true))
            listOf("system" to "Как в системе", "ru" to "Русский", "en" to "English").forEach { (id, title) ->
                val selected = appearance.getString("language", "system") == id
                c.addView(button((if (selected) "✓  " else "") + title, selected) {
                    if (!selected) {
                        appearance.edit().putString("language", id).apply()
                        service?.refreshNotificationLanguage()
                        recreate()
                    }
                }.apply { isSelected = selected; contentDescription = "language-$id" })
            }
        }
        card { c ->
            c.addView(text("Оформление", 17f, true)); gap(c, 14)
            c.addView(text(if (appearance.getString("theme", "system") == "system") "Системная тема · сейчас ${if (dark) "тёмная" else "светлая"}" else "Выбранная тема действует независимо от настроек телефона.", 12f, secondary = true))
            listOf("system" to "◐  Как в системе", "light" to "☀  Светлая", "dark" to "☾  Тёмная").forEach { (id, title) ->
                val selected = appearance.getString("theme", "system") == id
                c.addView(button((if (selected) "✓  " else "") + title, selected) { if (!selected) { appearance.edit().putString("theme", id).apply(); recreate() } }.apply { isSelected = selected })
            }; gap(c, 20); c.addView(text("Акцент", 13f, true))
            listOf("mint" to "Мята", "cobalt" to "Кобальт", "iris" to "Ирис").forEach { (id, title) ->
                val selected = appearance.getString("accent", "mint") == id
                c.addView(button((if (selected) "✓  " else "") + title, selected) { if (!selected) { appearance.edit().putString("accent", id).apply(); recreate() } }.apply { isSelected = selected })
            }
        }
        card { c -> toggle(c, "Восстанавливать связь", "Повторять поиск Mac после временного обрыва. Остановить можно в уведомлении.", service?.reconnect ?: settingsPrefs.getBoolean("reconnect", true)) { value -> service?.let { it.reconnect = value } ?: settingsPrefs.edit().putBoolean("reconnect", value).apply() } }
        card { c ->
            c.addView(text("История Android", 17f, true)); gap(c, 6); c.addView(text("События этого телефона. Ответы компьютера отмечены [Mac].", 12f, secondary = true)); gap(c, 12); logView = text("Событий пока нет", 11f, secondary = true).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }; c.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, dp(200)))
            c.addView(button("Скопировать журнал") { copyDiagnostics() })
            clearHistoryButton = button("Очистить историю") { service?.clearLogs(); refresh(); toast("История и технический журнал очищены") }; c.addView(clearHistoryButton)
        }
        card { c ->
            toggle(c, "Технические логи", "Локальный журнал BLE, очереди, адресов и этапов передачи. Ключ и QR не записываются.", service?.debugEnabled ?: settingsPrefs.getBoolean("debug", false)) {
                service?.let { s -> s.debugEnabled = it } ?: settingsPrefs.edit().putBoolean("debug", it).apply(); refresh()
            }; gap(c, 12)
            debugView = text("", 11f, secondary = true).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); tag = "raw-log" }
            c.addView(ScrollView(this).apply { addView(debugView) }, LinearLayout.LayoutParams(-1, dp(220)))
            c.addView(text("Включи на обоих устройствах, повтори одну передачу и скопируй диагностику с каждого. Последние 1000 записей хранятся до остановки приложения.", 12f, secondary = true))
        }
        card { c -> c.addView(text("Приватность по умолчанию", 17f, true)); gap(c, 8); c.addView(text("Без аккаунта и интернета. Ключ хранится в Android Keystore. Звук не записывается, содержимое уведомлений не читается. По BLE передаются только команды и состояния воспроизведения.", 13f, secondary = true)) }
    }
    private fun copyDiagnostics() {
        val report = service?.diagnostics() ?: "Seamless Headphones 0.5.0 · Android ${Build.VERSION.RELEASE}\n${Build.MANUFACTURER} ${Build.MODEL}\nСервис ещё не запущен. Разрешение Bluetooth: ${bluetoothGranted()}"
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Seamless Headphones", L.text(this, report)))
        toast("Диагностика скопирована — вставь её в сообщение")
    }
    private fun refresh() {
        val s = service
        status?.text = s?.status ?: if (bluetoothGranted()) "Запускаем сервис…" else "Нужно разрешение Bluetooth"
        detail?.text = s?.detail ?: "Начни с настройки устройств."
        autoStatus?.text = s?.let { "${it.autoReason}\n\nМузыка: ${it.observedSources}\nЗащита: ${it.local.guardReason ?: "ожидаем проверку"}" }
            ?: if (settingsPrefs.getBoolean("auto", true)) "Автоматика включена. Для работы разреши Bluetooth и свяжи телефон с Mac." else "Автопереключение выключено. Ручные передачи остаются доступны после подключения Mac."
        mediaPermission?.text = if (s?.mediaAvailable == true) "✓  Медиасессии доступны" else "1. Нужен доступ к медиасессиям"
        callPermission?.text = if (s?.callKnown == true) "✓  Защита вызовов доступна" else "2. Нужен доступ к состоянию вызовов"
        mediaPermissionButton?.text = if (s?.mediaAvailable == true) "Настройки доступа к медиасессиям" else "Разрешить медиасессии"
        callPermissionButton?.text = if (s?.callKnown == true) "Настройки разрешений приложения" else "Разрешить защиту звонков"
        connect?.text = if (s?.running == true) "Остановить связь" else "Найти Mac"
        val manualBlock = s?.manualBlockReason ?: if (s == null) "Разреши Bluetooth и свяжи телефон с Mac в разделе «Устройства»." else null
        availability(toMac, manualBlock == null); availability(toPhone, manualBlock == null)
        manualExplanation?.text = manualBlock ?: "Передача занимает несколько секунд. Подключение получателя и отключение источника идут параллельно."
        availability(picker, s != null && !s.busy)
        val resumeBlock = s?.resumeBlockReason ?: if (s == null) "Сначала свяжи телефон с Mac." else null
        availability(resumeButton, resumeBlock == null && s?.resumePending != true)
        resumeButton?.text = if (s?.resumePending == true) "Ждём подтверждение Mac…" else "Снять паузу автоматики"
        resumeExplanation?.text = resumeBlock ?: "Сбрасывает ожидание после передачи или ошибки. Затем запусти музыку заново. Не включает выключенную автоматику."
        controlFeedback?.text = s?.controlMessage.orEmpty()
        controlFeedback?.visibility = if (s?.controlMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        val modeBlock = s?.modeBlockReason ?: if (s == null) "Для выбора правила свяжи телефон с Mac." else null
        val mode = s?.policyMode
        val pending = s?.policyPending == true
        followButton?.text = (if (mode == "follow") "✓  " else "") + "Следовать новому воспроизведению"
        idleButton?.text = (if (mode == "idle") "✓  " else "") + "Только когда источник на паузе"
        followButton?.isSelected = mode == "follow"; idleButton?.isSelected = mode == "idle"
        availability(followButton, modeBlock == null && !pending && mode != "follow")
        availability(idleButton, modeBlock == null && !pending && mode != "idle")
        modeStatus?.text = listOfNotNull(
            when (mode) { "follow" -> "На Mac выбрано: следовать новому воспроизведению"; "idle" -> "На Mac выбрано: только когда источник на паузе"; else -> "Правило Mac ещё не получено" },
            if (pending) "Ждём подтверждение изменения от Mac…" else modeBlock,
            s?.controlMessage?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")
        selectionView?.text = s?.selectionSummary ?: "Разреши Bluetooth, чтобы выбрать наушники"
        selectionView?.setTextColor(if (s?.selectionMismatch == true) Color.rgb(200, 110, 50) else muted)
        matchButton?.visibility = if (s?.selectionMismatch == true) View.VISIBLE else View.GONE
        matchButton?.isEnabled = s?.busy != true
        debugView?.text = if (s?.debugEnabled == true) s.debugEvents.take(40).joinToString("\n\n").ifBlank { L.text(this, "Ожидаем события…") } else L.text(this, if (settingsPrefs.getBoolean("debug", false)) "Логи включены. Разреши Bluetooth, чтобы запустить сервис" else "Технический журнал выключен")
        availability(holdSwitch, s != null)
        if (s != null && holdSwitch?.isChecked != s.held) { syncingControls = true; holdSwitch?.isChecked = s.held; syncingControls = false }
        holdExplanation?.text = when { s == null -> "Разреши Bluetooth, чтобы управлять переключениями."; s.held -> "Переключения запрещены на телефоне. Выключи этот запрет для ручной передачи или автоматики."; s.peer.held -> "Переключения запрещены на Mac. Сними запрет на компьютере."; else -> "Переключения разрешены. Защита звонков остаётся активна." }
        ownerText?.text = when { s?.busy == true -> "ПЕРЕДАЁМ НАУШНИКИ…"; s?.owner == "mac" -> "ПЕРЕДАНО НА MAC"; s?.owner == "android" -> "ПЕРЕДАНО НА ANDROID"; else -> "ВЫБЕРИ, ГДЕ СЛУШАТЬ" }
        metrics?.text = "${s?.successful ?: 0} передач   ·   Последняя: ${s?.lastDuration ?: "—"}   ·   BLE"
        logView?.text = s?.events?.take(16)?.joinToString("\n\n")?.ifBlank { "Событий пока нет" } ?: "Событий пока нет"
        availability(clearHistoryButton, s != null && (s.events.isNotEmpty() || s.debugEvents.isNotEmpty()))
        L.apply(content); L.apply(navigation)
    }
    private fun openSettings(intent: Intent) { try { startActivity(intent) } catch (_: ActivityNotFoundException) { toast("Этот раздел недоступен. Открой настройки приложения вручную") } }
    private fun appSettings() = openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    private fun requestCallPermission() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED || service?.callKnown == true) { appSettings(); return }
        if (settingsPrefs.getBoolean("askedCalls", false) && !shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)) {
            toast("Разреши доступ к состоянию телефона в настройках приложения"); appSettings(); return
        }
        settingsPrefs.edit().putBoolean("askedCalls", true).apply()
        requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 2)
    }
    private fun bluetoothGranted() = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun withBluetooth(action: () -> Unit) {
        val required = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val blockedBluetooth = missing.filter { it == Manifest.permission.BLUETOOTH_SCAN || it == Manifest.permission.BLUETOOTH_CONNECT }
        if (settingsPrefs.getBoolean("askedBluetooth", false) && blockedBluetooth.any { !shouldShowRequestPermissionRationale(it) }) {
            toast("Разреши «Устройства поблизости» в настройках приложения"); appSettings(); return
        }
        if (missing.isEmpty()) { if (!bound) bind(); action() } else {
            if (blockedBluetooth.isNotEmpty()) settingsPrefs.edit().putBoolean("askedBluetooth", true).apply()
            pendingAction = action; requestPermissions(missing.toTypedArray(), 1)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) { val action = pendingAction; pendingAction = null; if (bluetoothGranted()) { if (!bound) bind(); action?.invoke() } else toast("Разреши устройства поблизости в настройках приложения") }
        if (requestCode == 2 && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) toast("Автоматика ждёт разрешение на защиту звонков. Ручные передачи можно проверить отдельно")
        refresh()
    }
    private fun bind() { if (!bound) bound = bindService(Intent(this, ResponseService::class.java), connection, BIND_AUTO_CREATE) }
    private fun refreshDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { picker?.visibility = View.GONE; return }
        picker?.visibility = View.VISIBLE
        devices = runCatching { service?.paired().orEmpty() }.getOrDefault(emptyList())
        val names = devices.map { try { "${it.name ?: L.text(this, "Наушники")}\n${it.address}" } catch (_: SecurityException) { L.text(this, "Нет разрешения") } }
        picker?.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, listOf("Выбрать наушники") + names) {
            private fun label(position: Int, dropdown: Boolean) = text(getItem(position).orEmpty(), 13f, localize = position == 0).apply {
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
    private fun selectedName(): String = try { service?.paired()?.firstOrNull { it.address == service?.address }?.name ?: L.text(this, "Твои наушники") } catch (_: SecurityException) { L.text(this, "Твои наушники") }
    private fun toast(message: String) { Toast.makeText(this, L.text(this, message), Toast.LENGTH_LONG).show() }
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

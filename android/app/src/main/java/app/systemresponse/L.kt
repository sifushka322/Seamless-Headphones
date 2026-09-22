package app.systemresponse

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject

/** Localizes presentation only. Wire values, addresses and unknown user content stay unchanged. */
object L {
    fun english(context: Context): Boolean = when (context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "system")) {
        "ru" -> false
        "en" -> true
        else -> Resources.getSystem().configuration.locales[0].language != "ru"
    }

    private data class Template(val source: String, val pattern: Regex, val replacement: String, val captures: List<String>)
    private var dictionary: Map<String, String>? = null
    private var templates = emptyList<Template>()
    private val placeholder = Regex("\\{(\\d+)\\}")
    private val historyPrefix = Regex("^(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)? \\[(?:Mac|Android)\\]\\s+)(.*)$")
    private val sourceEntry = Regex("(.+? \\[[^\\]]+\\] · )(разрешён|не выбран|служебный процесс · не запускает передачи)(?=, |$)")
    private val translated = object : LinkedHashMap<String, String>(1024, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1024
    }
    private val opaqueSources = setOf(
        "Новое воспроизведение: {0}", "Выход: {0}", "Разрешены: {0}",
        "После передачи наушники отключились или был выбран другой выход. Сейчас на Mac: {0}.",
        "Сохранить ключ Mac и выбрать {0}?",
        "{0} [{1}] · разрешён", "{0} [{1}] · не выбран", "{0} [{1}] · служебный процесс · не запускает передачи",
        "Mac: {0} [{1}]", "Android: {0} [{1}]",
        "Mac: {0} [{1}]\nAndroid: {2} [{3}]", "Android: {0} [{1}]\nMac: {2} [{3}]"
    )

    @Synchronized private fun load(context: Context) {
        if (dictionary != null) return
        val entries = linkedMapOf<String, String>()
        for (file in listOf("backend-en.json", "ui-en.json")) {
            val json = JSONObject(context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() })
            json.keys().forEach { key -> entries[key] = json.getString(key) }
        }
        templates = entries.filterKeys { placeholder.containsMatchIn(it) }.map { (key, value) ->
            val matches = placeholder.findAll(key).toList()
            var offset = 0
            val pattern = buildString {
                append("^")
                matches.forEach { match ->
                    append(Regex.escape(key.substring(offset, match.range.first))); append("(.*?)")
                    offset = match.range.last + 1
                }
                append(Regex.escape(key.substring(offset))); append("$")
            }
            Template(key, Regex(pattern, RegexOption.DOT_MATCHES_ALL), value, matches.map { it.value })
        }.sortedByDescending { placeholder.replace(it.source, "").length }
        dictionary = entries
    }

    @Synchronized fun text(context: Context, value: String): String {
        if (!english(context) || value.isEmpty()) return value
        load(context)
        translated[value]?.let { return it }
        return translate(value, 0).also { if (value.length <= 4096) translated[value] = it }
    }

    private fun translate(value: String, depth: Int): String {
        if (depth > 5) return value
        dictionary?.get(value)?.let { return it }
        if (value.startsWith("✓  ")) return "✓  " + translate(value.removePrefix("✓  "), depth + 1)
        historyPrefix.matchEntire(value)?.let { return it.groupValues[1] + translate(it.groupValues[2], depth + 1) }
        templates.forEach { template ->
            if (template.source.startsWith("{0} [{1}] · ")) return@forEach
            if (template.source == "{0} с" && !value.matches(Regex("[\\d.,]+ с"))) return@forEach
            if (value.contains('\n') && !template.source.contains('\n')) return@forEach
            val match = template.pattern.matchEntire(value) ?: return@forEach
            val captures = template.captures.mapIndexed { index, key ->
                val captured = match.groupValues[index + 1]
                val summary = template.source in opaqueSources && (template.source.startsWith("Mac: ") || template.source.startsWith("Android: "))
                val address = if (summary && index % 2 == 0) match.groupValues[index + 2] else captured
                val missing = summary && !address.matches(Regex("(?i)[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}"))
                val fallback = captured in setOf("Не выбраны", "не выбраны", "ожидаем сведения", "неизвестно", "Нет разрешения Bluetooth")
                key to if (template.source in opaqueSources && !(missing && fallback)) captured else translate(captured, depth + 1)
            }.toMap()
            return placeholder.replace(template.replacement) { captures[it.value] ?: it.value }
        }
        if (!value.contains('\n') && sourceEntry.containsMatchIn(value)) {
            return sourceEntry.replace(value) { match ->
                match.groupValues[1] + when (match.groupValues[2]) {
                    "разрешён" -> "allowed"
                    "не выбран" -> "not selected"
                    else -> "background process · cannot trigger transfers"
                }
            }
        }
        if (value.contains('\n')) return value.split('\n').joinToString("\n") { translate(it, depth + 1) }
        return value
    }

    /** Covers refresh-time status updates as well as labels created during a page render. */
    fun apply(view: View) {
        if (!english(view.context)) return
        if (view is TextView && view !is EditText && view.tag != "user-content" && view.tag != "raw-log") {
            val value = text(view.context, view.text.toString())
            if (value != view.text.toString()) view.text = value
        }
        if (view is EditText) view.hint = text(view.context, view.hint?.toString().orEmpty())
        view.contentDescription?.let { view.contentDescription = text(view.context, it.toString()) }
        if (view is ViewGroup) for (index in 0 until view.childCount) apply(view.getChildAt(index))
    }
}

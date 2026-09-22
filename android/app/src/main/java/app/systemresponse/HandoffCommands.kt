package app.systemresponse

/** One local adapter operation per transaction. Only a known terminal early failure allows retry. */
class HandoffCommands(private val target: String) {
    var stage = "prepared"; private set
    fun accept(command: String): Boolean {
        val acquire = command == "acquire" || command == "tryAcquire"
        if (command !in setOf("release", "acquire", "tryAcquire") || acquire != (target == "android")) return false
        if (stage != "prepared" && !(stage == "retryable" && command == "acquire")) return false
        stage = "working"; return true
    }
    fun result(command: String, ok: Boolean, retrySafe: Boolean): String {
        check(stage == "working")
        return if (ok) {
            stage = "awaiting-completion"
            when (command) { "release" -> "released"; "tryAcquire" -> "earlyResult"; else -> "result" }
        } else if (command == "tryAcquire" && retrySafe) {
            stage = "retryable"; "retryable"
        } else { stage = "failed"; "error" }
    }
    fun canComplete(): Boolean = stage == "awaiting-completion" || (target == "mac" && stage == "prepared")
}

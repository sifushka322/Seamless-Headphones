package app.systemresponse

/** A button is pending until its own acknowledgement arrives, never an unrelated snapshot. */
class ControlRequest {
    var id: String? = null; private set
    var kind: String? = null; private set
    private var sentAt = 0L
    fun begin(id: String, kind: String, now: Long): Boolean {
        if (this.id != null) return false
        this.id = id; this.kind = kind; sentAt = now; return true
    }
    fun accept(id: String, kind: String): Boolean {
        if (this.id == null || this.id != id || this.kind != kind) return false
        clear(); return true
    }
    fun expire(now: Long): Boolean {
        if (id == null || now - sentAt < 10_000) return false
        clear(); return true
    }
    fun clear() { id = null; kind = null }
}

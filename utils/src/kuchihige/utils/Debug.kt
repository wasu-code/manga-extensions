package kuchihige.utils

/**
 * Logs the value along with caller location, thread name, and type.
 *
 * Returns the receiver unchanged for easy chaining.
 */
fun <T> T.log(tag: String = "Kuchihige"): T {
    val stack = Throwable().stackTrace
    // stack[0] is this line, so stack[1] is the caller
    val caller = stack.getOrNull(1)

    val location = if (caller != null) {
        "${caller.fileName}:${caller.lineNumber}"
    } else {
        "unknown location"
    }

    val method = caller?.methodName ?: "unknown"
    val thread = Thread.currentThread().name
    val type = this?.let {
        it::class.qualifiedName ?: it::class.simpleName
    } ?: "null"

    val message = """
        ┌─ 𝕃𝕆𝔾
        │ $location
        │ 𝕞:  $method
        │ 𝕥:  $thread
        │
        │ 𝕋:  $type
        │────────────────────────────
        │ 𝕧:  $this
        └────────────────────────────
    """.trimIndent()

    android.util.Log.d(tag, message)
    return this
}

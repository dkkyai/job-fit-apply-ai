// Spike test file 3 for PR-Agent eval: swallowed exception, magic fallback
fun parsePort(raw: String): Int {
    return try {
        raw.toInt()
    } catch (e: Exception) {
        8080
    }
}
// re-trigger eval run

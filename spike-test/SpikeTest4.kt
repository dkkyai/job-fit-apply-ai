// Spike test file 4 for PR-Agent eval: string concatenation in a loop
fun joinNames(names: List<String>): String {
    var result = ""
    for (n in names) {
        result += n + ", "
    }
    return result
}
// re-trigger eval run

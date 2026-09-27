// Spike test file 5 for PR-Agent eval: hardcoded credential-like string
fun apiEndpoint(): String {
    val apiKey = "sk-test-1234567890abcdef"
    return "https://api.example.com/v1?key=$apiKey"
}

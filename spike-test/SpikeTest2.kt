// Spike test file 2 for PR-Agent eval: unsafe !! on nullable
fun greetUser(name: String?): String {
    val upper = name!!.uppercase()
    return "Hello, $upper!"
}

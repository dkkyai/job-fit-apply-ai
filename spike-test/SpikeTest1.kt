// Spike test file 1 for PR-Agent eval: magic numbers + unused variable
fun calculateDiscount(price: Double): Double {
    val unusedLabel = "discount"
    return if (price > 100) {
        price * 0.85
    } else if (price > 50) {
        price * 0.9
    } else {
        price
    }
}

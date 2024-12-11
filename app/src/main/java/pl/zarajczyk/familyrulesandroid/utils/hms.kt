package pl.zarajczyk.familyrulesandroid.utils

fun Long.toHMS(): String {
    val timeInSeconds = this / 1000
    val secPart = timeInSeconds % 60
    val timeInMinutes = timeInSeconds / 60
    val minPart = timeInMinutes % 60
    val hourPart = timeInMinutes / 60
    return "$hourPart:$minPart:$secPart"
}
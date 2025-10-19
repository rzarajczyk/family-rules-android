package pl.zarajczyk.familyrulesandroid.utils

import java.util.Locale

fun Long.toHMS(): String {
    val timeInSeconds = this / 1000
    val secPart = String.format(Locale.getDefault(), "%02d", timeInSeconds % 60)
    val timeInMinutes = timeInSeconds / 60
    val minPart = String.format(Locale.getDefault(), "%02d", timeInMinutes % 60)
    val hourPart = String.format(Locale.getDefault(), "%02d", timeInMinutes / 60)
    return "${hourPart}h ${minPart}m ${secPart}s"
}
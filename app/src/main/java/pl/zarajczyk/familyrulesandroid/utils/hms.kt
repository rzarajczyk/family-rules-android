package pl.zarajczyk.familyrulesandroid.utils

import java.util.Locale

fun Long.millisToHMS(): String {
    val timeInSeconds = this / 1000
    val secPart = String.format(Locale.getDefault(), "%02d", timeInSeconds % 60)
    val timeInMinutes = timeInSeconds / 60
    val minPart = String.format(Locale.getDefault(), "%02d", timeInMinutes % 60)
    val hourPart = String.format(Locale.getDefault(), "%02d", timeInMinutes / 60)
    return if (hourPart == "00") "${minPart}m ${secPart}s" else "${hourPart}h ${minPart}m ${secPart}s"
}

fun Long.secondsToHMS(): String {
    val secPart = String.format(Locale.getDefault(), "%02d", this % 60)
    val timeInMinutes = this / 60
    val minPart = String.format(Locale.getDefault(), "%02d", timeInMinutes % 60)
    val hourPart = String.format(Locale.getDefault(), "%02d", timeInMinutes / 60)
    return if (hourPart == "00") "${minPart}m ${secPart}s" else "${hourPart}h ${minPart}m ${secPart}s"
}
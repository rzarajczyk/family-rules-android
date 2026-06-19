package pl.zarajczyk.familyrulesandroid.core

object LoudSoundSession {
    @Volatile
    private var dismissRequested = false

    @Volatile
    var stopPlayback: (() -> Unit)? = null

    fun reset() {
        dismissRequested = false
        stopPlayback = null
    }

    fun requestDismiss() {
        dismissRequested = true
        stopPlayback?.invoke()
    }

    fun isDismissRequested(): Boolean = dismissRequested
}

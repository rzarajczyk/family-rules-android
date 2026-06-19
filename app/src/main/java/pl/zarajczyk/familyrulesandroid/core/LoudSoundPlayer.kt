package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.delay
import pl.zarajczyk.familyrulesandroid.utils.Logger

private const val TAG = "LoudSoundPlayer"

data class LoudSoundPlayResult(
    val playedSeconds: Int,
    val alarmVolumeRaised: Boolean,
)

class LoudSoundPlayer(private val context: Context) {

    companion object {
        const val PLAY_DURATION_SECONDS = 30
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    suspend fun play(): LoudSoundPlayResult {
        stop()
        LoudSoundSession.reset()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxAlarmVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        var volumeRaised = false
        var playedSeconds = 0
        try {
            if (previousAlarmVolume < maxAlarmVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVolume, 0)
                volumeRaised = true
            }
            startVibration()
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: error("No default alarm sound available")
            val player = MediaPlayer().apply {
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = player
            LoudSoundSession.stopPlayback = { stop() }
            Logger.i(TAG, "Playing loud alarm sound for up to $PLAY_DURATION_SECONDS seconds")
            while (playedSeconds < PLAY_DURATION_SECONDS && !LoudSoundSession.isDismissRequested()) {
                delay(1000)
                playedSeconds++
            }
            return LoudSoundPlayResult(
                playedSeconds = playedSeconds,
                alarmVolumeRaised = volumeRaised,
            )
        } finally {
            LoudSoundSession.stopPlayback = null
            stop()
            if (volumeRaised) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
            }
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        stopVibration()
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 400)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
    }
}

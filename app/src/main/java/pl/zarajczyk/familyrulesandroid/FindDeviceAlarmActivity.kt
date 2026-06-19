package pl.zarajczyk.familyrulesandroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.zarajczyk.familyrulesandroid.core.LoudSoundSession
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesColors

class FindDeviceAlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        configureShowOnLockScreen()

        onBackPressedDispatcher.addCallback(this) {
            dismissAlarm()
        }

        setContent {
            FamilyRulesAndroidTheme {
                FindDeviceAlarmScreen(onDismiss = ::dismissAlarm)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        dismissAlarm()
    }

    override fun onDestroy() {
        LoudSoundSession.requestDismiss()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun configureShowOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dismissAlarm() {
        LoudSoundSession.requestDismiss()
        finish()
    }

    companion object {
        @Volatile
        private var instance: FindDeviceAlarmActivity? = null

        fun show(context: Context) {
            context.startActivity(
                Intent(context, FindDeviceAlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }

        fun finishIfVisible() {
            instance?.runOnUiThread { instance?.finish() }
        }
    }
}

@Composable
private fun FindDeviceAlarmScreen(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FamilyRulesColors.BLOCKING_COLOR)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.icon),
            contentDescription = stringResource(R.string.family_rules_icon),
            modifier = Modifier.size(96.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.find_device_alarm_title),
            style = MaterialTheme.typography.headlineMedium,
            color = FamilyRulesColors.TEXT_COLOR,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.find_device_alarm_description),
            style = MaterialTheme.typography.bodyLarge,
            color = FamilyRulesColors.TEXT_COLOR,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = onDismiss) {
            Text(text = stringResource(R.string.find_device_alarm_dismiss))
        }
    }
}

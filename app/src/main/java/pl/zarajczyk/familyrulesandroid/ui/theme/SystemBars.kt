package pl.zarajczyk.familyrulesandroid.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun ApplySystemBars(backgroundColor: Color) {
    val view = LocalView.current
    val activity = view.context as? Activity ?: return
    val useDarkSystemBarIcons = !isSystemInDarkTheme()

    SideEffect {
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = useDarkSystemBarIcons
            isAppearanceLightNavigationBars = useDarkSystemBarIcons
        }
        activity.window.statusBarColor = backgroundColor.toArgb()
        activity.window.navigationBarColor = backgroundColor.toArgb()
    }
}

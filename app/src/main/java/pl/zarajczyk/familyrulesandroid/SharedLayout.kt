package pl.zarajczyk.familyrulesandroid

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

/**
 * Shared layout component that provides the common structure for both MainActivity and InitialSetupActivity.
 * Features:
 * - Same background color
 * - Same logo at the top (left in landscape)
 * - Same column layout in landscape
 */
@Composable
fun SharedAppLayout(
    deviceState: pl.zarajczyk.familyrulesandroid.adapter.DeviceState = pl.zarajczyk.familyrulesandroid.adapter.DeviceState.ACTIVE,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) { innerPadding ->
        val bgColor = when (deviceState) {
            pl.zarajczyk.familyrulesandroid.adapter.DeviceState.ACTIVE -> Color(0xFFEEEEEE)
            pl.zarajczyk.familyrulesandroid.adapter.DeviceState.BLOCK_LIMITTED_APPS -> Color(0xFFFFDEDE)
        }
        if (isLandscape) {
            // Horizontal layout for landscape orientation
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(innerPadding)
            ) {
                // Left side - Icon and label
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.icon),
                        contentDescription = stringResource(R.string.family_rules_icon),
                        modifier = Modifier.size(128.dp)
                    )
                    Text(
                        text = stringResource(R.string.family_rules),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.Black,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                
                // Right side - Content
                Column(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxSize(),
                    content = content
                )
            }
        } else {
            // Vertical layout for portrait orientation
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(innerPadding)
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.icon),
                            contentDescription = stringResource(R.string.family_rules_icon),
                            modifier = Modifier
                                .size(128.dp)
                                .padding(top = 32.dp)
                        )
                        Text(
                            text = stringResource(R.string.family_rules),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.Black,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        content()
                    }
                }
            }
        }
    }
}

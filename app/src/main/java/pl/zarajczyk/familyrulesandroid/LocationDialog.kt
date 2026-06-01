package pl.zarajczyk.familyrulesandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.zarajczyk.familyrulesandroid.core.FamilyRulesCoreService
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocationDialog(
    service: FamilyRulesCoreService,
    onDismiss: () -> Unit
) {
    val locationTracker = service.getLocationTracker()
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var lastUpdated by remember { mutableLongStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }
    var noData by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        noData = false

        val cached = locationTracker.getLastCachedLocation()
        if (cached != null) {
            latitude = cached.first
            longitude = cached.second
            lastUpdated = locationTracker.getLastCachedTimestamp()
            isLoading = false
        } else {
            val fresh = locationTracker.getCurrentLocation()
            if (fresh != null) {
                latitude = fresh.first
                longitude = fresh.second
                lastUpdated = locationTracker.getLastCachedTimestamp()
                isLoading = false
            } else {
                noData = true
                isLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.location_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    isLoading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(modifier = Modifier.height(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.location_fetching),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FamilyRulesColors.TEXT_COLOR
                            )
                        }
                    }
                    noData -> {
                        Text(
                            text = stringResource(R.string.location_no_data),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FamilyRulesColors.TEXT_COLOR
                        )
                    }
                    else -> {
                        Text(
                            text = stringResource(
                                R.string.location_coordinates,
                                latitude!!,
                                longitude!!
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FamilyRulesColors.TEXT_COLOR
                        )
                        if (lastUpdated > 0L) {
                            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            val dateStr = formatter.format(Date(lastUpdated))
                            Text(
                                text = stringResource(R.string.location_last_updated, dateStr),
                                style = MaterialTheme.typography.bodySmall,
                                color = FamilyRulesColors.TEXT_COLOR,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

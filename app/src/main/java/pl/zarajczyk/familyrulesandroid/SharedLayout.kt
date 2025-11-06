package pl.zarajczyk.familyrulesandroid

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState.ACTIVE
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState.BLOCK_RESTRICTED_APPS
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesColors
import pl.zarajczyk.familyrulesandroid.utils.CrashLogger

/**
 * Shared layout component that provides the common structure for both MainActivity and InitialSetupActivity.
 * Features:
 * - Same background color
 * - Same logo at the top (left in landscape)
 * - Same column layout in landscape
 * - Menu icon in top-right corner (or top-right of left column in landscape)
 */
@Composable
fun SharedAppLayout(
    deviceState: DeviceState = ACTIVE,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) { innerPadding ->
        val bgColor = when (deviceState) {
            ACTIVE -> FamilyRulesColors.NORMAL_BACKGROUND
            BLOCK_RESTRICTED_APPS -> FamilyRulesColors.BLOCKING_COLOR
        }
        if (isLandscape) {
            // Horizontal layout for landscape orientation
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(innerPadding)
            ) {
                // Left side - Icon and label with menu
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
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
                            color = FamilyRulesColors.TEXT_COLOR,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                    
                    // Menu icon in top-right corner of left column
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .wrapContentSize(Alignment.TopEnd)
                    ) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.menu),
                                tint = FamilyRulesColors.TEXT_COLOR
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_crash_logs)) },
                                onClick = {
                                    showMenu = false
                                    exportCrashLogs(context)
                                }
                            )
                        }
                    }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
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
                                color = FamilyRulesColors.TEXT_COLOR,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            content()
                        }
                    }
                }
                
                // Menu icon in top-right corner
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .wrapContentSize(Alignment.TopEnd)
                ) {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.menu),
                            tint = FamilyRulesColors.TEXT_COLOR
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_crash_logs)) },
                            onClick = {
                                showMenu = false
                                exportCrashLogs(context)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Exports crash logs and opens a share dialog
 */
private fun exportCrashLogs(context: android.content.Context) {
    val exportFile = CrashLogger.exportAllCrashLogs(context)
    
    if (exportFile == null) {
        // No crash logs to export - could show a toast here
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.no_crash_logs),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }
    
    // Create a content URI for the file using FileProvider
    val contentUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        exportFile
    )
    
    // Create share intent
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_logs_export_subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    
    // Start share activity
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.export_crash_logs)))
}

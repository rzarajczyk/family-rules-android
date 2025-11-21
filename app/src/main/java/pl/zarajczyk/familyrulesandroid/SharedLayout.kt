package pl.zarajczyk.familyrulesandroid

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import pl.zarajczyk.familyrulesandroid.core.SettingsManager
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesColors
import pl.zarajczyk.familyrulesandroid.utils.Logger

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
    settingsManager: SettingsManager,
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
                            onDismissRequest = { showMenu = false },
                            containerColor = FamilyRulesColors.SECONDARY_BACKGROUND_COLOR
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_logs)) },
                                onClick = {
                                    showMenu = false
                                    exportLogs(context)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clean_logs)) },
                                onClick = {
                                    showMenu = false
                                    cleanLogs(context)
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(settingsManager.getVersion()) },
                                onClick = { },
                                enabled = false
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
                    // Top-aligned header and content area
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
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
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                        // TabRow/content injected by caller should appear directly under header
                        content()
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
                        onDismissRequest = { showMenu = false },
                        containerColor = FamilyRulesColors.SECONDARY_BACKGROUND_COLOR
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_logs)) },
                            onClick = {
                                showMenu = false
                                exportLogs(context)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clean_logs)) },
                            onClick = {
                                showMenu = false
                                cleanLogs(context)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(settingsManager.getVersion()) },
                            onClick = { },
                            enabled = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * Exports all log files and opens a share dialog
 */
private fun exportLogs(context: android.content.Context) {
    val logFiles = Logger.exportLogs(context)
    
    if (logFiles == null || logFiles.isEmpty()) {
        // No logs to export
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.no_crash_logs),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }
    
    // Android supports sharing multiple files
    if (logFiles.size == 1) {
        // Share single file
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFiles[0]
        )
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_logs_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_logs)))
    } else {
        // Share multiple files
        val uris = ArrayList<android.net.Uri>()
        logFiles.forEach { file ->
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            uris.add(uri)
        }
        
        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_logs_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_logs)))
    }
}

/**
 * Cleans all log files
 */
private fun cleanLogs(context: android.content.Context) {
    val success = Logger.clearAllLogs(context)
    
    val message = if (success) {
        context.getString(R.string.logs_cleaned)
    } else {
        context.getString(R.string.logs_clean_failed)
    }
    
    android.widget.Toast.makeText(
        context,
        message,
        android.widget.Toast.LENGTH_SHORT
    ).show()
}

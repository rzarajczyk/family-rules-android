package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.zarajczyk.familyrulesandroid.FindDeviceAlarmActivity
import pl.zarajczyk.familyrulesandroid.adapter.CommandAckDto
import pl.zarajczyk.familyrulesandroid.adapter.CommandResultDto
import pl.zarajczyk.familyrulesandroid.adapter.FamilyRulesClient
import pl.zarajczyk.familyrulesandroid.adapter.ServerCommandDto
import pl.zarajczyk.familyrulesandroid.database.AppDb
import pl.zarajczyk.familyrulesandroid.database.ServerCommand
import pl.zarajczyk.familyrulesandroid.database.ServerCommandExecutionState
import pl.zarajczyk.familyrulesandroid.database.ServerCommandMeta
import pl.zarajczyk.familyrulesandroid.utils.Logger
import java.io.File
import java.time.Instant
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

private const val TAG = "ServerCommandCoordinator"
private val STALE_PLAY_LOUD_SOUND_EXECUTING_MS =
    (LoudSoundPlayer.PLAY_DURATION_SECONDS + 15L) * 1000L

class ServerCommandCoordinator(
    private val context: Context,
    private val appDb: AppDb,
    private val familyRulesClient: FamilyRulesClient,
) {
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loudSoundJob: Job? = null
    private val loudSoundPlayer = LoudSoundPlayer(context)
    private val payloadAdapter: JsonAdapter<Map<String, String>> = Moshi.Builder()
        .build()
        .adapter(Types.newParameterizedType(Map::class.java, String::class.java, String::class.java))

    suspend fun onCommandsReceived(commands: List<ServerCommandDto>) {
        if (commands.isEmpty()) {
            retryPendingWork()
            return
        }

        val now = System.currentTimeMillis()
        commands.forEach { command ->
            val inserted = appDb.insertServerCommandIfAbsent(
                ServerCommand(
                    commandId = command.commandId,
                    commandName = command.commandName,
                    issuedAtIso = command.issuedAt,
                    protocolVersion = command.protocolVersion,
                    receivedAtMillis = now,
                    executionState = ServerCommandExecutionState.RECEIVED.name,
                )
            )
            if (inserted) {
                Logger.i(TAG, "Stored server command ${command.commandName} (${command.commandId})")
            }
        }

        retryPendingWork()
    }

    suspend fun retryPendingWork() {
        appDb.cleanUpStalePayloadFiles()
        recoverStaleExecutingPlayLoudSound()
        acknowledgePendingCommands()
        executePendingCommands()
        uploadPendingResults()
    }

    private suspend fun recoverStaleExecutingPlayLoudSound() {
        if (loudSoundJob?.isActive == true) return
        val cutoff = System.currentTimeMillis() - STALE_PLAY_LOUD_SOUND_EXECUTING_MS
        appDb.resetStaleExecutingPlayLoudSound(cutoff)
    }

    private suspend fun acknowledgePendingCommands() {
        val pendingAcks = appDb.getPendingCommandAcks()
        if (pendingAcks.isEmpty()) return

        val acknowledged = familyRulesClient.acknowledgeCommands(
            pendingAcks.map {
                CommandAckDto(
                    commandId = it.commandId,
                    receivedAt = Instant.ofEpochMilli(it.receivedAtMillis).toString(),
                )
            }
        )
        if (acknowledged) {
            appDb.markCommandAcksConfirmed(pendingAcks.map { it.commandId }, System.currentTimeMillis())
        }
    }

    private suspend fun executePendingCommands() {
        val pending = appDb.getCommandsByExecutionState(ServerCommandExecutionState.RECEIVED)
        pending.forEach { command ->
            when (command.commandName) {
                "PLAY_LOUD_SOUND" -> launchPlayLoudSound(command.commandId)
                else -> executeCommandSynchronously(command)
            }
        }
    }

    private suspend fun executeCommandSynchronously(command: ServerCommandMeta) {
        appDb.markCommandExecuting(command.commandId)
        val result = when (command.commandName) {
            "SEND_LOGS" -> executeSendLogs(command.commandId)
            else -> unsupportedCommand(command.commandName)
        }
        appDb.storeCommandResult(
            commandId = command.commandId,
            resultStatus = result.status,
            responseType = result.responseType,
            responsePayloadJson = result.responsePayloadJson,
            responsePayloadFilePath = result.responsePayloadFilePath,
            completedAtIso = result.completedAt,
        )
    }

    private fun launchPlayLoudSound(commandId: String) {
        loudSoundJob?.cancel()
        FindDeviceAlarmActivity.finishIfVisible()
        loudSoundJob = commandScope.launch {
            appDb.markCommandExecuting(commandId)
            val completedAt = Instant.now().toString()
            val result = try {
                FindDeviceAlarmActivity.show(context)
                val playResult = loudSoundPlayer.play()
                Logger.i(TAG, "Played loud sound for command $commandId (${playResult.playedSeconds}s)")
                CommandExecutionResult(
                    status = "SUCCEEDED",
                    responseType = "PLAY_LOUD_SOUND_V1",
                    responsePayloadJson = payloadAdapter.toJson(
                        mapOf(
                            "playedSeconds" to playResult.playedSeconds.toString(),
                            "alarmVolumeRaised" to playResult.alarmVolumeRaised.toString(),
                            "dismissedEarly" to LoudSoundSession.isDismissRequested().toString(),
                        )
                    ),
                    responsePayloadFilePath = null,
                    completedAt = completedAt,
                )
            } catch (e: CancellationException) {
                loudSoundPlayer.stop()
                Logger.i(TAG, "PLAY_LOUD_SOUND cancelled for command $commandId")
                CommandExecutionResult(
                    status = "FAILED",
                    responseType = "PLAY_LOUD_SOUND_V1",
                    responsePayloadJson = payloadAdapter.toJson(mapOf("error" to "cancelled")),
                    responsePayloadFilePath = null,
                    completedAt = completedAt,
                )
            } catch (e: Exception) {
                Logger.e(TAG, "PLAY_LOUD_SOUND failed for command $commandId", e)
                CommandExecutionResult(
                    status = "FAILED",
                    responseType = "PLAY_LOUD_SOUND_V1",
                    responsePayloadJson = payloadAdapter.toJson(
                        mapOf("error" to (e.message ?: "unknown"))
                    ),
                    responsePayloadFilePath = null,
                    completedAt = completedAt,
                )
            } finally {
                FindDeviceAlarmActivity.finishIfVisible()
                LoudSoundSession.reset()
            }
            appDb.storeCommandResult(
                commandId = commandId,
                resultStatus = result.status,
                responseType = result.responseType,
                responsePayloadJson = result.responsePayloadJson,
                responsePayloadFilePath = result.responsePayloadFilePath,
                completedAtIso = result.completedAt,
            )
            retryPendingWork()
        }
    }

    private suspend fun uploadPendingResults() {
        val pendingUploads = appDb.getPendingCommandResultUploads()
        if (pendingUploads.isEmpty()) return

        val results = pendingUploads.mapNotNull { command ->
            val payload = resolvePayload(command) ?: return@mapNotNull null
            CommandResultDto(
                commandId = command.commandId,
                commandName = command.commandName,
                completedAt = command.completedAtIso ?: Instant.now().toString(),
                status = command.resultStatus ?: "FAILED",
                responseType = command.responseType ?: "UNKNOWN",
                responsePayload = payload,
            )
        }

        if (familyRulesClient.sendCommandResults(results)) {
            val now = System.currentTimeMillis()
            pendingUploads.forEach { command ->
                appDb.markCommandResultUploaded(command.commandId, now)
                // Delete the payload file now that it has been successfully uploaded
                command.responsePayloadFilePath?.let { path ->
                    try {
                        val deleted = File(path).delete()
                        if (deleted) {
                            Logger.i(TAG, "Deleted payload file after upload: $path")
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to delete payload file $path", e)
                    }
                }
            }
        }
    }

    /**
     * Resolves the response payload map for a command.
     *
     * Priority:
     * 1. If [ServerCommand.responsePayloadFilePath] is set, read the payload text from that file
     *    and reconstruct the map (file-backed path for large payloads like SEND_LOGS).
     * 2. Fall back to parsing [ServerCommand.responsePayloadJson] inline (legacy / small payloads).
     */
    private suspend fun resolvePayload(command: ServerCommand): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            val filePath = command.responsePayloadFilePath
            if (filePath != null) {
                try {
                    val file = File(filePath)
                    if (!file.exists()) {
                        Logger.w(TAG, "Payload file missing for command ${command.commandId}: $filePath")
                        return@withContext null
                    }
                    val logsText = file.readText()
                    mapOf(
                        "logsText" to logsText,
                        "truncated" to "false",
                        "collectedAt" to (command.completedAtIso ?: Instant.now().toString()),
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to read payload file $filePath for command ${command.commandId}", e)
                    null
                }
            } else {
                val payloadJson = command.responsePayloadJson ?: return@withContext null
                payloadAdapter.fromJson(payloadJson) ?: emptyMap()
            }
        }
    }

    private suspend fun executeSendLogs(commandId: String): CommandExecutionResult {
        return withContext(Dispatchers.IO) {
            val logText = collectLogsForUpload(context)
            val completedAt = Instant.now().toString()
            Logger.i(TAG, "Collected logs for command $commandId (${logText.text.length} chars)")

            // Write the (potentially very large) log payload to a file in noBackupFilesDir
            // to avoid SQLiteBlobTooBigException when storing in the database row.
            val payloadFile = writePayloadFile(commandId, logText.text)
            if (payloadFile != null) {
                Logger.i(TAG, "Wrote log payload to file ${payloadFile.path} for command $commandId")
                CommandExecutionResult(
                    status = "SUCCEEDED",
                    responseType = "SEND_LOGS_V1",
                    responsePayloadJson = null,
                    responsePayloadFilePath = payloadFile.absolutePath,
                    completedAt = completedAt,
                )
            } else {
                // Fallback: store inline (may be large, but better than losing the data)
                Logger.w(TAG, "Could not write payload file for command $commandId; storing inline")
                CommandExecutionResult(
                    status = "SUCCEEDED",
                    responseType = "SEND_LOGS_V1",
                    responsePayloadJson = payloadAdapter.toJson(
                        mapOf(
                            "logsText" to logText.text,
                            "truncated" to "false",
                            "collectedAt" to completedAt,
                        )
                    ),
                    responsePayloadFilePath = null,
                    completedAt = completedAt,
                )
            }
        }
    }

    private fun writePayloadFile(commandId: String, content: String): File? {
        return try {
            val dir = appDb.payloadDir()
            val file = File(dir, "$commandId.txt")
            file.writeText(content)
            file
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to write payload file for command $commandId", e)
            null
        }
    }

    private fun unsupportedCommand(commandName: String) = CommandExecutionResult(
        status = "FAILED",
        responseType = "UNSUPPORTED_COMMAND_V1",
        responsePayloadJson = payloadAdapter.toJson(mapOf("receivedCommandName" to commandName)),
        responsePayloadFilePath = null,
        completedAt = Instant.now().toString(),
    )

    private fun collectLogsForUpload(context: Context): LogPayload {
        val files = Logger.exportLogs(context).orEmpty()
        if (files.isEmpty()) {
            return LogPayload("No logs available")
        }

        val combined = buildString {
            files.forEachIndexed { index, file ->
                if (index > 0) append("\n\n")
                append("===== ${file.name} =====\n")
                append(file.readText())
            }
        }

        return LogPayload(combined)
    }
}

private data class CommandExecutionResult(
    val status: String,
    val responseType: String,
    val responsePayloadJson: String?,
    val responsePayloadFilePath: String?,
    val completedAt: String,
)

private data class LogPayload(
    val text: String,
)

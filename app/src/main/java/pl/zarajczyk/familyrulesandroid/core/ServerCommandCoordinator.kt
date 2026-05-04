package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.zarajczyk.familyrulesandroid.adapter.CommandAckDto
import pl.zarajczyk.familyrulesandroid.adapter.CommandResultDto
import pl.zarajczyk.familyrulesandroid.adapter.FamilyRulesClient
import pl.zarajczyk.familyrulesandroid.adapter.ServerCommandDto
import pl.zarajczyk.familyrulesandroid.database.AppDb
import pl.zarajczyk.familyrulesandroid.database.ServerCommand
import pl.zarajczyk.familyrulesandroid.database.ServerCommandExecutionState
import pl.zarajczyk.familyrulesandroid.utils.Logger
import java.time.Instant
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

private const val TAG = "ServerCommandCoordinator"

class ServerCommandCoordinator(
    private val context: Context,
    private val appDb: AppDb,
    private val familyRulesClient: FamilyRulesClient,
) {
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
        acknowledgePendingCommands()
        executePendingCommands()
        uploadPendingResults()
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
            appDb.markCommandExecuting(command.commandId)
            val result = when (command.commandName) {
                "SEND_LOGS" -> executeSendLogs(command.commandId)
                else -> unsupportedCommand(command.commandName)
            }
            appDb.storeCommandResult(
                commandId = command.commandId,
                resultStatus = result.status,
                responseType = result.responseType,
                responsePayloadJson = payloadAdapter.toJson(result.responsePayload),
                completedAtIso = result.completedAt,
            )
        }
    }

    private suspend fun uploadPendingResults() {
        val pendingUploads = appDb.getPendingCommandResultUploads()
        if (pendingUploads.isEmpty()) return

        val results = pendingUploads.mapNotNull { command ->
            val payloadJson = command.responsePayloadJson ?: return@mapNotNull null
            val payload = payloadAdapter.fromJson(payloadJson) ?: emptyMap()
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
            pendingUploads.forEach { appDb.markCommandResultUploaded(it.commandId, now) }
        }
    }

    private suspend fun executeSendLogs(commandId: String): CommandExecutionResult {
        return withContext(Dispatchers.IO) {
            val logText = collectLogsForUpload(context)
            Logger.i(TAG, "Collected logs for command $commandId (${logText.text.length} chars)")
            CommandExecutionResult(
                status = "SUCCEEDED",
                responseType = "SEND_LOGS_V1",
                responsePayload = mapOf(
                    "logsText" to logText.text,
                    "truncated" to "false",
                    "collectedAt" to Instant.now().toString(),
                ),
                completedAt = Instant.now().toString(),
            )
        }
    }

    private fun unsupportedCommand(commandName: String) = CommandExecutionResult(
        status = "FAILED",
        responseType = "UNSUPPORTED_COMMAND_V1",
        responsePayload = mapOf("receivedCommandName" to commandName),
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
    val responsePayload: Map<String, String>,
    val completedAt: String,
)

private data class LogPayload(
    val text: String,
)

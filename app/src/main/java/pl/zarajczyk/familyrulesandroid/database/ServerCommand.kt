package pl.zarajczyk.familyrulesandroid.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_commands")
data class ServerCommand(
    @PrimaryKey
    val commandId: String,
    val commandName: String,
    val issuedAtIso: String,
    val protocolVersion: Int,
    val receivedAtMillis: Long,
    val ackConfirmedAtMillis: Long? = null,
    val executionState: String,
    val resultStatus: String? = null,
    val responseType: String? = null,
    val responsePayloadJson: String? = null,
    val completedAtIso: String? = null,
    val resultUploadedAtMillis: Long? = null,
)

enum class ServerCommandExecutionState {
    RECEIVED,
    EXECUTING,
    COMPLETED,
}

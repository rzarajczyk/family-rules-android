package pl.zarajczyk.familyrulesandroid.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Lightweight projection of [ServerCommand] that excludes the potentially very large
 * [ServerCommand.responsePayloadJson] and [ServerCommand.responsePayloadFilePath] columns.
 * Use this for queries that do NOT need to read or upload the payload, to avoid
 * SQLiteBlobTooBigException on rows with large payloads.
 */
data class ServerCommandMeta(
    val commandId: String,
    val commandName: String,
    val issuedAtIso: String,
    val protocolVersion: Int,
    val receivedAtMillis: Long,
    val ackConfirmedAtMillis: Long?,
    val executionState: String,
    val resultStatus: String?,
    val responseType: String?,
    val completedAtIso: String?,
    val resultUploadedAtMillis: Long?,
)

@Dao
interface ServerCommandDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(command: ServerCommand): Long

    // Lightweight — excludes responsePayloadJson / responsePayloadFilePath
    @Query(
        "SELECT commandId, commandName, issuedAtIso, protocolVersion, receivedAtMillis, " +
        "ackConfirmedAtMillis, executionState, resultStatus, responseType, completedAtIso, " +
        "resultUploadedAtMillis FROM server_commands WHERE ackConfirmedAtMillis IS NULL ORDER BY receivedAtMillis ASC"
    )
    suspend fun getPendingAcksMeta(): List<ServerCommandMeta>

    // Lightweight — excludes responsePayloadJson / responsePayloadFilePath
    @Query(
        "SELECT commandId, commandName, issuedAtIso, protocolVersion, receivedAtMillis, " +
        "ackConfirmedAtMillis, executionState, resultStatus, responseType, completedAtIso, " +
        "resultUploadedAtMillis FROM server_commands WHERE executionState = :executionState ORDER BY issuedAtIso ASC"
    )
    suspend fun getByExecutionStateMeta(executionState: String): List<ServerCommandMeta>

    // Full row needed here — must read responsePayloadJson / responsePayloadFilePath for upload
    @Query("SELECT * FROM server_commands WHERE (responsePayloadJson IS NOT NULL OR responsePayloadFilePath IS NOT NULL) AND resultUploadedAtMillis IS NULL ORDER BY issuedAtIso ASC")
    suspend fun getPendingResultUploads(): List<ServerCommand>

    @Query("UPDATE server_commands SET ackConfirmedAtMillis = :ackConfirmedAtMillis WHERE commandId IN (:commandIds)")
    suspend fun markAckConfirmed(commandIds: List<String>, ackConfirmedAtMillis: Long)

    @Query("UPDATE server_commands SET executionState = :executionState WHERE commandId = :commandId")
    suspend fun updateExecutionState(commandId: String, executionState: String)

    @Query(
        "UPDATE server_commands SET executionState = :executionState, resultStatus = :resultStatus, responseType = :responseType, responsePayloadJson = :responsePayloadJson, responsePayloadFilePath = :responsePayloadFilePath, completedAtIso = :completedAtIso WHERE commandId = :commandId"
    )
    suspend fun storeResult(
        commandId: String,
        executionState: String,
        resultStatus: String,
        responseType: String,
        responsePayloadJson: String?,
        responsePayloadFilePath: String?,
        completedAtIso: String,
    )

    @Query("UPDATE server_commands SET resultUploadedAtMillis = :uploadedAtMillis, executionState = :executionState WHERE commandId = :commandId")
    suspend fun markResultUploaded(commandId: String, uploadedAtMillis: Long, executionState: String)
}

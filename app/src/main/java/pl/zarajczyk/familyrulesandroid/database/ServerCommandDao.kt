package pl.zarajczyk.familyrulesandroid.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ServerCommandDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(command: ServerCommand): Long

    @Query("SELECT * FROM server_commands WHERE ackConfirmedAtMillis IS NULL ORDER BY receivedAtMillis ASC")
    suspend fun getPendingAcks(): List<ServerCommand>

    @Query("SELECT * FROM server_commands WHERE executionState = :executionState ORDER BY issuedAtIso ASC")
    suspend fun getByExecutionState(executionState: String): List<ServerCommand>

    @Query("SELECT * FROM server_commands WHERE responsePayloadJson IS NOT NULL AND resultUploadedAtMillis IS NULL ORDER BY issuedAtIso ASC")
    suspend fun getPendingResultUploads(): List<ServerCommand>

    @Query("UPDATE server_commands SET ackConfirmedAtMillis = :ackConfirmedAtMillis WHERE commandId IN (:commandIds)")
    suspend fun markAckConfirmed(commandIds: List<String>, ackConfirmedAtMillis: Long)

    @Query("UPDATE server_commands SET executionState = :executionState WHERE commandId = :commandId")
    suspend fun updateExecutionState(commandId: String, executionState: String)

    @Query(
        "UPDATE server_commands SET executionState = :executionState, resultStatus = :resultStatus, responseType = :responseType, responsePayloadJson = :responsePayloadJson, completedAtIso = :completedAtIso WHERE commandId = :commandId"
    )
    suspend fun storeResult(
        commandId: String,
        executionState: String,
        resultStatus: String,
        responseType: String,
        responsePayloadJson: String,
        completedAtIso: String,
    )

    @Query("UPDATE server_commands SET resultUploadedAtMillis = :uploadedAtMillis, executionState = :executionState WHERE commandId = :commandId")
    suspend fun markResultUploaded(commandId: String, uploadedAtMillis: Long, executionState: String)
}

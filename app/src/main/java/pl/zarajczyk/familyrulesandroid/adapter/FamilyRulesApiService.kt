package pl.zarajczyk.familyrulesandroid.adapter

import retrofit2.http.Body
import retrofit2.http.POST

// Request DTOs
data class AppData(
    val appName: String,
    val iconBase64Png: String?
)

data class AvailableState(
    val deviceState: String,
    val title: String,
    val icon: String,
    val description: String,
    val arguments: Set<String>?
)

data class ClientInfoRequest(
    val instanceId: String,
    val version: String,
    val knownApps: Map<String, AppData>,
    val availableStates: List<AvailableState>,
    val capabilities: List<String>,
    val pushToken: String? = null,
)

data class ReportRequest(
    val instanceId: String,
    val screenTime: Long,
    val applications: Map<String, Long>,
    val activeApps: Set<String>,
    val mediaPlayingApps: Set<String> = emptySet(),
    val isOnline: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

// Response DTOs
data class ClientInfoResponseDto(
    val restrictedApps: Map<String, AppDetails>?
)

data class ReportResponseDto(
    val deviceState: String,
    val extra: String?,
    val serverCommands: List<ServerCommandDto> = emptyList(),
)

data class ServerCommandDto(
    val commandId: String,
    val commandName: String,
    val issuedAt: String,
    val protocolVersion: Int,
)

data class CommandAcksRequest(
    val acks: List<CommandAckDto>,
)

data class CommandAckDto(
    val commandId: String,
    val receivedAt: String,
)

data class CommandResultsRequest(
    val results: List<CommandResultDto>,
)

data class CommandResultDto(
    val commandId: String,
    val commandName: String,
    val completedAt: String,
    val status: String,
    val responseType: String,
    val responsePayload: Map<String, String>,
)

data class StatusResponseDto(
    val status: String,
)

data class BlockedAppsResponse(
    val apps: List<MembershipAppResponse>
)

data class MembershipAppResponse(
    val appPath: String,
)

// Groups Usage Report DTOs
data class AppGroupsUsageReportResponse(
    val appGroups: List<AppGroupUsageReportResponse>
)

data class AppGroupUsageReportResponse(
    val appGroupId: String,
    val appGroupName: String,
    val apps: List<AppUsageReportResponse>,
    val totalTimeSeconds: Long
)

data class AppUsageReportResponse(
    val appPath: String,
    val appName: String,
    val iconBase64Png: String?,
    val deviceName: String,
    val deviceId: String,
    val uptimeSeconds: Long
)

interface FamilyRulesApiService {
    @POST("/api/v2/client-info")
    suspend fun sendClientInfo(@Body body: ClientInfoRequest): ClientInfoResponseDto

    @POST("/api/v2/report")
    suspend fun report(@Body body: ReportRequest): ReportResponseDto

    @POST("/api/v2/get-blocked-apps")
    suspend fun getBlockedApps(): BlockedAppsResponse

    @POST("/api/v2/get-blocked-playback-apps")
    suspend fun getBlockedPlaybackApps(): BlockedAppsResponse

    @POST("/api/v2/groups-usage-report")
    suspend fun getGroupsUsageReport(): AppGroupsUsageReportResponse

    @POST("/api/v2/command-acks")
    suspend fun acknowledgeCommands(@Body body: CommandAcksRequest): StatusResponseDto

    @POST("/api/v2/command-results")
    suspend fun sendCommandResults(@Body body: CommandResultsRequest): StatusResponseDto
}

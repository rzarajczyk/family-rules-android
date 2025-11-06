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
    val availableStates: List<AvailableState>
)

data class ReportRequest(
    val instanceId: String,
    val screenTime: Long,
    val applications: Map<String, Long>
)

// Response DTOs
data class ClientInfoResponseDto(
    val restrictedApps: Map<String, AppDetails>?
)

data class ReportResponseDto(
    val deviceState: String,
    val extra: String?
)

data class AppGroupReportRequest(
    val appGroupId: String
)

data class AppGroupReportResponse(
    val appGroupId: String,
    val apps: Map<String, App>
)

data class App(
    val appName: String,
    val iconBase64Png: String?
)

interface FamilyRulesApiService {
    @POST("/api/v2/client-info")
    suspend fun sendClientInfo(@Body body: ClientInfoRequest): ClientInfoResponseDto

    @POST("/api/v2/report")
    suspend fun report(@Body body: ReportRequest): ReportResponseDto

    @POST("/api/v2/group-report")
    suspend fun groupReport(@Body body: AppGroupReportRequest): AppGroupReportResponse
}



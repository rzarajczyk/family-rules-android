package pl.zarajczyk.familyrulesandroid.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_info")
data class AppInfo(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val iconBase64: String?, // Store icon as base64 string
    val lastUpdated: Long = System.currentTimeMillis()
)

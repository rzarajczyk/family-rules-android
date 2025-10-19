package pl.zarajczyk.familyrulesandroid.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AppInfoDao {
    @Query("SELECT * FROM app_info WHERE packageName = :packageName")
    suspend fun getAppInfo(packageName: String): AppInfo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppInfo(appInfo: AppInfo)

    @Update
    suspend fun updateAppInfo(appInfo: AppInfo)

    @Query("DELETE FROM app_info WHERE packageName = :packageName")
    suspend fun deleteAppInfo(packageName: String)

    @Query("SELECT * FROM app_info")
    suspend fun getAllAppInfo(): List<AppInfo>
}

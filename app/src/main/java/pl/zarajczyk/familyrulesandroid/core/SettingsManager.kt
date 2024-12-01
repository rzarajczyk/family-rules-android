package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val version = javaClass.getResourceAsStream("/version.txt")
        ?.bufferedReader()
        ?.readText()
        ?.trim()
        ?: "v?.?"

    fun getString(key: String, defaultValue: String?): String? {
        return preferences.getString(key, defaultValue)
    }

    fun setString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    fun areSettingsComplete(): Boolean {
        return getString("serverUrl", null) != null &&
               getString("username", null) != null &&
               getString("instanceId", null) != null &&
               getString("instanceName", null) != null &&
               getString("instanceToken", null) != null
    }

    fun clearSettings() {
        preferences.edit().clear().apply()
    }

    fun getVersion(): String {
        return version
    }

}
package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsManager(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val version = javaClass.getResourceAsStream("/version.txt")
        ?.bufferedReader()
        ?.readText()
        ?.trim()
        ?: "v?.?"

    fun getString(key: String, defaultValue: String): String {
        return preferences.getString(key, defaultValue)!!
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return preferences.getString(key, defaultValue)
    }

    fun setString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    fun areSettingsComplete(): Boolean {
        return getString("serverUrl") != null &&
                getString("username") != null &&
                getString("instanceId") != null &&
                getString("instanceName") != null &&
                getString("instanceToken") != null
    }

    fun getVersion(): String {
        return version
    }


}
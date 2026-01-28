package com.tools.il2fusion.config

import android.content.Context

data class AutoFlowConfig(
    val downloadUrl: String,
    val uploadUrl: String
)

object AutoFlowConfigStore {
    private const val PREFS_NAME = "auto_flow_config"
    private const val KEY_DOWNLOAD_URL = "download_url"
    private const val KEY_UPLOAD_URL = "upload_url"

    fun load(context: Context): AutoFlowConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AutoFlowConfig(
            downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, "") ?: "",
            uploadUrl = prefs.getString(KEY_UPLOAD_URL, "") ?: ""
        )
    }

    fun save(context: Context, downloadUrl: String, uploadUrl: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DOWNLOAD_URL, downloadUrl)
            .putString(KEY_UPLOAD_URL, uploadUrl)
            .apply()
    }
}

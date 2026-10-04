package com.silf.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _endpointUrl = MutableStateFlow(
        prefs.getString(KEY_ENDPOINT_URL, DEFAULT_ENDPOINT_URL) ?: DEFAULT_ENDPOINT_URL
    )
    val endpointUrl: StateFlow<String> = _endpointUrl.asStateFlow()

    private val _modelName = MutableStateFlow(
        prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME) ?: DEFAULT_MODEL_NAME
    )
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    fun setEndpointUrl(url: String) {
        val trimmed = url.trim()
        prefs.edit().putString(KEY_ENDPOINT_URL, trimmed).apply()
        _endpointUrl.value = trimmed
    }

    fun setModelName(model: String) {
        val trimmed = model.trim()
        prefs.edit().putString(KEY_MODEL_NAME, trimmed).apply()
        _modelName.value = trimmed
    }

    companion object {
        private const val PREFS_NAME = "silf_client_prefs"
        private const val KEY_ENDPOINT_URL = "endpoint_url"
        private const val KEY_MODEL_NAME = "model_name"

        const val DEFAULT_ENDPOINT_URL = "http://192.168.1.80:11434/api/generate"
        const val DEFAULT_MODEL_NAME = "qwen2.5:3b"
    }
}

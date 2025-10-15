package com.example.frontendproyectoapp.config

import android.content.Context
import android.content.SharedPreferences

object GeminiConfig {
    private const val PREFS_NAME = "gemini_config"
    private const val API_KEY_PREF = "api_key"
    
    // Configuración para Gemini API
    private const val DEFAULT_API_KEY = "AIzaSyA3W3r-d7-e9SHu6MtyI-AEvwtiQvpnnJc" // Reemplazar con tu API key real de Gemini
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1" // Usar v1 para modelos más nuevos
    const val MODEL_NAME = "gemini-2.5-flash" // Modelo disponible en v1
    
    private var _apiKey: String? = null
    
    val API_KEY: String
        get() = _apiKey ?: DEFAULT_API_KEY

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _apiKey = prefs.getString(API_KEY_PREF, DEFAULT_API_KEY)
    }
    
    fun setApiKey(context: Context, apiKey: String) {
        _apiKey = apiKey
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(API_KEY_PREF, apiKey).apply()
    }

    fun isValid(): Boolean {
        return API_KEY.isNotBlank() && API_KEY.startsWith("AIzaSy")
    }
}

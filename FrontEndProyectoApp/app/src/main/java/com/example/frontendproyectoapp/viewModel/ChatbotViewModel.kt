package com.example.frontendproyectoapp.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.frontendproyectoapp.model.*
import com.example.frontendproyectoapp.service.ChatbotService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

enum class ApiStatus {
    UNKNOWN,
    CONNECTING,
    SUCCESS,
    API_KEY_INVALID,
    API_KEY_EXPIRED,
    NETWORK_ERROR,
    RATE_LIMIT_EXCEEDED,
    QUOTA_EXCEEDED,
    SERVER_ERROR,
    TIMEOUT,
    FAILED
}

class ChatbotViewModel(application: Application) : AndroidViewModel(application) {
    
    private val chatbotService = ChatbotService()
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _currentSession = MutableStateFlow<SesionChatbot?>(null)
    val currentSession: StateFlow<SesionChatbot?> = _currentSession.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Sistema de alertas para diagnóstico de API
    private val _apiStatus = MutableStateFlow<ApiStatus>(ApiStatus.UNKNOWN)
    val apiStatus: StateFlow<ApiStatus> = _apiStatus.asStateFlow()
    
    private val _diagnosticMessage = MutableStateFlow<String?>(null)
    val diagnosticMessage: StateFlow<String?> = _diagnosticMessage.asStateFlow()
    
    // Nuevas funcionalidades para gestión de rutina
    private val _modificationHistory = MutableStateFlow<List<RoutineModification>>(emptyList())
    val modificationHistory: StateFlow<List<RoutineModification>> = _modificationHistory.asStateFlow()
    
    private val _currentRoutine = MutableStateFlow<List<RegistroAlimentoSalida>>(emptyList())
    val currentRoutine: StateFlow<List<RegistroAlimentoSalida>> = _currentRoutine.asStateFlow()
    
    private val _userProfile = MutableStateFlow<Usuario?>(null)
    val userProfile: StateFlow<Usuario?> = _userProfile.asStateFlow()
    
    // Notificación de cambios en la rutina
    private val _routineUpdated = MutableStateFlow(false)
    val routineUpdated: StateFlow<Boolean> = _routineUpdated.asStateFlow()
    
    fun startNewSession(userId: Long = 1L) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                println("=== INICIANDO SESIÓN CON USUARIO ID: $userId ===")
                
                // Configurar callback para notificar cambios en la rutina
                chatbotService.setOnRoutineUpdatedCallback {
                    notifyRoutineUpdated()
                }
                
                val session = chatbotService.createSession(userId)
                _currentSession.value = session
                _messages.value = emptyList()
                _error.value = null
                
                // Cargar perfil del usuario si no está cargado
                if (_userProfile.value == null && userId != 0L) {
                    println("=== CARGANDO PERFIL DEL USUARIO EN CHATBOT VIEWMODEL ===")
                    loadUserProfile(userId)
                }
                
                // Test de conexión con Gemini API
                val geminiService = com.example.frontendproyectoapp.service.GeminiNutriAIService()
                println("=== TESTING GEMINI API CONNECTION ===")
                
                // Test directo de generación de respuesta
                try {
                    val directTest = geminiService.generateResponse("Hola, ¿cómo estás?")
                    println("Test directo de respuesta Gemini: $directTest")
                } catch (e: Exception) {
                    println("Error en test directo Gemini: ${e.message}")
                    e.printStackTrace()
                }
                
                // Test específico del chatbot - DESHABILITADO para evitar interferencia
                // println("=== TESTING CHATBOT SPECIFIC RESPONSE ===")
                // val chatbotTest = chatbotService.testChatbotResponse()
                // println("Chatbot test result: $chatbotTest")
                
            } catch (e: Exception) {
                _error.value = "Error al iniciar sesión: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun sendMessage(message: String, userProfile: Usuario? = null, currentRoutine: List<RegistroAlimentoSalida>? = null) {
        if (message.isBlank()) return
        
        // Agregar mensaje del usuario inmediatamente
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            message = message,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )
        
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(userMessage)
        _messages.value = currentMessages
        
        // Enviar al backend
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _apiStatus.value = ApiStatus.CONNECTING
                _diagnosticMessage.value = "🔍 Conectando con Gemini API..."
                
                // Usar el perfil del usuario del ViewModel si no se proporciona uno
                val finalUserProfile = userProfile ?: _userProfile.value
                
                // Usar la rutina del ViewModel si no se proporciona una externa
                val finalCurrentRoutine = currentRoutine ?: _currentRoutine.value
                
                println("=== CHATBOT VIEWMODEL ===")
                println("Mensaje del usuario: $message")
                println("ID Sesión: ${_currentSession.value?.idSesion}")
                println("UserProfile pasado: $userProfile")
                println("UserProfile del ViewModel: ${_userProfile.value}")
                println("UserProfile final: $finalUserProfile")
                println("Nombre del usuario: ${finalUserProfile?.nombre}")
                println("CurrentRoutine pasado: ${currentRoutine?.size} elementos")
                println("CurrentRoutine del ViewModel: ${_currentRoutine.value.size} elementos")
                println("CurrentRoutine final: ${finalCurrentRoutine.size} elementos")
                println("CurrentRoutine detalle: ${finalCurrentRoutine.map { "${it.alimento.nombreAlimento} (${it.momentoDelDia})" }}")
                
                // Diagnóstico de API key
                _diagnosticMessage.value = "🔑 Verificando API key de Gemini..."
                val apiKeyStatus = checkApiKeyStatus()
                if (apiKeyStatus != ApiStatus.SUCCESS) {
                    _apiStatus.value = apiKeyStatus
                    _diagnosticMessage.value = getApiStatusMessage(apiKeyStatus)
                    return@launch
                }
                
                _diagnosticMessage.value = "📡 Enviando solicitud a Gemini API..."
                
                val request = ChatbotRequest(
                    mensaje = message,
                    idSesion = _currentSession.value?.idSesion,
                    tipoIntento = determineIntent(message)
                )
                
                println("Request creado: $request")
                
                val response = chatbotService.sendMessage(request, finalUserProfile, finalCurrentRoutine)
                
                _apiStatus.value = ApiStatus.SUCCESS
                _diagnosticMessage.value = "✅ Respuesta recibida exitosamente"
                
                println("Respuesta recibida: ${response.respuesta}")
                
                // Agregar respuesta del bot
                val botMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    message = response.respuesta,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis(),
                    tipoIntento = response.tipoIntento,
                    tipoAccion = response.tipoAccion
                )
                
                val updatedMessages = _messages.value.toMutableList()
                updatedMessages.add(botMessage)
                _messages.value = updatedMessages
                
            } catch (e: Exception) {
                _apiStatus.value = ApiStatus.FAILED
                _diagnosticMessage.value = "❌ Error: ${e.message}"
                _error.value = "Error al enviar mensaje: ${e.message}"
                
                // Agregar mensaje de error
                val errorMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    message = "Lo siento, hubo un error al procesar tu mensaje. Por favor, inténtalo de nuevo.",
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                
                val updatedMessages = _messages.value.toMutableList()
                updatedMessages.add(errorMessage)
                _messages.value = updatedMessages
                
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun endSession() {
        viewModelScope.launch {
            try {
                _currentSession.value?.let { session ->
                    chatbotService.endSession(session.idSesion!!)
                }
                _currentSession.value = null
                _messages.value = emptyList()
                
                // Notificar que se cerró el chatbot para actualizar la rutina
                notifyRoutineUpdated()
            } catch (e: Exception) {
                _error.value = "Error al cerrar sesión: ${e.message}"
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
    
    fun clearMessages() {
        _messages.value = emptyList()
    }
    
    fun addWelcomeMessage() {
        val welcomeMessage = ChatMessage(
            id = "welcome_${System.currentTimeMillis()}",
            message = "¡Hola! Soy NutriAI, tu asistente nutricional personal. Estoy aquí para ayudarte con tu rutina alimentaria y responder tus preguntas sobre nutrición. ¿En qué puedo ayudarte hoy?",
            isFromUser = false,
            timestamp = System.currentTimeMillis()
        )
        
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(welcomeMessage)
        _messages.value = currentMessages
    }
    
    // Métodos para gestión de rutina
    fun addFoodToRoutine(foodName: String, mealTime: String, quantity: String? = null) {
        val modification = RoutineModification(
            action = ModificationAction.ADD,
            foodName = foodName,
            mealTime = mealTime,
            quantity = quantity
        )
        
        val currentHistory = _modificationHistory.value.toMutableList()
        currentHistory.add(modification)
        _modificationHistory.value = currentHistory
        
        // Aquí se podría actualizar la rutina real si se tiene acceso al backend
        println("✅ Alimento agregado: $foodName a $mealTime")
    }
    
    fun removeFoodFromRoutine(foodName: String, mealTime: String) {
        val modification = RoutineModification(
            action = ModificationAction.REMOVE,
            foodName = foodName,
            mealTime = mealTime
        )
        
        val currentHistory = _modificationHistory.value.toMutableList()
        currentHistory.add(modification)
        _modificationHistory.value = currentHistory
        
        println("✅ Alimento eliminado: $foodName de $mealTime")
    }
    
    fun changeFoodInRoutine(originalFood: String, newFood: String, mealTime: String) {
        val modification = RoutineModification(
            action = ModificationAction.CHANGE,
            foodName = newFood,
            mealTime = mealTime,
            originalFood = originalFood
        )
        
        val currentHistory = _modificationHistory.value.toMutableList()
        currentHistory.add(modification)
        _modificationHistory.value = currentHistory
        
        println("✅ Alimento cambiado: $originalFood por $newFood en $mealTime")
    }
    
    fun getModificationHistory(): List<RoutineModification> {
        return _modificationHistory.value
    }
    
    fun clearModificationHistory() {
        _modificationHistory.value = emptyList()
    }
    
    fun updateCurrentRoutine(routine: List<RegistroAlimentoSalida>) {
        _currentRoutine.value = routine
    }
    
    fun notifyRoutineUpdated() {
        _routineUpdated.value = true
    }
    
    fun clearRoutineUpdateNotification() {
        _routineUpdated.value = false
    }
    
    fun updateUserProfile(userProfile: Usuario?) {
        _userProfile.value = userProfile
        println("=== USER PROFILE UPDATED IN CHATBOT VIEWMODEL ===")
        println("UserProfile: $userProfile")
        println("Nombre: ${userProfile?.nombre}")
    }
    
    private suspend fun loadUserProfile(userId: Long) {
        try {
            // Aquí se podría cargar el perfil del usuario desde el backend
            // Por ahora, creamos un perfil básico
            val basicProfile = Usuario(
                idUsuario = userId,
                nombre = "Usuario", // Se actualizará cuando se cargue desde la pantalla
                correo = "",
                contrasena = "",
                fechaNacimiento = "",
                altura = 0f,
                peso = 0f,
                sexo = "",
                pesoObjetivo = 0f,
                restriccionesDieta = "",
                objetivosSalud = "",
                nivelActividad = ""
            )
            _userProfile.value = basicProfile
            println("=== PERFIL BÁSICO CARGADO EN CHATBOT VIEWMODEL ===")
            println("UserProfile: $basicProfile")
        } catch (e: Exception) {
            println("Error cargando perfil del usuario: ${e.message}")
        }
    }
    
    // Método para procesar modificaciones de rutina desde el chatbot
    fun processRoutineModification(
        originalFood: String?,
        newFood: String,
        mealTime: String,
        action: ModificationAction
    ) {
        when (action) {
            ModificationAction.ADD -> {
                addFoodToRoutine(newFood, mealTime)
            }
            ModificationAction.REMOVE -> {
                removeFoodFromRoutine(originalFood ?: newFood, mealTime)
            }
            ModificationAction.CHANGE -> {
                if (originalFood != null) {
                    changeFoodInRoutine(originalFood, newFood, mealTime)
                }
            }
            ModificationAction.VIEW_ROUTINE -> {
                // Solo para visualización, no requiere acción
            }
        }
    }
    
    
    // Métodos de diagnóstico de API
    private fun checkApiKeyStatus(): ApiStatus {
        return try {
            val config = com.example.frontendproyectoapp.config.GeminiConfig
            if (!config.isValid()) {
                ApiStatus.API_KEY_INVALID
            } else {
                ApiStatus.SUCCESS
            }
        } catch (e: Exception) {
            ApiStatus.API_KEY_INVALID
        }
    }
    
    private fun getApiStatusMessage(status: ApiStatus): String {
        return when (status) {
            ApiStatus.API_KEY_INVALID -> "❌ API Key inválida o no configurada"
            ApiStatus.API_KEY_EXPIRED -> "⏰ API Key expirada"
            ApiStatus.NETWORK_ERROR -> "🌐 Error de conexión a internet"
            ApiStatus.RATE_LIMIT_EXCEEDED -> "⏱️ Límite de solicitudes excedido"
            ApiStatus.QUOTA_EXCEEDED -> "📊 Cuota de API excedida"
            ApiStatus.SERVER_ERROR -> "🔧 Error del servidor de Gemini"
            ApiStatus.TIMEOUT -> "⏰ Tiempo de espera agotado"
            ApiStatus.CONNECTING -> "🔍 Conectando con Gemini API..."
            ApiStatus.SUCCESS -> "✅ Conexión exitosa"
            else -> "❓ Estado desconocido"
        }
    }
    
    fun getDetailedApiStatus(): String {
        val status = _apiStatus.value
        val message = _diagnosticMessage.value
        return "Estado: ${getApiStatusMessage(status)}\nDetalles: $message"
    }
    
    private fun determineIntent(message: String): TipoIntento {
        val lowerMessage = message.lowercase()
        
        return when {
            // Detección para generar rutina personalizada
            lowerMessage.contains("generar rutina") || lowerMessage.contains("generar rutina personalizada") -> TipoIntento.Modificar_Rutina
            
            // Detección mejorada para gestión de rutina
            lowerMessage.contains("agregar") || lowerMessage.contains("añadir") || 
            lowerMessage.contains("incluir") || lowerMessage.contains("poner") -> TipoIntento.Modificar_Rutina
            lowerMessage.contains("eliminar") || lowerMessage.contains("quitar") ||
            lowerMessage.contains("remover") || lowerMessage.contains("sacar") -> TipoIntento.Modificar_Rutina
            lowerMessage.contains("cambiar") || lowerMessage.contains("modificar") ||
            lowerMessage.contains("rutina") || lowerMessage.contains("intercambiar") -> TipoIntento.Modificar_Rutina
            lowerMessage.contains("ver rutina") || lowerMessage.contains("mostrar rutina") ||
            lowerMessage.contains("mi rutina") || lowerMessage.contains("rutina de hoy") ||
            lowerMessage.contains("mostrar rutina nutricional") -> TipoIntento.Modificar_Rutina
            lowerMessage.contains("rutina del") || lowerMessage.contains("rutina de") ||
            lowerMessage.contains("mostrar rutina del") || lowerMessage.contains("ver rutina del") -> TipoIntento.Modificar_Rutina
            
            // Detección mejorada para preguntas nutricionales
            lowerMessage.contains("calorías") || lowerMessage.contains("nutricional") ||
            lowerMessage.contains("proteína") || lowerMessage.contains("carbohidrato") ||
            lowerMessage.contains("grasa") || lowerMessage.contains("vitamina") ||
            lowerMessage.contains("mineral") || lowerMessage.contains("nutriente") -> TipoIntento.Pregunta_Nutricional
            lowerMessage.contains("desayuno") || lowerMessage.contains("almuerzo") ||
            lowerMessage.contains("cena") || lowerMessage.contains("snack") ||
            lowerMessage.contains("comida") || lowerMessage.contains("aliment") -> TipoIntento.Pregunta_Nutricional
            lowerMessage.contains("dieta") || lowerMessage.contains("peso") ||
            lowerMessage.contains("salud") || lowerMessage.contains("nutrición") -> TipoIntento.Pregunta_Nutricional
            
            else -> TipoIntento.Otros
        }
    }
}

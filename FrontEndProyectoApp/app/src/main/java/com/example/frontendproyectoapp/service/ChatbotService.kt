package com.example.frontendproyectoapp.service

import com.example.frontendproyectoapp.model.*
import com.example.frontendproyectoapp.repository.AlimentoRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import java.util.concurrent.TimeUnit

class ChatbotService {
    
    private val geminiService = GeminiNutriAIService()
    private val repository = AlimentoRepository()
    
    // Callback para notificar cambios en la rutina
    private var onRoutineUpdated: (() -> Unit)? = null
    
    fun setOnRoutineUpdatedCallback(callback: () -> Unit) {
        onRoutineUpdated = callback
    }
    
    // Conversation state management for NutriAI flow
    private val conversationStates = mutableMapOf<Long, ConversationState>()
    
    // Historial de rutinas generadas para evitar repeticiones
    private val routineHistory = mutableMapOf<Long, MutableList<String>>()
    
    // Función helper para detectar valores por defecto
    private fun isDefaultValue(value: String): Boolean {
        return value.contains("Usuario") || value.isBlank() || value == "0.0" || value == "0"
    }
    
    data class ConversationState(
        val userId: Long,
        val currentStep: NutriAIStep,
        val foodName: String? = null,
        val quantity: String? = null,
        val unit: String? = null,
        val mealTime: String? = null,
        val originalFood: String? = null,
        val newFood: String? = null,
        val routineCount: Int = 0, // Contador de rutinas generadas
        // Nuevos campos para el flujo de categorías
        val availableCategories: List<String>? = null,
        val selectedCategory: String? = null,
        val availableFoods: List<Alimento>? = null,
        val selectedFood: Alimento? = null,
        // Unidades válidas del alimento seleccionado (desde unidad_equivalencia)
        val validUnits: List<String>? = null,
        // Alimentos actuales de la rutina para cambiar
        val currentRoutineFoods: List<RegistroAlimentoSalida>? = null
    )
    
    enum class NutriAIStep {
        IDLE,
        ADD_FOOD_NAME,
        ADD_FOOD_QUANTITY,
        ADD_FOOD_UNIT,
        ADD_FOOD_MEAL_TIME,
        ADD_FOOD_CONFIRMATION,
        CHANGE_ORIGINAL_FOOD,
        CHANGE_NEW_FOOD,
        CHANGE_QUANTITY,
        CHANGE_UNIT,
        CHANGE_MEAL_TIME,
        CHANGE_CONFIRMATION,
        // Nuevos estados para el flujo de cambio por categorías
        CHANGE_SHOW_CATEGORIES,
        CHANGE_SHOW_CURRENT_FOOD,
        CHANGE_SELECT_ORIGINAL_FOOD,
        CHANGE_SELECT_CATEGORY,
        CHANGE_SHOW_FOODS,
        CHANGE_SELECT_FOOD,
        CHANGE_SELECT_FOOD_QUANTITY,
        CHANGE_SELECT_MEAL_TIME,
        CHANGE_SELECT_UNIT,
        CHANGE_CONFIRMATION_NEW,
        // Nuevos estados para el flujo de agregar por categorías
        ADD_SHOW_CATEGORIES,
        ADD_SELECT_CATEGORY,
        ADD_SHOW_FOODS,
        ADD_SELECT_FOOD,
        ADD_SELECT_FOOD_QUANTITY,
        ADD_SELECT_MEAL_TIME,
        ADD_SELECT_UNIT,
        ADD_CONFIRMATION
    }
    
    private val baseUrl = "http://localhost:8080/api" // Para desarrollo local
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()
    
    suspend fun createSession(userId: Long = 1L): SesionChatbot = withContext(Dispatchers.IO) {
        println("=== CREANDO SESIÓN LOCAL ===")
        println("ID Usuario para sesión: $userId")
        // Crear sesión local sin depender del backend
        val session = SesionChatbot(
            idSesion = System.currentTimeMillis(),
            inicioSesion = Timestamp(System.currentTimeMillis()),
            idUsuario = userId
        )
        println("✅ Sesión creada localmente: ${session.idSesion} para usuario: $userId")
        session
    }
    
    suspend fun sendMessage(
        request: ChatbotRequest, 
        userProfile: Usuario? = null,
        currentRoutine: List<RegistroAlimentoSalida>? = null
    ): ChatbotResponse = withContext(Dispatchers.IO) {
        println("=== CHATBOT SERVICE ===")
        println("Mensaje: ${request.mensaje}")
        println("Tipo intento: ${request.tipoIntento}")
        println("Usuario: ${userProfile?.nombre}")
        println("CurrentRoutine recibido: ${currentRoutine?.size} elementos")
        println("CurrentRoutine detalle: ${currentRoutine?.map { "${it.alimento.nombreAlimento} (${it.momentoDelDia})" }}")
        
        // Get or create conversation state
        val userId = userProfile?.idUsuario ?: 1L
        val currentState = conversationStates[userId] ?: ConversationState(userId, NutriAIStep.IDLE)
        
        // Check if we're in a conversation flow - PRIORITY OVER GEMINI
        if (currentState.currentStep != NutriAIStep.IDLE) {
            println("=== EN FLUJO DE CONVERSACIÓN - USANDO LÓGICA PASO A PASO ===")
            val flowResponse = handleConversationFlow(request.mensaje, currentState, userProfile, currentRoutine)
            if (flowResponse != null) {
                println("✅ Respuesta del flujo paso a paso: ${flowResponse.respuesta}")
                return@withContext flowResponse
            }
        }
        
        // Check for "generar rutina" command - HIGH PRIORITY
        val lowerMessage = request.mensaje.lowercase()
        if (lowerMessage.contains("generar rutina") || lowerMessage.contains("generar rutina personalizada")) {
            val userName = userProfile?.nombre?.ifBlank { "Usuario" } ?: "Usuario"
            val greeting = if (userName != "Usuario") "¡Perfecto $userName! 🎯" else "¡Perfecto! 🎯"
            
            return@withContext ChatbotResponse(
                respuesta = "$greeting Te ayudo a crear una rutina nutricional personalizada basada en tu perfil actual.\n\n" +
                            generateUserProfileDisplay(userProfile) + "\n\n" +
                            "💡 Responde:\n" +
                            "✳️ \"Sí\" o \"Generar\" para crear tu rutina personalizada\n" +
                            "❌ \"No\" para cancelar",
                tipoIntento = TipoIntento.Modificar_Rutina,
                tipoAccion = TipoAccion.Agregar
            )
        }
        
        // Check for confirmation to generate routine - HIGH PRIORITY
        if (lowerMessage == "generar" || lowerMessage == "sí" || lowerMessage == "si" || 
            (lowerMessage.contains("sí") && lowerMessage.contains("generar")) ||
            (lowerMessage.contains("si") && lowerMessage.contains("generar"))) {
            println("=== DETECTADO: Confirmación para generar rutina personalizada ===")
            println("=== LLAMANDO A generatePersonalizedRoutine ===")
            
            // Verificar si es la primera rutina o una subsecuente
            val isSubsequentRoutine = currentState.routineCount > 0
            val personalizedRoutine = withContext(Dispatchers.IO) {
                generatePersonalizedRoutine(userProfile, isSubsequentRoutine)
            }
            
            // Actualizar contador de rutinas
            conversationStates[userId] = currentState.copy(routineCount = currentState.routineCount + 1)
            
            println("=== RUTINA PERSONALIZADA GENERADA ===")
            println("Respuesta: ${personalizedRoutine.take(200)}...")
            return@withContext ChatbotResponse(
                respuesta = personalizedRoutine,
                tipoIntento = TipoIntento.Modificar_Rutina,
                tipoAccion = TipoAccion.Agregar
            )
        }
        
        // Check for "No" response to generate routine - HIGH PRIORITY
        if (lowerMessage == "no" && currentState.routineCount == 0) {
            println("=== DETECTADO: Usuario declina generar rutina personalizada ===")
            val userName = userProfile?.nombre?.ifBlank { "Usuario" } ?: "Usuario"
            val greeting = if (userName != "Usuario") "Entendido, $userName." else "Entendido."
            
            return@withContext ChatbotResponse(
                respuesta = "$greeting No hay problema. Si en algún momento quieres generar una rutina personalizada, solo dime 'Generar' y te ayudaré.",
                tipoIntento = TipoIntento.Otros,
                tipoAccion = null
            )
        }
        
        // Check for response to "generate another routine" question
        if (lowerMessage.contains("otra rutina") || lowerMessage.contains("rutina diferente") ||
            lowerMessage.contains("generar otra") || lowerMessage.contains("otra diferente")) {
            if (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("generar")) {
                println("=== DETECTADO: Usuario quiere generar otra rutina ===")
                
                // Es una rutina subsecuente
                val anotherRoutine = withContext(Dispatchers.IO) {
                    generatePersonalizedRoutine(userProfile, true)
                }
                
                // Actualizar contador de rutinas
                conversationStates[userId] = currentState.copy(routineCount = currentState.routineCount + 1)
                
                return@withContext ChatbotResponse(
                    respuesta = anotherRoutine,
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Agregar
                )
            } else if (lowerMessage.contains("no")) {
                println("=== DETECTADO: Usuario no quiere generar otra rutina ===")
                return@withContext ChatbotResponse(
                    respuesta = "Perfecto, no hay problema. Si en algún momento quieres generar una nueva rutina personalizada, solo dime 'Generar' y te ayudaré.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
        }
        
        // Check for "agregar" and "cambiar" commands - HIGH PRIORITY (but not during confirmation)
        if ((lowerMessage.contains("agregar") || lowerMessage.contains("añadir") || 
             lowerMessage.contains("agregar alimento") || lowerMessage.contains("añadir alimento")) && 
            currentState.currentStep != NutriAIStep.ADD_FOOD_CONFIRMATION) {
            val userId = userProfile?.idUsuario ?: 1L
            val userName = userProfile?.nombre ?: ""
            val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
            
            // Verificar si es el comando específico "agregar alimento" para usar el nuevo flujo
            if (lowerMessage.contains("agregar alimento") || lowerMessage.contains("añadir alimento")) {
                // Iniciar nuevo flujo con categorías
                try {
                    val categorias = repository.obtenerCategoriasUnicas()
                    if (categorias.isNotEmpty()) {
                        conversationStates[userId] = ConversationState(
                            userId = userId,
                            currentStep = NutriAIStep.ADD_SELECT_CATEGORY,
                            availableCategories = categorias
                        )
                        println("=== INICIANDO NUEVO FLUJO AGREGAR ALIMENTO CON CATEGORÍAS PARA USUARIO $userId ===")
                        
                        val categoriasTexto = categorias.joinToString(", ")
                        return@withContext ChatbotResponse(
                            respuesta = "$greeting ¡Perfecto! Te ayudo a agregar un alimento a tu rutina.\n\n" +
                                       "Puedes agregar alimentos de las siguientes categorías: **$categoriasTexto**\n\n" +
                                       "Por favor, selecciona una categoría.",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    } else {
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        return@withContext ChatbotResponse(
                            respuesta = "Lo siento, no hay categorías de alimentos disponibles en este momento.\n\n" +
                                       "¿Hay algo más en lo que pueda ayudarte?",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } catch (e: Exception) {
                    println("Error obteniendo categorías: ${e.message}")
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    return@withContext ChatbotResponse(
                        respuesta = "Lo siento, hubo un problema al obtener las categorías.\n\n" +
                                   "¿Hay algo más en lo que pueda ayudarte?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            } else {
                // Usar flujo original para otros comandos
                conversationStates[userId] = ConversationState(userId, NutriAIStep.ADD_FOOD_NAME)
                println("=== INICIANDO FLUJO AGREGAR ALIMENTO ORIGINAL PARA USUARIO $userId ===")
                
                return@withContext ChatbotResponse(
                    respuesta = "$greeting ¡Perfecto! Te ayudo a agregar un alimento a tu rutina.\n\n" +
                               "🥦 **¿Cuál es el nombre del alimento que quieres agregar?**\n" +
                               "(ejemplo: avena, pollo, arroz, quinoa, etc.)",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Agregar
                )
            }
        }
        
        if ((lowerMessage.contains("cambiar") || lowerMessage.contains("modificar") || 
            lowerMessage.contains("cambiar alimento") || lowerMessage.contains("modificar alimento") ||
            lowerMessage.contains("modifica tu rutina") || lowerMessage.contains("modificar rutina")) &&
            currentState.currentStep != NutriAIStep.CHANGE_CONFIRMATION) {
            val userId = userProfile?.idUsuario ?: 1L
            val userName = userProfile?.nombre ?: ""
            val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
            
            // Verificar si es el comando específico "cambiar alimento" para usar el nuevo flujo
            if (lowerMessage.contains("cambiar alimento") || lowerMessage.contains("modificar alimento")) {
                println("=== INICIANDO NUEVO FLUJO CAMBIAR ALIMENTO PARA USUARIO $userId ===")
                
                // Obtener TODOS los alimentos de la rutina actual
                val alimentosEnRutina = currentRoutine?.filter { it != null } ?: emptyList()
                
                if (alimentosEnRutina.isEmpty()) {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    return@withContext ChatbotResponse(
                        respuesta = "$greeting No tienes alimentos registrados actualmente en tu rutina.\n\n" +
                                   "¿Te gustaría agregar un alimento a tu rutina?",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                }
                
                // Mostrar todos los alimentos de la rutina para que el usuario seleccione cuál cambiar
                val alimentosTexto = alimentosEnRutina.joinToString("\n") { 
                    "• **${it.alimento.nombreAlimento}** - ${it.momentoDelDia}" 
                }
                
                conversationStates[userId] = ConversationState(
                    userId = userId,
                    currentStep = NutriAIStep.CHANGE_SELECT_ORIGINAL_FOOD,
                    currentRoutineFoods = alimentosEnRutina
                )
                
                return@withContext ChatbotResponse(
                    respuesta = "$greeting ¡Perfecto! Te ayudo a cambiar un alimento en tu rutina.\n\n" +
                               "Estos son los alimentos registrados en tu rutina actual:\n\n" +
                               "$alimentosTexto\n\n" +
                               "📝 **¿Qué alimento deseas cambiar?**\n" +
                               "(Escribe el nombre del alimento que quieres reemplazar)",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Modificar
                )
            } else {
                // Usar flujo original para otros comandos
                conversationStates[userId] = ConversationState(userId, NutriAIStep.CHANGE_ORIGINAL_FOOD)
                println("=== INICIANDO FLUJO CAMBIAR ALIMENTO ORIGINAL PARA USUARIO $userId ===")
                
                return@withContext ChatbotResponse(
                    respuesta = "$greeting ¡Perfecto! Te ayudo a cambiar un alimento en tu rutina.\n\n" +
                               "🔄 **¿Qué alimento de tu rutina actual quieres reemplazar?**\n" +
                               "(menciona el alimento que quieres cambiar)",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Modificar
                )
            }
        }
        
        // Verificar si es una solicitud de rutina - usar fallback directo
        val isRoutineRequest = lowerMessage.contains("mostrar rutina") || 
                              lowerMessage.contains("ver rutina") || 
                              lowerMessage.contains("mi rutina") || 
                              lowerMessage.contains("rutina de hoy") ||
                              lowerMessage.contains("rutina del") ||
                              lowerMessage.contains("rutina de")
        
         // Detectar si es una solicitud de rutina con fecha específica
         val datePattern = extractDateFromMessage(request.mensaje)
        
        // Validar si la fecha es realmente válida
        var isValidDate = false
        if (datePattern != null) {
            try {
                LocalDate.parse(datePattern, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                isValidDate = true
                println("✅ Fecha válida: $datePattern")
            } catch (e: DateTimeParseException) {
                println("❌ Fecha inválida: $datePattern - ${e.message}")
                isValidDate = false
            }
        }
         val isSpecificDateRoutine = lowerMessage.contains("ver rutina") && datePattern != null && isValidDate
         
         // Detectar formato "ver rutina YYYY-MM-DD" específico
         val isVerRutinaFormat = lowerMessage.matches(Regex("ver rutina \\d{4}-\\d{2}-\\d{2}"))
         
         println("=== DEBUGGING DETECCIÓN DE FECHA ESPECÍFICA ===")
         println("Mensaje: ${request.mensaje}")
         println("LowerMessage: $lowerMessage")
         println("DatePattern extraído: $datePattern")
         println("¿Contiene 'ver rutina'? ${lowerMessage.contains("ver rutina")}")
         println("¿DatePattern no es null? ${datePattern != null}")
         println("¿Es solicitud de fecha específica? $isSpecificDateRoutine")
         
         // Detectar fechas inválidas ANTES de enviar a Gemini
         if (datePattern != null && !isValidDate) {
             println("=== DETECTADO: Fecha inválida en sendMessage ===")
             val userName = userProfile?.nombre ?: ""
             val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
             val message = "$greeting\n\nEsa fecha parece contener un error. Para ver tu rutina en una fecha específica, escribe:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
             return@withContext ChatbotResponse(
                 respuesta = message,
                 tipoIntento = TipoIntento.Otros,
                 tipoAccion = null
             )
         }
         
         // Manejar formato "ver rutina YYYY-MM-DD" específico
         if (isVerRutinaFormat) {
             println("=== DETECTADO: Formato 'ver rutina YYYY-MM-DD' ===")
             val extractedDate = extractDateFromMessage(request.mensaje)
             if (extractedDate != null && isValidDate) {
                 val fallbackResponse = generateRoutineResponse(userProfile, currentRoutine, extractedDate)
                 return@withContext ChatbotResponse(
                     respuesta = fallbackResponse,
                     tipoIntento = request.tipoIntento ?: determineIntent(request.mensaje),
                     tipoAccion = determineAction(request.mensaje),
                     tema = "Rutina alimentaria"
                 )
             } else {
                 val userName = userProfile?.nombre ?: ""
                 val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                 val message = "$greeting\n\nLa fecha que ingresaste no es válida. Por favor, usa el formato YYYY-MM-DD.\n\n👉 **Ejemplo:** ver rutina 2025-10-05"
                 return@withContext ChatbotResponse(
                     respuesta = message,
                     tipoIntento = TipoIntento.Otros,
                     tipoAccion = null
                 )
             }
         }
         
         // Detectar fechas que parecen fechas pero no contienen "ver rutina"
         if (lowerMessage.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && !lowerMessage.contains("ver rutina")) {
             println("=== DETECTADO: Fecha sin 'ver rutina' en sendMessage ===")
             val userName = userProfile?.nombre ?: ""
             val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
             val message = "$greeting\n\nPara ver tu rutina en una fecha específica, escribe:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
             return@withContext ChatbotResponse(
                 respuesta = message,
                 tipoIntento = TipoIntento.Otros,
                 tipoAccion = null
             )
         }
         println("=== DEBUGGING USER PROFILE ===")
         println("UserProfile completo: $userProfile")
         println("UserProfile ID: ${userProfile?.idUsuario}")
         println("UserProfile nombre: '${userProfile?.nombre}'")
         println("UserProfile nombre vacío: ${userProfile?.nombre?.isBlank()}")
         println("UserProfile nombre nulo: ${userProfile?.nombre == null}")
        
        // Solo usar bypass directo para rutinas de "hoy", no para fechas específicas
        val isTodayRoutine = lowerMessage.contains("mi rutina") || 
                           lowerMessage.contains("rutina de hoy") ||
                           lowerMessage.contains("mostrar rutina nutricional") ||
                           (lowerMessage.contains("ver rutina") && !lowerMessage.contains("/") && !lowerMessage.contains("-"))
        
        if (isTodayRoutine) {
            println("=== DETECTADA SOLICITUD DE RUTINA DE HOY - USANDO FALLBACK DIRECTO ===")
            val fallbackResponse = generateSpecificResponse(request.mensaje, userProfile, currentRoutine)
            println("✅ Respuesta de fallback para rutina de hoy: $fallbackResponse")
            
            return@withContext ChatbotResponse(
                respuesta = fallbackResponse,
                tipoIntento = request.tipoIntento ?: determineIntent(request.mensaje),
                tipoAccion = determineAction(request.mensaje),
                tema = "Rutina alimentaria"
            )
        }
        
         if (isSpecificDateRoutine) {
             println("=== DETECTADA SOLICITUD DE RUTINA CON FECHA ESPECÍFICA - USANDO FALLBACK DIRECTO ===")
             println("DatePattern detectado: $datePattern")
             println("UserProfile ID: ${userProfile?.idUsuario}")
             val fallbackResponse = generateRoutineResponse(userProfile, currentRoutine, datePattern)
             println("✅ Respuesta de fallback para rutina de fecha específica: $fallbackResponse")
             
             return@withContext ChatbotResponse(
                 respuesta = fallbackResponse,
                 tipoIntento = request.tipoIntento ?: determineIntent(request.mensaje),
                 tipoAccion = determineAction(request.mensaje),
                 tema = "Rutina alimentaria"
             )
         }
        
        try {
            // Usar Gemini API para otras consultas
            println("=== USANDO GEMINI API ===")
            println("⚠️ ADVERTENCIA: Esto NO debería ejecutarse si se procesó 'agregar' correctamente")
            println("Mensaje a enviar a Gemini: ${request.mensaje}")
            println("API Key configurada: ${com.example.frontendproyectoapp.config.GeminiConfig.API_KEY.take(10)}...")
            println("¿API Key válida? ${com.example.frontendproyectoapp.config.GeminiConfig.isValid()}")
            
            val geminiResponse = geminiService.generateResponse(
                userMessage = request.mensaje,
                userProfile = userProfile,
                currentRoutine = currentRoutine
            )
            println("✅ Respuesta de Gemini recibida: $geminiResponse")
            
            ChatbotResponse(
                respuesta = geminiResponse,
                tipoIntento = request.tipoIntento ?: determineIntent(request.mensaje),
                tipoAccion = determineAction(request.mensaje),
                tema = "Rutina alimentaria"
            )
        } catch (geminiError: Exception) {
            println("=== ERROR EN GEMINI, USANDO FALLBACK ===")
            println("Tipo de error: ${geminiError.javaClass.simpleName}")
            println("Mensaje de error: ${geminiError.message}")
            println("Causa: ${geminiError.cause?.message}")
            println("Stack trace completo:")
            geminiError.printStackTrace()
            
            // Intentar diagnóstico automático
            try {
                println("=== EJECUTANDO DIAGNÓSTICO AUTOMÁTICO ===")
                val diagnostics = geminiService.diagnoseApiIssue()
                println("Resultado del diagnóstico: $diagnostics")
            } catch (e: Exception) {
                println("Error en diagnóstico: ${e.message}")
            }
            
            // Fallback a respuestas específicas si Gemini falla
            val fallbackResponse = generateSpecificResponse(request.mensaje, userProfile, currentRoutine)
            println("✅ Respuesta de fallback: $fallbackResponse")
            
            ChatbotResponse(
                respuesta = fallbackResponse,
                tipoIntento = request.tipoIntento ?: determineIntent(request.mensaje),
                tipoAccion = determineAction(request.mensaje),
                tema = "Rutina alimentaria"
            )
        }
    }
    
    private suspend fun saveInteraction(interaccion: InteraccionChatbot) {
        // Deshabilitado - no guardar en backend local
        println("📝 Interacción registrada localmente: ${interaccion.consultaUsuario}")
    }
    
    suspend fun endSession(sessionId: Long) = withContext(Dispatchers.IO) {
        println("🔚 Sesión finalizada localmente: $sessionId")
        // Limpiar estados de conversación para evitar respuestas duplicadas
        conversationStates.clear()
        // Limpiar historial de rutinas
        routineHistory.clear()
        println("🧹 Estados de conversación y historial de rutinas limpiados")
        // No intentar conectar al backend local
    }
    
    // Función de test para verificar que el chatbot funcione
    suspend fun testChatbotResponse(): String = withContext(Dispatchers.IO) {
        println("=== TESTING CHATBOT RESPONSE ===")
        val testMessage = "Responder preguntas de nutrición"
        val testUser = Usuario(
            idUsuario = 1L,
            nombre = "Test User",
            correo = "test@test.com",
            contrasena = "password",
            peso = 70.0f,
            altura = 170f,
            fechaNacimiento = "1990-01-01",
            sexo = "M",
            pesoObjetivo = 75.0f,
            restriccionesDieta = "Ninguna",
            nivelActividad = "Moderado",
            objetivosSalud = "Ganar peso"
        )
        
        // Test directo de Gemini
        try {
            println("=== TESTING GEMINI DIRECTLY ===")
            println("Mensaje de prueba: $testMessage")
            println("Configuración actual:")
            println("  - API Key: ${com.example.frontendproyectoapp.config.GeminiConfig.API_KEY.take(10)}...")
            println("  - Modelo: ${com.example.frontendproyectoapp.config.GeminiConfig.MODEL_NAME}")
            println("  - Base URL: ${com.example.frontendproyectoapp.config.GeminiConfig.BASE_URL}")
            println("  - ¿API Key válida? ${com.example.frontendproyectoapp.config.GeminiConfig.isValid()}")
            
            val geminiResponse = geminiService.generateResponse(testMessage, testUser, null)
            println("✅ Gemini test exitoso: $geminiResponse")
            return@withContext geminiResponse
        } catch (e: Exception) {
            println("❌ Gemini test falló: ${e.message}")
            println("Tipo de error: ${e.javaClass.simpleName}")
            e.printStackTrace()
            
            // Intentar diagnóstico
            try {
                println("=== EJECUTANDO DIAGNÓSTICO ===")
                val diagnostics = geminiService.diagnoseApiIssue()
                println("Diagnóstico: $diagnostics")
            } catch (diagError: Exception) {
                println("Error en diagnóstico: ${diagError.message}")
            }
        }
        
        // Fallback a respuesta específica
        val response = generateSpecificResponse(testMessage, testUser, null)
        println("Test response (fallback): $response")
        response
    }
    
    private suspend fun generateSpecificResponse(message: String, userProfile: Usuario? = null, currentRoutine: List<RegistroAlimentoSalida>? = null): String {
        val lowerMessage = message.lowercase()
        
        println("=== GENERANDO RESPUESTA ESPECÍFICA ===")
        println("Mensaje original: '$message'")
        println("Mensaje en minúsculas: '$lowerMessage'")
        println("UserProfile: $userProfile")
        println("Nombre del usuario: ${userProfile?.nombre}")
        println("¿Contiene 'responder preguntas de nutrición'? ${lowerMessage.contains("responder preguntas de nutrición")}")
        println("¿Contiene 'nutrición'? ${lowerMessage.contains("nutrición")}")
        
        // Extraer y validar fecha en generateSpecificResponse
        val datePattern = extractDateFromMessage(message)
        var isValidDate = false
        if (datePattern != null) {
            try {
                LocalDate.parse(datePattern, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                isValidDate = true
                println("✅ Fecha válida: $datePattern")
            } catch (e: DateTimeParseException) {
                println("❌ Fecha inválida: $datePattern - ${e.message}")
                isValidDate = false
            }
        }
        
        println("=== DEBUGGING DETECCIÓN EN generateSpecificResponse ===")
        println("Mensaje: $message")
        println("DatePattern: $datePattern")
        println("IsValidDate: $isValidDate")
        println("LowerMessage: $lowerMessage")
        println("¿Contiene 'ver rurina'? ${lowerMessage.contains("ver rurina")}")
        println("¿Contiene 'ver rutina'? ${lowerMessage.contains("ver rutina")}")
        println("¿Es fecha sin 'ver rutina'? ${lowerMessage.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && !lowerMessage.contains("ver rutina")}")
        
        return when {
            
            // Detectar fechas inválidas y mostrar mensaje de ejemplo (DEBE IR PRIMERO)
            datePattern != null && !isValidDate -> {
                println("=== DETECTADO: Fecha inválida ===")
                println("Fecha detectada: $datePattern")
                println("¿Es válida? $isValidDate")
                val userName = userProfile?.nombre ?: ""
                val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                "$greeting\n\nEsa fecha parece contener un error. Para ver tu rutina en una fecha específica, escribe:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
            }
            
            // Detectar "ver rurina" (con error tipográfico)
            lowerMessage.contains("ver rurina") -> {
                println("=== DETECTADO: 'ver rurina' con error tipográfico ===")
                println("Mensaje: $message")
                val userName = userProfile?.nombre ?: ""
                val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                "$greeting\n\nPara ver tu rutina en una fecha específica, escribe:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
            }
            
            // Detectar fechas que parecen fechas pero no contienen "ver rutina"
            lowerMessage.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && !lowerMessage.contains("ver rutina") -> {
                println("=== DETECTADO: Fecha sin 'ver rutina' ===")
                println("Mensaje: $message")
                val userName = userProfile?.nombre ?: ""
                val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                "$greeting\n\nPara ver tu rutina en una fecha específica, escribe:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
            }
            
            // Detectar fechas o entradas erróneas sin formato correcto
            lowerMessage.matches(Regex("\\d{2}/\\d{2}/\\d{4}")) ||
            lowerMessage.matches(Regex("\\d{2}-\\d{2}-\\d{4}")) ||
            (lowerMessage.length <= 10 && lowerMessage.any { it.isDigit() }) -> {
                println("=== DETECTADO: Fecha errónea o entrada incorrecta ===")
                val userName = userProfile?.nombre ?: ""
                val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                "$greeting\n\nPara ver tu rutina en una fecha específica, escribe:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
            }
            
            // Arroz y granos
            lowerMessage.contains("arroz integral") || lowerMessage.contains("arroz") -> 
                "¡Sí, el arroz integral es excelente! Es mucho mejor que el arroz blanco porque conserva la fibra, vitaminas y minerales. Tiene más nutrientes, te da energía sostenida y ayuda con la digestión. Es una excelente fuente de carbohidratos complejos. ¿Te gustaría saber cómo incluirlo en tus comidas?"
            
            lowerMessage.contains("quinoa") -> 
                "La quinoa es un superalimento completo. Tiene proteínas de alta calidad, fibra, vitaminas y minerales. Es perfecta para vegetarianos y veganos. ¿Te interesa saber cómo prepararla?"
            
            lowerMessage.contains("avena") || lowerMessage.contains("oatmeal") -> 
                "La avena es fantástica para el desayuno. Tiene fibra soluble que ayuda a controlar el colesterol y te da energía duradera. Es rica en proteínas y te mantiene saciado. ¿Quieres ideas de cómo prepararla?"
            
            // Carbohidratos
            lowerMessage.contains("carbohidratos") || lowerMessage.contains("carbohidrato") -> 
                "Los carbohidratos son la principal fuente de energía para tu cuerpo. Se dividen en simples (azúcares) y complejos (almidones). Los carbohidratos complejos como arroz integral, avena y quinoa son mejores porque te dan energía sostenida. ¿Te gustaría saber más sobre cómo incluirlos en tu dieta?"
            
            // Proteínas
            lowerMessage.contains("proteínas") || lowerMessage.contains("proteína") -> 
                "Las proteínas son esenciales para construir y reparar músculos. Las encuentras en carnes, pescados, huevos, legumbres y lácteos. Para una dieta balanceada, incluye proteína en cada comida. ¿Necesitas sugerencias de fuentes de proteína específicas?"
            
            // Grasas
            lowerMessage.contains("grasas") || lowerMessage.contains("grasa") -> 
                "Las grasas son importantes para tu salud, especialmente las grasas buenas como aguacate, nueces, aceite de oliva y pescados grasos. Evita las grasas trans y consume grasas saturadas con moderación. ¿Quieres saber qué grasas incluir en tu dieta?"
            
            // Calorías
            lowerMessage.contains("calorías") || lowerMessage.contains("caloría") -> 
                "Las calorías son la energía que necesita tu cuerpo. Para mantener un peso saludable, necesitas equilibrar las calorías que consumes con las que gastas. ¿Te gustaría que te ayude a calcular tus necesidades calóricas?"
            
            // Comidas específicas
            lowerMessage.contains("desayuno") -> 
                "El desayuno es muy importante para empezar el día con energía. Un buen desayuno incluye proteínas, carbohidratos complejos y algo de grasa saludable. ¿Te gustaría sugerencias específicas para tu desayuno?"
            
            lowerMessage.contains("almuerzo") -> 
                "El almuerzo debe ser balanceado con proteínas, carbohidratos y verduras. Es la comida principal del día, así que asegúrate de incluir todos los macronutrientes. ¿Necesitas ideas para tu almuerzo?"
            
            lowerMessage.contains("cena") -> 
                "La cena debe ser más ligera que el almuerzo. Incluye proteínas magras con verduras y una porción moderada de carbohidratos. Evita comidas muy pesadas antes de dormir. ¿Qué te gustaría cenar hoy?"
            
            // Hidratación
            lowerMessage.contains("agua") || lowerMessage.contains("hidratación") -> 
                "El agua es esencial para tu cuerpo. Se recomienda beber al menos 8 vasos de agua al día, más si haces ejercicio. ¿Estás bebiendo suficiente agua durante el día?"
            
            // Frutas y verduras
            lowerMessage.contains("frutas") || lowerMessage.contains("fruta") -> 
                "Las frutas son excelentes fuentes de vitaminas, minerales y fibra. Son naturales, bajas en calorías y te dan energía. ¿Te gustaría saber cuáles son las mejores frutas para incluir en tu dieta?"
            
            lowerMessage.contains("verduras") || lowerMessage.contains("vegetales") -> 
                "Las verduras son fundamentales para una dieta saludable. Tienen pocas calorías, mucha fibra, vitaminas y minerales. ¿Quieres saber cómo incluir más verduras en tus comidas?"
            
            // Masa muscular y fitness
            lowerMessage.contains("masa muscular") || lowerMessage.contains("ganar músculo") || lowerMessage.contains("músculo") -> 
                "Para ganar masa muscular necesitas un excedente calórico y suficiente proteína. Te recomiendo: 1.6-2.2g de proteína por kg de peso, carbohidratos para energía, y entrenamiento de fuerza. ¿Te gustaría un plan específico de alimentación para ganar músculo?"
            
            lowerMessage.contains("perder peso") || lowerMessage.contains("adelgazar") || lowerMessage.contains("bajar peso") -> 
                "Para perder peso de forma saludable necesitas un déficit calórico moderado (300-500 calorías menos al día), proteína suficiente para mantener músculo, y ejercicio regular. ¿Quieres que te ayude con un plan específico?"
            
            lowerMessage.contains("dieta") || lowerMessage.contains("alimentación") -> 
                "Una dieta equilibrada incluye proteínas, carbohidratos complejos, grasas saludables, frutas y verduras. ¿Tienes algún objetivo específico como ganar músculo, perder peso, o mantener tu peso actual?"
            
            // Preguntas sobre alimentos específicos
            lowerMessage.contains("bueno") || lowerMessage.contains("buena") || lowerMessage.contains("es bueno") -> 
                "Para darte una respuesta específica sobre si algo es bueno, necesito saber de qué alimento hablas. ¿Podrías ser más específico? Por ejemplo: '¿el aguacate es bueno?' o '¿la avena es buena?'"
            
            // Detectar solo fecha (sin palabras de rutina) - formato DD/MM/YYYY
            Regex("^\\d{1,2}/\\d{1,2}/\\d{4}$").matches(message.trim()) -> {
                println("=== DETECTADA SOLO FECHA (SIN PALABRAS RUTINA) ===")
                println("Mensaje: $message")
                val datePattern = extractDateFromMessage(message)
                println("Fecha extraída: $datePattern")
                if (datePattern != null) {
                    println("✅ Generando rutina para fecha específica: $datePattern")
                    generateRoutineResponse(userProfile, currentRoutine, datePattern)
                } else {
                    val userName = userProfile?.nombre ?: ""
                    val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                    "$greeting Para mostrarte la rutina de una fecha específica, necesito que me indiques la fecha en formato DD/MM/YYYY.\n\n" +
                    "📅 **Ejemplo de formato de fecha:**\n" +
                    "• \"05/10/2025\"\n\n" +
                    "💡 **Formato requerido:** DD/MM/YYYY (día/mes/año)\n\n" +
                    "¿De qué fecha te gustaría ver la rutina? (ejemplo: 05/10/2025)"
                }
            }
            
            // Rutina de fecha específica - detectar cuando hay fecha en el mensaje
            (lowerMessage.contains("rutina del") || lowerMessage.contains("rutina de") ||
            lowerMessage.contains("mostrar rutina del") || lowerMessage.contains("ver rutina del") ||
            lowerMessage.contains("ver rutina") || lowerMessage.contains("mostrar rutina")) &&
            (lowerMessage.contains("/") || lowerMessage.contains("-") || 
             lowerMessage.contains("ayer") || lowerMessage.contains("hoy") || lowerMessage.contains("mañana")) -> {
                println("=== DETECTADA SOLICITUD DE RUTINA CON FECHA ===")
                println("Mensaje: $message")
                val datePattern = extractDateFromMessage(message)
                println("Fecha extraída: $datePattern")
                if (datePattern != null) {
                    println("✅ Generando rutina para fecha específica: $datePattern")
                    generateRoutineResponse(userProfile, currentRoutine, datePattern)
                } else {
                    val userName = userProfile?.nombre ?: ""
                    val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                    "$greeting Para mostrarte la rutina de una fecha específica, necesito que me indiques la fecha en formato DD/MM/YYYY.\n\n" +
                    "📅 **Ejemplo de formato de fecha:**\n" +
                    "• \"05/10/2025\"\n\n" +
                    "💡 **Formato requerido:** DD/MM/YYYY (día/mes/año)\n\n" +
                    "¿De qué fecha te gustaría ver la rutina? (ejemplo: 05/10/2025)"
                }
            }
            
             // Gestión de rutina nutricional SIN fecha específica (solo "hoy")
             lowerMessage.contains("mi rutina") || lowerMessage.contains("rutina de hoy") ||
             lowerMessage.contains("mostrar rutina nutricional") ||
             (lowerMessage.contains("ver rutina") && !lowerMessage.contains("/") && !lowerMessage.contains("-")) -> {
                 println("=== DETECTADO: Mostrar rutina nutricional ===")
                 println("UserProfile recibido: $userProfile")
                 println("Nombre del usuario: ${userProfile?.nombre}")
                 // generateRoutineResponse ya incluye las opciones cuando es rutina de hoy
                 generateRoutineResponse(userProfile, currentRoutine, null)
             }
            
            
            // Manejar confirmaciones de cambios
            (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("confirmo") || 
             lowerMessage.contains("confirmar")) && lowerMessage.contains("cambio") -> {
                // Aquí se podría integrar con el ChatbotViewModel para registrar el cambio
                // Por ahora, solo mostramos el mensaje de confirmación
                "¡Perfecto! He registrado tu cambio en la rutina.\n\n" +
                "✅ **Cambio aplicado exitosamente**\n\n" +
                "💡 **Nota:** Para que los cambios se reflejen en tu rutina, asegúrate de actualizar la pantalla de rutina.\n\n" +
                "¿Te gustaría hacer algún otro cambio en tu rutina o necesitas ayuda con algo más?"
            }
            
            
            
            // Manejar cancelaciones de cambios
            (lowerMessage.contains("no") || lowerMessage.contains("cancelar") || lowerMessage.contains("cancel")) && 
            lowerMessage.contains("cambio") -> {
                "Entendido, no se realizará ningún cambio.\n\n" +
                "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?"
            }
            
            // Manejar solicitudes completas de modificación con todos los datos
            isCompleteModificationRequest(message) -> {
                
                val modificationData = parseModificationRequest(message)
                if (modificationData != null) {
                    "¡Perfecto! Entiendo que quieres:\n\n" +
                    "🔄 **Modificación solicitada:**\n" +
                    "• **Acción:** ${modificationData.action}\n" +
                    "• **Alimento:** ${modificationData.foodName}\n" +
                    "• **Momento:** ${modificationData.mealTime}\n" +
                    "• **Cantidad:** ${modificationData.quantity}\n" +
                    "• **Unidad:** ${modificationData.unit}\n\n" +
                    "¿Confirmas este cambio? (Responde 'sí' para proceder o 'no' para cancelar)"
                } else {
                    "No pude entender completamente tu solicitud de modificación.\n\n" +
                    "Por favor, asegúrate de incluir:\n" +
                    "• El nombre del alimento\n" +
                    "• El momento del día\n" +
                    "• La cantidad y unidad\n\n" +
                    "💡 **Ejemplo:** \"Quiero agregar 100 gramos de avena al desayuno\""
                }
            }
            
            
            // Mensaje predeterminado para responder preguntas de nutrición (DEBE IR PRIMERO)
            lowerMessage.contains("responder preguntas de nutrición") -> {
                println("=== DETECTADO: Responder preguntas de nutrición ===")
                println("UserProfile completo: $userProfile")
                val userName = userProfile?.nombre ?: ""
                println("Nombre del usuario: '$userName'")
                println("¿Nombre está vacío? ${userName.isBlank()}")
                println("¿Nombre es 'Usuario'? ${userName == "Usuario"}")
                val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                val response = "$greeting Soy NutriAI, tu asistente de nutrición.\n\nEstoy aquí para resolver todas tus dudas sobre alimentación saludable, dietas, control de peso, suplementos y mucho más.\n\n¿Qué tema te gustaría consultar hoy?"
                println("Respuesta generada: $response")
                response
            }
            
        // Detectar fechas o entradas erróneas y mostrar mensaje de ejemplo (DEBE IR ANTES DE GEMINI)
        lowerMessage.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || 
        lowerMessage.matches(Regex("\\d{2}/\\d{2}/\\d{4}")) ||
        lowerMessage.matches(Regex("\\d{2}-\\d{2}-\\d{4}")) ||
        (lowerMessage.length <= 10 && lowerMessage.any { it.isDigit() }) -> {
            println("=== DETECTADO: Fecha errónea o entrada incorrecta ===")
            val userName = userProfile?.nombre ?: ""
            val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
            "$greeting Para consultar tu rutina nutricional, usa el formato correcto:\n\n" +
            "📅 **¿Quieres ver la rutina de otra fecha?**\n" +
            "Escribe: \"Ver rutina 2025-10-01\" (formato: YYYY-MM-DD)"
        }
            
            // Preguntas generales de nutrición (DESPUÉS de la condición específica)
            lowerMessage.contains("nutrición") || lowerMessage.contains("alimentación saludable") -> 
                "La nutrición es fundamental para tu salud. Una alimentación balanceada incluye todos los macronutrientes: proteínas para músculos, carbohidratos para energía, y grasas saludables. ¿Hay algún aspecto específico que te interese?"
            
            lowerMessage.contains("vitaminas") || lowerMessage.contains("minerales") -> 
                "Las vitaminas y minerales son micronutrientes esenciales. Las frutas y verduras son las mejores fuentes. ¿Te gustaría saber sobre alguna vitamina específica o cómo obtener más micronutrientes?"
            
            else -> {
                println("No se encontró coincidencia específica, usando respuesta genérica")
                "Entiendo tu consulta. Como NutriAI, puedo ayudarte con información sobre nutrición, macronutrientes, planificación de comidas y consejos para una alimentación saludable. ¿Hay algo específico sobre nutrición que te gustaría saber?"
            }
        }
    }
    
    private fun generateBotResponse(message: String, intent: TipoIntento?): String {
        val lowerMessage = message.lowercase()
        
        return when (intent) {
            TipoIntento.Modificar_Rutina -> {
                when {
                    lowerMessage.contains("agregar") || lowerMessage.contains("añadir") || 
                    lowerMessage.contains("agregar alimento") || lowerMessage.contains("añadir alimento") -> 
                        "¡Perfecto! Te ayudo a agregar alimentos a tu rutina. ¿Qué alimento te gustaría agregar y en qué momento del día (desayuno, almuerzo, cena, snack)?"
                    lowerMessage.contains("eliminar") || lowerMessage.contains("quitar") -> 
                        "Entiendo que quieres eliminar algo de tu rutina. ¿Qué alimento específico te gustaría quitar y de qué comida?"
                    lowerMessage.contains("cambiar") || lowerMessage.contains("modificar") || 
                    lowerMessage.contains("cambiar alimento") || lowerMessage.contains("modificar alimento") ||
                    lowerMessage.contains("modifica tu rutina") -> 
                        "Te ayudo a modificar tu rutina. ¿Qué alimento te gustaría cambiar y por cuál te gustaría reemplazarlo?"
                    else -> 
                        "¿En qué puedo ayudarte con tu rutina alimentaria? Puedo ayudarte a agregar, eliminar o modificar alimentos según tus necesidades."
                }
            }
            TipoIntento.Pregunta_Nutricional -> {
                when {
                    lowerMessage.contains("calorías") -> 
                        "Las calorías son la energía que necesita tu cuerpo. Para darte recomendaciones precisas, necesito conocer tu perfil completo. ¿Has configurado tu información personal en la aplicación?"
                    lowerMessage.contains("desayuno") -> 
                        "El desayuno es muy importante para empezar el día con energía. Basándome en tu perfil, te puedo sugerir opciones nutritivas. ¿Te gustaría que te ayude con eso?"
                    lowerMessage.contains("almuerzo") -> 
                        "El almuerzo debe ser balanceado con proteínas, carbohidratos y verduras. ¿Necesitas ideas específicas para tu almuerzo según tu perfil?"
                    lowerMessage.contains("cena") -> 
                        "La cena debe ser más ligera que el almuerzo. Te recomiendo proteínas magras con verduras. ¿Qué te parece si te sugiero opciones basadas en tus objetivos?"
                    else -> 
                        "Tengo información sobre nutrición y alimentación saludable. ¿Sobre qué aspecto específico te gustaría saber más? Puedo personalizar mis respuestas según tu perfil."
                }
            }
            else -> {
                when {
                    lowerMessage.contains("hola") || lowerMessage.contains("hi") -> {
                        "¡Hola! Soy NutriAI, tu asistente nutricional personal. Estoy aquí para ayudarte con tu rutina alimentaria y responder tus preguntas sobre nutrición. ¿En qué puedo ayudarte hoy?"
                    }
                    lowerMessage.contains("gracias") -> 
                        "¡De nada! Estoy aquí para ayudarte siempre que lo necesites. ¿Hay algo más en lo que pueda asistirte con tu nutrición?"
                    else -> 
                        "Entiendo tu consulta. Como NutriAI, puedo ayudarte con información nutricional, sugerencias de alimentos, gestión de tu rutina alimentaria, y mucho más. ¿Hay algo específico en lo que pueda ayudarte?"
                }
            }
        }
    }
    
    private fun determineIntent(message: String): TipoIntento {
        val lowerMessage = message.lowercase()
        
        return when {
            lowerMessage.contains("agregar") || lowerMessage.contains("añadir") || 
            lowerMessage.contains("agregar alimento") || lowerMessage.contains("añadir alimento") ||
            lowerMessage.contains("incluir") || lowerMessage.contains("agregar comida") -> TipoIntento.Modificar_Rutina
            lowerMessage.contains("eliminar") || lowerMessage.contains("quitar") ||
            lowerMessage.contains("remover") || lowerMessage.contains("quitar comida") -> TipoIntento.Modificar_Rutina
            lowerMessage.contains("cambiar") || lowerMessage.contains("modificar") ||
            lowerMessage.contains("cambiar alimento") || lowerMessage.contains("modificar alimento") ||
            lowerMessage.contains("rutina") || lowerMessage.contains("modificar rutina") ||
            lowerMessage.contains("modifica tu rutina") -> TipoIntento.Modificar_Rutina
            lowerMessage.contains("calorías") || lowerMessage.contains("nutricional") ||
            lowerMessage.contains("proteína") || lowerMessage.contains("carbohidrato") ||
            lowerMessage.contains("grasa") || lowerMessage.contains("vitamina") ||
            lowerMessage.contains("preguntas de nutrición") || lowerMessage.contains("responder preguntas de nutrición") -> TipoIntento.Pregunta_Nutricional
            lowerMessage.contains("desayuno") || lowerMessage.contains("almuerzo") ||
            lowerMessage.contains("cena") || lowerMessage.contains("snack") ||
            lowerMessage.contains("comida") || lowerMessage.contains("alimentos adecuados") -> TipoIntento.Pregunta_Nutricional
            lowerMessage.contains("rutina nutricional") || lowerMessage.contains("mostrar rutina") -> TipoIntento.Pregunta_Nutricional
            else -> TipoIntento.Otros
        }
    }
    
    private fun determineAction(message: String): TipoAccion? {
        val lowerMessage = message.lowercase()
        
        return when {
            lowerMessage.contains("agregar") || lowerMessage.contains("añadir") || 
            lowerMessage.contains("agregar alimento") || lowerMessage.contains("añadir alimento") -> TipoAccion.Agregar
            lowerMessage.contains("eliminar") || lowerMessage.contains("quitar") -> TipoAccion.Eliminar
            lowerMessage.contains("cambiar") || lowerMessage.contains("modificar") || 
            lowerMessage.contains("cambiar alimento") || lowerMessage.contains("modificar alimento") -> TipoAccion.Modificar
            else -> null
        }
    }
    
    // Función para manejar el flujo de modificación de rutina
    private fun handleRoutineModificationFlow(message: String, userProfile: Usuario?, currentRoutine: List<RegistroAlimentoSalida>?): String {
        val lowerMessage = message.lowercase()
        val userName = userProfile?.nombre ?: ""
        val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
        
        // Detectar si el usuario está especificando momento del día
        val momentoDelDia = when {
            lowerMessage.contains("desayuno") -> "Desayuno"
            lowerMessage.contains("almuerzo") -> "Almuerzo"
            lowerMessage.contains("cena") -> "Cena"
            lowerMessage.contains("snack") -> "Snack"
            else -> null
        }
        
        // Detectar si el usuario está especificando un alimento específico
        val alimentosEnRutina = currentRoutine?.filter { it.momentoDelDia == momentoDelDia }?.map { it.alimento.nombreAlimento } ?: emptyList()
        
        return when {
            // Si el usuario menciona "modifica tu rutina" sin más detalles
            lowerMessage.contains("modifica tu rutina") && !lowerMessage.contains("desayuno") && 
            !lowerMessage.contains("almuerzo") && !lowerMessage.contains("cena") && !lowerMessage.contains("snack") -> {
                val rutinaActual = getDetailedRoutineInfo(currentRoutine)
                "$greeting ¡Perfecto! Te ayudo a modificar tu rutina.\n\n" +
                "📋 **Tu rutina de hoy:**\n$rutinaActual\n\n" +
                "🔄 **¿Qué te gustaría modificar?**\n\n" +
                "1️⃣ **¿En qué momento del día?** (Desayuno, Almuerzo, Cena, Snack)\n" +
                "2️⃣ **¿Qué comida específica quieres cambiar?**\n" +
                "3️⃣ **¿Por cuál alimento te gustaría reemplazarla?**\n\n" +
                "💡 **Ejemplo:** \"Quiero cambiar el arroz del almuerzo por quinoa\""
            }
            
            // Si el usuario especifica momento del día pero no el alimento
            momentoDelDia != null && alimentosEnRutina.isNotEmpty() && 
            !lowerMessage.contains("por") && !lowerMessage.contains("reemplazar") -> {
                val alimentosTexto = alimentosEnRutina.joinToString(", ")
                "$greeting Perfecto, quieres modificar el **$momentoDelDia**.\n\n" +
                "🍽️ **Alimentos actuales en $momentoDelDia:**\n$alimentosTexto\n\n" +
                "¿Cuál de estos alimentos quieres cambiar y por cuál te gustaría reemplazarlo?\n\n" +
                "💡 **Ejemplo:** \"Quiero cambiar el arroz por quinoa\""
            }
            
            // Si el usuario especifica momento del día pero no hay alimentos
            momentoDelDia != null && alimentosEnRutina.isEmpty() -> {
                "$greeting Veo que no tienes alimentos registrados para el **$momentoDelDia**.\n\n" +
                "¿Te gustaría agregar algún alimento a esta comida en lugar de modificar?\n\n" +
                "💡 **Ejemplo:** \"Quiero agregar avena al desayuno\""
            }
            
            // Si el usuario está especificando el cambio completo
            momentoDelDia != null && lowerMessage.contains("por") -> {
                val alimentoOriginal = extractFoodFromMessage(message, alimentosEnRutina)
                val alimentoNuevo = extractNewFoodFromMessage(message)
                
                if (alimentoOriginal != null && alimentoNuevo != null) {
                    "$greeting ¡Perfecto! Entiendo que quieres cambiar:\n\n" +
                    "🔄 **Cambio solicitado:**\n" +
                    "• **De:** $alimentoOriginal\n" +
                    "• **Por:** $alimentoNuevo\n" +
                    "• **Momento:** $momentoDelDia\n\n" +
                    "¿Confirmas este cambio? (Responde 'sí' para proceder o 'no' para cancelar)"
                } else {
                    "$greeting No pude entender completamente el cambio que quieres hacer.\n\n" +
                    "Por favor, especifica:\n" +
                    "• ¿Qué alimento quieres cambiar?\n" +
                    "• ¿Por cuál quieres reemplazarlo?\n\n" +
                    "💡 **Ejemplo:** \"Quiero cambiar el arroz por quinoa en el almuerzo\""
                }
            }
            
            else -> {
                "$greeting Para ayudarte a modificar tu rutina, necesito que me digas:\n\n" +
                "1️⃣ **¿En qué momento del día?** (Desayuno, Almuerzo, Cena, Snack)\n" +
                "2️⃣ **¿Qué alimento quieres cambiar?**\n" +
                "3️⃣ **¿Por cuál alimento lo quieres reemplazar?**\n\n" +
                "💡 **Ejemplo:** \"Quiero cambiar el arroz del almuerzo por quinoa\""
            }
        }
    }
    
    // Función auxiliar para extraer el alimento original del mensaje
    private fun extractFoodFromMessage(message: String, availableFoods: List<String>): String? {
        val lowerMessage = message.lowercase()
        return availableFoods.find { food ->
            lowerMessage.contains(food.lowercase())
        }
    }
    
    // Función auxiliar para extraer el alimento nuevo del mensaje
    private fun extractNewFoodFromMessage(message: String): String? {
        val lowerMessage = message.lowercase()
        val porIndex = lowerMessage.indexOf("por")
        if (porIndex != -1) {
            val afterPor = message.substring(porIndex + 3).trim()
            // Buscar hasta el final o hasta la próxima palabra clave
            val endIndex = afterPor.indexOf(" en ")
            return if (endIndex != -1) {
                afterPor.substring(0, endIndex).trim()
            } else {
                afterPor
            }
        }
        return null
    }
    
    // Función para obtener información detallada de la rutina actual
    private fun getDetailedRoutineInfo(currentRoutine: List<RegistroAlimentoSalida>?): String {
        if (currentRoutine == null || currentRoutine.isEmpty()) {
            return "📝 **No tienes alimentos registrados para hoy**\n\n" +
                   "Para modificar tu rutina, primero necesitas registrar algunos alimentos."
        }
        
        val comidasAgrupadas = currentRoutine.groupBy { it.momentoDelDia }
        val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
        
        return momentos.joinToString("\n\n") { momento ->
            val alimentos = comidasAgrupadas[momento] ?: emptyList()
            val emoji = when (momento) {
                "Desayuno" -> "🌅"
                "Almuerzo" -> "🌞"
                "Cena" -> "🌙"
                "Snack" -> "🍎"
                else -> "🍽️"
            }
            
            if (alimentos.isEmpty()) {
                "$emoji **$momento:**\n- No hay alimentos registrados"
            } else {
                "$emoji **$momento:**\n" + alimentos.joinToString("\n") { 
                    "- ${it.alimento.nombreAlimento} (${it.tamanoPorcion} ${it.unidadMedida})" 
                }
            }
        }
    }
    
    // Función para detectar si es una solicitud completa de modificación
    private fun isCompleteModificationRequest(message: String): Boolean {
        val lowerMessage = message.lowercase()
        
        // Detectar si contiene acción, alimento, momento y cantidad
        val hasAction = lowerMessage.contains("agregar") || lowerMessage.contains("cambiar") ||
                       lowerMessage.contains("agregar alimento") || lowerMessage.contains("cambiar alimento")
        val hasFood = lowerMessage.contains("gramos") || lowerMessage.contains("taza") || 
                     lowerMessage.contains("porción") || lowerMessage.contains("unidad")
        val hasMealTime = lowerMessage.contains("desayuno") || lowerMessage.contains("almuerzo") || 
                         lowerMessage.contains("cena") || lowerMessage.contains("snack")
        val hasQuantity = Regex("\\d+").containsMatchIn(message)
        
        return hasAction && hasFood && hasMealTime && hasQuantity
    }
    
    // Función para parsear una solicitud completa de modificación
    private fun parseModificationRequest(message: String): ModificationData? {
        val lowerMessage = message.lowercase()
        
        try {
            // Detectar acción
            val action = when {
                lowerMessage.contains("agregar") || lowerMessage.contains("agregar alimento") -> "Agregar"
                lowerMessage.contains("cambiar") || lowerMessage.contains("cambiar alimento") -> "Cambiar"
                else -> return null
            }
            
            // Detectar momento del día
            val mealTime = when {
                lowerMessage.contains("desayuno") -> "Desayuno"
                lowerMessage.contains("almuerzo") -> "Almuerzo"
                lowerMessage.contains("cena") -> "Cena"
                lowerMessage.contains("snack") -> "Snack"
                else -> return null
            }
            
            // Extraer cantidad y unidad
            val quantityMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(gramos?|tazas?|porciones?|unidades?)").find(message)
            val quantity = quantityMatch?.groupValues?.get(1)?.toFloatOrNull() ?: return null
            val unit = quantityMatch?.groupValues?.get(2) ?: return null
            
            // Extraer nombre del alimento (simplificado)
            val foodName = extractFoodNameFromMessage(message)
            
            return ModificationData(
                action = action,
                foodName = foodName,
                mealTime = mealTime,
                quantity = quantity,
                unit = unit
            )
        } catch (e: Exception) {
            println("Error parseando solicitud de modificación: ${e.message}")
            return null
        }
    }
    
    // Función auxiliar para extraer el nombre del alimento
    private fun extractFoodNameFromMessage(message: String): String {
        val lowerMessage = message.lowercase()
        
        // Lista de alimentos comunes para detectar
        val commonFoods = listOf(
            "avena", "pollo", "arroz", "quinoa", "huevo", "huevos", "pollo", "pescado", 
            "salmón", "atún", "pavo", "yogur", "queso", "leche", "pan", "pasta", 
            "papa", "batata", "plátano", "manzana", "naranja", "fresa", "uva"
        )
        
        for (food in commonFoods) {
            if (lowerMessage.contains(food)) {
                return food.capitalize()
            }
        }
        
        // Si no se encuentra un alimento específico, intentar extraer de la estructura
        val words = message.split(" ")
        val foodIndex = words.indexOfFirst { it.lowercase() in listOf("de", "con", "y") }
        if (foodIndex > 0 && foodIndex < words.size - 1) {
            return words[foodIndex + 1].capitalize()
        }
        
        return "Alimento"
    }
    
    // Data class para almacenar datos de modificación
    data class ModificationData(
        val action: String,
        val foodName: String,
        val mealTime: String,
        val quantity: Float,
        val unit: String
    )
    
    
     private suspend fun generateRoutineResponse(userProfile: Usuario?, currentRoutine: List<RegistroAlimentoSalida>?, datePattern: String?): String {
         val userName = userProfile?.nombre ?: ""
         println("=== GENERANDO SALUDO PERSONALIZADO ===")
         println("UserProfile: $userProfile")
         println("Nombre del usuario: '$userName'")
         println("¿Nombre está vacío? ${userName.isBlank()}")
         println("¿Nombre es 'Usuario'? ${userName == "Usuario"}")
         
         val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
         println("Saludo generado: $greeting")
        
        return if (datePattern != null) {
            // Rutina de fecha específica - consultar base de datos
            val userId = userProfile?.idUsuario ?: 0L
            val specificRoutine = getRoutineForSpecificDate(datePattern, userId)
            
            // Verificar si la fecha consultada es el día actual
            val isCurrentDate = try {
                val consultedDate = LocalDate.parse(datePattern, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val today = LocalDate.now()
                consultedDate == today
            } catch (e: Exception) {
                false
            }
            
            val routineContent = "$greeting Aquí tienes tu rutina nutricional del $datePattern:\n\n" +
            generateRoutineContent(specificRoutine, datePattern, isCurrentDate)
            
            // Si no es el día actual, agregar menú inicial después de mostrar la rutina
            if (!isCurrentDate) {
                routineContent + "\n\n" + getInitialMenu(userName)
            } else {
                // Si es el día actual, agregar las opciones normales
                routineContent + "\n\n" +
                "**Opciones disponibles:**\n\n" +
                "Escribe **agregar alimento** si deseas incluir un nuevo alimento.\n\n" +
                "Escribe **cambiar alimento** si deseas reemplazar un alimento existente.\n\n" +
                "Escribe **ver rutina YYYY-MM-DD** si deseas consultar la rutina de otra fecha.\n" +
                "👉 **Ejemplo:** ver rutina 2025-10-05\n\n" +
                "⚠️ **Nota:** Las opciones de agregar alimento y cambiar alimento solo están disponibles para la rutina del día actual."
            }
        } else {
            // Rutina de hoy
            "$greeting Aquí tienes tu rutina nutricional de hoy:\n\n" +
            generateRoutineContent(currentRoutine, "hoy", true) + "\n\n" +
            "**Opciones disponibles:**\n\n" +
            "Escribe **agregar alimento** si deseas incluir un nuevo alimento.\n\n" +
            "Escribe **cambiar alimento** si deseas reemplazar un alimento existente.\n\n" +
            "Escribe **ver rutina YYYY-MM-DD** si deseas consultar la rutina de otra fecha.\n" +
            "👉 **Ejemplo:** ver rutina 2025-10-05\n\n" +
            "⚠️ **Nota:** Las opciones de agregar alimento y cambiar alimento solo están disponibles para la rutina del día actual."
        }
    }
    
    private fun generateRoutineContent(currentRoutine: List<RegistroAlimentoSalida>?, dateContext: String, isCurrentDate: Boolean = true): String {
        println("=== GENERANDO CONTENIDO DE RUTINA ===")
        println("CurrentRoutine: ${currentRoutine?.size} elementos")
        println("DateContext: $dateContext")
        println("IsCurrentDate: $isCurrentDate")
        println("CurrentRoutine detalle: ${currentRoutine?.map { "${it.alimento.nombreAlimento} (${it.momentoDelDia})" }}")
        
        return if (currentRoutine != null && currentRoutine.isNotEmpty()) {
            // Usar rutina real del usuario
            val comidasAgrupadas = currentRoutine.groupBy { it.momentoDelDia }
            val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
            
            val rutinaContent = momentos.joinToString("\n\n") { momento ->
                val alimentos = comidasAgrupadas[momento] ?: emptyList()
                val emoji = when (momento) {
                    "Desayuno" -> "🌅"
                    "Almuerzo" -> "🌞"
                    "Cena" -> "🌙"
                    "Snack" -> "🍎"
                    else -> "🍽️"
                }
                
                if (alimentos.isEmpty()) {
                    "$emoji $momento:\n- No hay alimentos registrados"
                } else {
                    "$emoji $momento:\n" + alimentos.joinToString("\n") { "- ${it.alimento.nombreAlimento}" }
                }
            }
            
            rutinaContent
        } else {
            // No hay alimentos registrados
            if (dateContext == "hoy" || isCurrentDate) {
                "📝 **No has registrado alimentos para ${if (dateContext == "hoy") "hoy" else dateContext}**\n\n" +
                "Para ver tu rutina nutricional, necesitas registrar los alimentos que consumes.\n\n" +
                "💡 **¿Cómo registrar alimentos?**\n" +
                "1. Ve a la sección 'Rutina'\n" +
                "2. Ubica el momento del día\n" +
                "3. Da clic en el icono +\n" +
                "4. Selecciona la cantidad\n" +
                "5. ¡Listo! Ya aparecerá el registro en la rutina"
            } else {
                // Para fechas pasadas, solo mostrar que no hay registros sin opciones de agregar/cambiar
                "📝 **No tienes una rutina registrada para el $dateContext**\n\n" +
                "No se encontraron alimentos registrados para esa fecha."
            }
        }
    }
    
    private fun getInitialMenu(userName: String): String {
        val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
        return "**Opciones disponibles:**\n\n" +
               "Escribe **agregar alimento** si deseas incluir un nuevo alimento a tu rutina del día actual.\n\n" +
               "Escribe **cambiar alimento** si deseas reemplazar un alimento existente en tu rutina del día actual.\n\n" +
               "Escribe **ver rutina YYYY-MM-DD** si deseas consultar la rutina de otra fecha.\n" +
               "👉 **Ejemplo:** ver rutina 2025-10-05\n\n" +
               "⚠️ **Nota:** Las opciones de agregar alimento y cambiar alimento solo están disponibles para la rutina del día actual."
    }
    
    private fun extractDateFromMessage(message: String): String? {
        val lowerMessage = message.lowercase()
        
        println("=== EXTRAYENDO FECHA DEL MENSAJE ===")
        println("Mensaje original: $message")
        println("Mensaje en minúsculas: $lowerMessage")
        
        // Patrones de fecha comunes - priorizar formato YYYY-MM-DD (formato de base de datos)
        val datePatterns = listOf(
            // Formato YYYY-MM-DD (prioritario) - formato de base de datos
            Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})"),
            // Formato DD/MM/YYYY (compatibilidad)
            Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})"),
            // Días relativos (ayer, hoy, mañana)
            Regex("(ayer|hoy|mañana)"),
            // Formato "DD de mes"
            Regex("(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"),
            // Días de la semana
            Regex("(lunes|martes|miércoles|jueves|viernes|sábado|domingo)"),
            // Patrones adicionales
            Regex("(\\d{1,2})\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"),
            // Meses completos
            Regex("(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)")
        )
        
        for ((index, pattern) in datePatterns.withIndex()) {
            val match = pattern.find(lowerMessage)
            println("Patrón $index: $pattern - Match: $match")
            if (match != null) {
                println("✅ Fecha encontrada: ${match.value}")
                return match.value
            }
        }
        
        println("❌ No se encontró fecha en el mensaje")
        return null
    }
    
     private suspend fun getRoutineForSpecificDate(dateString: String, userId: Long): List<RegistroAlimentoSalida>? {
         return try {
             println("=== CONSULTANDO RUTINA PARA FECHA ESPECÍFICA ===")
             println("Fecha solicitada: $dateString")
             println("Usuario ID recibido: $userId")
             println("Tipo de userId: ${userId.javaClass.simpleName}")
             println("¿Es userId válido? ${userId > 0}")
            
            // Parsear la fecha YYYY-MM-DD (formato de base de datos)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val targetDate = LocalDate.parse(dateString, formatter)
            println("Fecha parseada: $targetDate")
            
             // Obtener todos los registros del usuario
             println("=== CONSULTANDO BASE DE DATOS ===")
             println("Usuario ID para consulta: $userId")
             
             val allRegistros = try {
                 println("🔄 Iniciando consulta a repository.obtenerComidasRecientes($userId)")
                 println("⏰ Iniciando consulta a las: ${System.currentTimeMillis()}")
                 
                 val result = repository.obtenerComidasRecientes(userId)
                 
                 println("⏰ Consulta finalizada a las: ${System.currentTimeMillis()}")
                 println("✅ Consulta completada. Resultado: ${result?.size ?: "null"} registros")
                 
                 if (result == null) {
                     println("⚠️ ADVERTENCIA: repository.obtenerComidasRecientes devolvió null")
                 } else if (result.isEmpty()) {
                     println("⚠️ ADVERTENCIA: repository.obtenerComidasRecientes devolvió lista vacía")
                 } else {
                     println("✅ ÉXITO: Se obtuvieron ${result.size} registros")
                 }
                 
                 result ?: emptyList()
             } catch (e: Exception) {
                 println("❌ ERROR en consulta a base de datos: ${e.message}")
                 println("❌ Tipo de error: ${e.javaClass.simpleName}")
                 e.printStackTrace()
                 emptyList()
             }
             
             println("Total de registros del usuario: ${allRegistros.size}")
             println("¿Consulta exitosa? ${allRegistros != null}")
            
             // Mostrar algunos registros de ejemplo para debugging
             if (allRegistros.isNotEmpty()) {
                 println("=== TODOS LOS REGISTROS DEL USUARIO ===")
                 allRegistros.forEachIndexed { index, registro ->
                     println("Registro $index: ${registro.alimento.nombreAlimento}")
                     println("Fecha completa: ${registro.consumidoEn}")
                     println("Fecha substring (0,10): ${registro.consumidoEn.substring(0, 10)}")
                     println("Momento: ${registro.momentoDelDia}")
                     println("---")
                 }
                 
                 println("=== BUSCANDO REGISTROS PARA FECHA ESPECÍFICA: $dateString ===")
                 println("Fecha objetivo: $targetDate")
             }
            
             // Filtrar por la fecha específica
             println("=== INICIANDO FILTRADO POR FECHA ===")
             val registrosDelDia = allRegistros.filter { registro ->
                 try {
                     // El registro.consumidoEn viene en formato ISO: 2025-10-05T10:30:00
                     val fechaRegistro = registro.consumidoEn.substring(0, 10) // "2025-10-05"
                     val registroDate = LocalDate.parse(fechaRegistro) // Parse ISO format
                     
                     println("🔍 Analizando registro: ${registro.alimento.nombreAlimento}")
                     println("   Fecha completa: ${registro.consumidoEn}")
                     println("   Fecha substring: $fechaRegistro")
                     println("   Fecha parseada: $registroDate")
                     println("   Fecha objetivo: $targetDate")
                     println("   ¿Coincide? ${registroDate == targetDate}")
                     
                     val coincide = registroDate == targetDate
                     if (coincide) {
                         println("✅ ¡ENCONTRADO! Registro coincide con la fecha solicitada")
                     }
                     
                     coincide
                 } catch (e: Exception) {
                     println("❌ Error parseando fecha del registro: ${registro.consumidoEn} - Error: ${e.message}")
                     false
                 }
             }
            
            println("Registros encontrados para $dateString: ${registrosDelDia.size}")
            registrosDelDia.forEach { 
                println("- ${it.alimento.nombreAlimento} (${it.momentoDelDia})")
            }
            
            // Si no hay registros para la fecha específica, mostrar todos los registros disponibles
            if (registrosDelDia.isEmpty() && allRegistros.isNotEmpty()) {
                println("=== NO HAY REGISTROS PARA ESA FECHA, MOSTRANDO REGISTROS DISPONIBLES ===")
                allRegistros.take(5).forEach { registro ->
                    println("Registro disponible: ${registro.alimento.nombreAlimento} - ${registro.consumidoEn}")
                }
            }
            
            registrosDelDia
        } catch (e: Exception) {
            println("Error obteniendo rutina para fecha $dateString: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    // Helper functions for NutriAI step-by-step flow
    
    private suspend fun handleConversationFlow(
        message: String, 
        currentState: ConversationState, 
        userProfile: Usuario?, 
        currentRoutine: List<RegistroAlimentoSalida>?
    ): ChatbotResponse? {
        val userId = currentState.userId
        val userName = userProfile?.nombre ?: ""
        
        // Solo mostrar saludo en el primer paso de cada flujo
        val shouldShowGreeting = currentState.currentStep == NutriAIStep.ADD_FOOD_NAME || 
                                currentState.currentStep == NutriAIStep.CHANGE_ORIGINAL_FOOD
        val greeting = if (shouldShowGreeting) {
            if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
        } else ""
        
        println("=== DEBUG CONVERSATION FLOW ===")
        println("Current Step: ${currentState.currentStep}")
        println("Should Show Greeting: $shouldShowGreeting")
        println("Greeting: '$greeting'")
        println("User Name: '$userName'")
        
        return when (currentState.currentStep) {
            NutriAIStep.ADD_FOOD_NAME -> {
                val foodName = message.trim()
                conversationStates[userId] = currentState.copy(
                    currentStep = NutriAIStep.ADD_FOOD_QUANTITY,
                    foodName = foodName
                )
                ChatbotResponse(
                    respuesta = "${greeting}¡Excelente! Has elegido **$foodName**.\n\n" +
                               "⚖️ **¿Qué cantidad quieres agregar?**\n" +
                               "(ejemplo: 100, 1, 2, etc.)",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Agregar
                )
            }
            
            NutriAIStep.ADD_FOOD_QUANTITY -> {
                val quantity = message.trim()
                // Validar que sea solo un número
                if (quantity.matches(Regex("\\d+(\\.\\d+)?"))) {
                    // Buscar el alimento por nombre para obtener las unidades válidas
                    val alimento = try {
                        repository.buscarAlimentoPorNombre(currentState.foodName ?: "")
                    } catch (e: Exception) {
                        println("Error buscando alimento: ${e.message}")
                        null
                    }
                    
                    // Obtener las unidades válidas si el alimento existe
                    val unidadesValidas = if (alimento != null) {
                        try {
                            repository.obtenerUnidadesPorId(alimento.idAlimento)
                        } catch (e: Exception) {
                            println("Error obteniendo unidades válidas: ${e.message}")
                            emptyList<String>()
                        }
                    } else {
                        emptyList<String>()
                    }
                    
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.ADD_FOOD_UNIT,
                        quantity = quantity,
                        validUnits = if (unidadesValidas.isNotEmpty()) unidadesValidas else null
                    )
                    
                    val mensajeUnidades = if (unidadesValidas.isNotEmpty()) {
                        val unidadesTexto = unidadesValidas.joinToString(", ")
                        "📏 **¿Cuál es la unidad de medida?**\n\n" +
                        "Unidades disponibles: **$unidadesTexto**\n\n" +
                        "Por favor, elige una de las unidades listadas arriba."
                    } else {
                        "📏 **¿Cuál es la unidad de medida?**\n" +
                        "(ejemplo: gramos, tazas, porciones, etc.)"
                    }
                    
                    ChatbotResponse(
                        respuesta = "Perfecto, **$quantity** de **${currentState.foodName}**.\n\n$mensajeUnidades",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                } else {
                    ChatbotResponse(
                        respuesta = "Por favor, ingresa solo un número para la cantidad.\n\n" +
                                   "⚖️ **¿Qué cantidad quieres agregar?**\n" +
                                   "(ejemplo: 100, 1, 2, etc.)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                }
            }
            
            NutriAIStep.ADD_FOOD_UNIT -> {
                val unit = message.trim().lowercase()
                val unidadesValidas = currentState.validUnits
                
                // Validar que la unidad esté en la lista de unidades válidas
                if (!unidadesValidas.isNullOrEmpty()) {
                    val unidadValida = unidadesValidas.find { 
                        it.lowercase() == unit 
                    }
                    
                    if (unidadValida == null) {
                        val unidadesTexto = unidadesValidas.joinToString(", ")
                        ChatbotResponse(
                            respuesta = "❌ La unidad **$unit** no está disponible para **${currentState.foodName}**.\n\n" +
                                       "Las unidades válidas son: **$unidadesTexto**\n\n" +
                                       "Por favor, elige una de las unidades listadas.",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    } else {
                        // Usar la unidad válida (con el formato correcto)
                        conversationStates[userId] = currentState.copy(
                            currentStep = NutriAIStep.ADD_FOOD_MEAL_TIME,
                            unit = unidadValida
                        )
                        ChatbotResponse(
                            respuesta = "Excelente, **${currentState.quantity} $unidadValida** de **${currentState.foodName}**.\n\n" +
                                       "🕐 **¿En qué momento del día?**\n" +
                                       "(Desayuno, Almuerzo, Cena, Snack)",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    }
                } else {
                    // Si no hay unidades válidas disponibles, aceptar cualquier unidad (fallback)
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.ADD_FOOD_MEAL_TIME,
                        unit = unit
                    )
                    ChatbotResponse(
                        respuesta = "Excelente, **${currentState.quantity} $unit** de **${currentState.foodName}**.\n\n" +
                                   "🕐 **¿En qué momento del día?**\n" +
                                   "(Desayuno, Almuerzo, Cena, Snack)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                }
            }
            
            NutriAIStep.ADD_FOOD_MEAL_TIME -> {
                val mealTime = message.trim()
                conversationStates[userId] = currentState.copy(
                    currentStep = NutriAIStep.ADD_FOOD_CONFIRMATION,
                    mealTime = mealTime
                )
                ChatbotResponse(
                    respuesta = "¡Perfecto! Resumen de tu solicitud:\n\n" +
                               "🥦 **Alimento:** ${currentState.foodName}\n" +
                               "⚖️ **Cantidad:** ${currentState.quantity}\n" +
                               "📏 **Unidad:** ${currentState.unit}\n" +
                               "🕐 **Momento:** $mealTime\n\n" +
                               "¿Deseas agregar **${currentState.foodName} - ${currentState.quantity} ${currentState.unit}** a tu rutina nutricional?\n\n" +
                               "💡 **Responde:**\n" +
                               "• **Sí** o **agregar** para confirmar\n" +
                               "• **No** para cancelar",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Agregar
                )
            }
            
            NutriAIStep.ADD_FOOD_CONFIRMATION -> {
                val lowerMessage = message.lowercase()
                if (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("confirmo") || 
                    lowerMessage.contains("agregar") || lowerMessage.contains("añadir") ||
                    lowerMessage.contains("agregar alimento") || lowerMessage.contains("añadir alimento")) {
                    // Perform database operation
                    val success = try {
                        repository.agregarAlimentoDesdeChatbot(
                            idUsuario = userId,
                            nombreAlimento = currentState.foodName ?: "",
                            cantidad = currentState.quantity ?: "",
                            unidad = currentState.unit ?: "",
                            momentoDelDia = currentState.mealTime ?: ""
                        )
                    } catch (e: Exception) {
                        println("Error agregando alimento desde chatbot: ${e.message}")
                        false
                    }
                    
                    // Reset conversation state
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    
                    if (success) {
                        // Notificar que la rutina se actualizó
                        onRoutineUpdated?.invoke()
                        
                        ChatbotResponse(
                            respuesta = "¡Perfecto! He registrado **${currentState.foodName} - ${currentState.quantity} ${currentState.unit}** en tu rutina.\n\n" +
                                       "✅ **Tu rutina se ha actualizado correctamente.**\n\n" +
                                       "¿Te gustaría hacer algún otro cambio en tu rutina o necesitas ayuda con algo más?",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    } else {
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al agregar el alimento a tu rutina.\n\n" +
                                       "❌ **No se pudo actualizar la rutina.**\n\n" +
                                       "Por favor, verifica que el nombre del alimento sea correcto e inténtalo de nuevo.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    // Reset conversation state
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Entendido, no se realizará ningún cambio.\n\n" +
                                   "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            NutriAIStep.CHANGE_ORIGINAL_FOOD -> {
                val originalFood = message.trim()
                conversationStates[userId] = currentState.copy(
                    currentStep = NutriAIStep.CHANGE_NEW_FOOD,
                    originalFood = originalFood
                )
                ChatbotResponse(
                    respuesta = "${greeting}Entendido, quieres cambiar **$originalFood**.\n\n" +
                               "🥦 **¿Por cuál alimento lo quieres reemplazar?**\n" +
                               "(menciona el nuevo alimento)",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Modificar
                )
            }
            
            NutriAIStep.CHANGE_NEW_FOOD -> {
                val newFood = message.trim()
                conversationStates[userId] = currentState.copy(
                    currentStep = NutriAIStep.CHANGE_QUANTITY,
                    newFood = newFood
                )
                ChatbotResponse(
                    respuesta = "Perfecto, quieres reemplazar **${currentState.originalFood}** por **$newFood**.\n\n" +
                               "⚖️ **¿Qué cantidad del nuevo alimento?**\n" +
                               "(ejemplo: 100, 1, 2, etc.)",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Modificar
                )
            }
            
            NutriAIStep.CHANGE_QUANTITY -> {
                val quantity = message.trim()
                // Validar que sea solo un número
                if (quantity.matches(Regex("\\d+(\\.\\d+)?"))) {
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.CHANGE_UNIT,
                        quantity = quantity
                    )
                    ChatbotResponse(
                        respuesta = "Excelente, **$quantity** de **${currentState.newFood}**.\n\n" +
                                   "📏 **¿Cuál es la unidad de medida?**\n" +
                                   "(ejemplo: gramos, tazas, porciones, etc.)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    ChatbotResponse(
                        respuesta = "Por favor, ingresa solo un número para la cantidad.\n\n" +
                                   "⚖️ **¿Qué cantidad del nuevo alimento?**\n" +
                                   "(ejemplo: 100, 1, 2, etc.)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
            }
            
            NutriAIStep.CHANGE_UNIT -> {
                val unit = message.trim()
                conversationStates[userId] = currentState.copy(
                    currentStep = NutriAIStep.CHANGE_MEAL_TIME,
                    unit = unit
                )
                ChatbotResponse(
                    respuesta = "$greeting Perfecto, **${currentState.quantity} $unit** de **${currentState.newFood}**.\n\n" +
                               "🕐 **¿En qué momento del día?**\n" +
                               "(Desayuno, Almuerzo, Cena, Snack)",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Modificar
                )
            }
            
            NutriAIStep.CHANGE_MEAL_TIME -> {
                val mealTime = message.trim()
                conversationStates[userId] = currentState.copy(
                    currentStep = NutriAIStep.CHANGE_CONFIRMATION,
                    mealTime = mealTime
                )
                ChatbotResponse(
                    respuesta = "¡Perfecto! Resumen de tu cambio:\n\n" +
                               "🔄 **Cambio solicitado:**\n" +
                               "• **De:** ${currentState.originalFood}\n" +
                               "• **Por:** ${currentState.newFood}\n" +
                               "• **Cantidad:** ${currentState.quantity}\n" +
                               "• **Unidad:** ${currentState.unit}\n" +
                               "• **Momento:** $mealTime\n\n" +
                               "¿Deseas reemplazar **${currentState.originalFood}** por **${currentState.newFood} - ${currentState.quantity} ${currentState.unit}**?\n\n" +
                               "💡 **Responde:**\n" +
                               "• **Sí** o **cambiar** para confirmar\n" +
                               "• **No** para cancelar",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Modificar
                )
            }
            
            NutriAIStep.CHANGE_CONFIRMATION -> {
                val lowerMessage = message.lowercase()
                if (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("confirmo") || 
                    lowerMessage.contains("cambiar") || lowerMessage.contains("modificar") ||
                    lowerMessage.contains("cambiar alimento") || lowerMessage.contains("modificar alimento")) {
                    // Perform database operation
                    val success = try {
                        repository.cambiarAlimentoDesdeChatbot(
                            idUsuario = userId,
                            alimentoOriginal = currentState.originalFood ?: "",
                            nuevoAlimento = currentState.newFood ?: "",
                            cantidad = currentState.quantity ?: "",
                            unidad = currentState.unit ?: "",
                            momentoDelDia = currentState.mealTime ?: ""
                        )
                    } catch (e: Exception) {
                        println("Error cambiando alimento desde chatbot: ${e.message}")
                        false
                    }
                    
                    // Reset conversation state
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    
                    if (success) {
                        // Notificar que la rutina se actualizó
                        onRoutineUpdated?.invoke()
                        
                        ChatbotResponse(
                            respuesta = "¡Perfecto! He realizado el cambio en tu rutina.\n\n" +
                                       "✅ **Tu rutina se ha actualizado correctamente.**\n\n" +
                                       "¿Te gustaría hacer algún otro cambio en tu rutina o necesitas ayuda con algo más?",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Modificar
                        )
                    } else {
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al realizar el cambio en tu rutina.\n\n" +
                                       "❌ **No se pudo actualizar la rutina.**\n\n" +
                                       "Por favor, verifica que los nombres de los alimentos sean correctos e inténtalo de nuevo.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    // Reset conversation state
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Entendido, no se realizará ningún cambio.\n\n" +
                                   "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            // Nuevos estados para el flujo de cambio por categorías
            NutriAIStep.CHANGE_SHOW_CATEGORIES -> {
                // Este estado ya no se usa, las categorías se obtienen directamente en la detección del comando
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                ChatbotResponse(
                    respuesta = "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
            
            NutriAIStep.CHANGE_SHOW_CURRENT_FOOD -> {
                // Este estado ya no se usa, el alimento actual se muestra directamente en la detección del comando
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                ChatbotResponse(
                    respuesta = "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
            
            NutriAIStep.CHANGE_SELECT_ORIGINAL_FOOD -> {
                val alimentoSeleccionado = message.trim()
                val alimentosDisponibles = currentState.currentRoutineFoods ?: emptyList()
                
                // Buscar el alimento en la lista de alimentos de la rutina
                val alimentoValido = alimentosDisponibles.find { 
                    it.alimento.nombreAlimento.equals(alimentoSeleccionado, ignoreCase = true) 
                }
                
                if (alimentoValido != null) {
                    // Alimento encontrado, ahora obtener categorías para el nuevo alimento
                    try {
                        val categorias = repository.obtenerCategoriasUnicas()
                        if (categorias.isNotEmpty()) {
                            conversationStates[userId] = currentState.copy(
                                currentStep = NutriAIStep.CHANGE_SELECT_CATEGORY,
                                availableCategories = categorias,
                                originalFood = alimentoValido.alimento.nombreAlimento,
                                mealTime = alimentoValido.momentoDelDia
                            )
                            val categoriasTexto = categorias.joinToString(", ")
                            ChatbotResponse(
                                respuesta = "Perfecto, cambiarás **${alimentoValido.alimento.nombreAlimento}** de **${alimentoValido.momentoDelDia}**.\n\n" +
                                           "Estas son las categorías disponibles: **$categoriasTexto**\n\n" +
                                           "Por favor, selecciona la categoría del nuevo alimento que deseas elegir.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            ChatbotResponse(
                                respuesta = "Lo siento, no hay categorías de alimentos disponibles en este momento.\n\n" +
                                           "¿Hay algo más en lo que pueda ayudarte?",
                                tipoIntento = TipoIntento.Otros,
                                tipoAccion = null
                            )
                        }
                    } catch (e: Exception) {
                        println("Error obteniendo categorías: ${e.message}")
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al obtener las categorías.\n\n" +
                                       "¿Hay algo más en lo que pueda ayudarte?",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    // Alimento no encontrado, mostrar lista nuevamente
                    val alimentosTexto = alimentosDisponibles.joinToString("\n") { 
                        "• **${it.alimento.nombreAlimento}** - ${it.momentoDelDia}" 
                    }
                    ChatbotResponse(
                        respuesta = "❌ No encontré **$alimentoSeleccionado** en tu rutina actual.\n\n" +
                                   "Estos son los alimentos registrados en tu rutina:\n\n" +
                                   "$alimentosTexto\n\n" +
                                   "📝 **Por favor, elige uno de los alimentos listados arriba.**",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
            }
            
            NutriAIStep.CHANGE_SELECT_CATEGORY -> {
                val categoriaSeleccionada = message.trim()
                val categoriasDisponibles = currentState.availableCategories ?: emptyList()
                
                // Verificar si la categoría existe
                val categoriaValida = categoriasDisponibles.find { 
                    it.equals(categoriaSeleccionada, ignoreCase = true) 
                }
                
                if (categoriaValida != null) {
                    try {
                        val alimentos = repository.obtenerAlimentosPorCategoriaParaChatbot(categoriaValida)
                        if (alimentos.isNotEmpty()) {
                            conversationStates[userId] = currentState.copy(
                                currentStep = NutriAIStep.CHANGE_SHOW_FOODS,
                                selectedCategory = categoriaValida,
                                availableFoods = alimentos
                            )
                            val alimentosTexto = alimentos.joinToString("\n") { "• ${it.nombreAlimento}" }
                            ChatbotResponse(
                                respuesta = "En la categoría **$categoriaValida** se encuentran los siguientes alimentos:\n\n" +
                                           "$alimentosTexto\n\n" +
                                           "Por favor, elige uno.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            ChatbotResponse(
                                respuesta = "Actualmente no hay alimentos registrados en esa categoría.\n\n" +
                                           "¿Te gustaría consultar otra categoría?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        }
                    } catch (e: Exception) {
                        println("Error obteniendo alimentos por categoría: ${e.message}")
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al obtener los alimentos de esa categoría.\n\n" +
                                       "¿Hay algo más en lo que pueda ayudarte?",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    val categoriasTexto = categoriasDisponibles.joinToString(", ")
                    ChatbotResponse(
                        respuesta = "Esa categoría no se encuentra disponible. Las categorías disponibles son:\n\n" +
                                   "**$categoriasTexto**\n\n" +
                                   "Por favor, escribe una categoría válida.",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
            }
            
            NutriAIStep.CHANGE_SHOW_FOODS -> {
                val alimentoSeleccionado = message.trim()
                val alimentosDisponibles = currentState.availableFoods ?: emptyList()
                
                // Verificar si el alimento existe
                val alimentoValido = alimentosDisponibles.find { 
                    it.nombreAlimento.equals(alimentoSeleccionado, ignoreCase = true) 
                }
                
                if (alimentoValido != null) {
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.CHANGE_SELECT_FOOD_QUANTITY,
                        selectedFood = alimentoValido
                    )
                    ChatbotResponse(
                        respuesta = "¿Qué cantidad de **${alimentoValido.nombreAlimento}** deseas registrar?\n" +
                                   "(Solo ingresa el número: 1, 2, 3, 150, etc.)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    val alimentosTexto = alimentosDisponibles.joinToString("\n") { "• ${it.nombreAlimento}" }
                    ChatbotResponse(
                        respuesta = "Ese alimento no se encuentra disponible. Solo puedes elegir alimentos registrados en la base de datos.\n\n" +
                                   "Los alimentos disponibles en la categoría **${currentState.selectedCategory}** son:\n\n" +
                                   "$alimentosTexto\n\n" +
                                   "Por favor, elige uno de la lista.",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
            }
            
            NutriAIStep.CHANGE_SELECT_FOOD_QUANTITY -> {
                val cantidadTexto = message.trim()
                val alimentoSeleccionado = currentState.selectedFood
                
                if (alimentoSeleccionado != null) {
                    // Validar que sea solo un número
                    if (cantidadTexto.matches(Regex("\\d+(\\.\\d+)?"))) {
                        // Obtener las unidades válidas para este alimento desde unidad_equivalencia
                        val unidadesValidas = try {
                            repository.obtenerUnidadesPorId(alimentoSeleccionado.idAlimento)
                        } catch (e: Exception) {
                            println("Error obteniendo unidades válidas: ${e.message}")
                            emptyList<String>()
                        }
                        
                        conversationStates[userId] = currentState.copy(
                            currentStep = NutriAIStep.CHANGE_SELECT_MEAL_TIME,
                            quantity = cantidadTexto,
                            validUnits = if (unidadesValidas.isNotEmpty()) unidadesValidas else null
                        )
                        ChatbotResponse(
                            respuesta = "Perfecto, **$cantidadTexto** de **${alimentoSeleccionado.nombreAlimento}**.\n\n" +
                                       "🕐 **¿En qué momento del día deseas cambiar este alimento?**\n" +
                                       "(Desayuno, Almuerzo, Cena, Snack)",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Modificar
                        )
                    } else {
                        ChatbotResponse(
                            respuesta = "Por favor, ingresa solo un número para la cantidad.\n" +
                                       "Ejemplo: 1, 2, 3, 150, etc.",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Modificar
                        )
                    }
                } else {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Lo siento, hubo un error. Por favor, intenta de nuevo.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            NutriAIStep.CHANGE_SELECT_MEAL_TIME -> {
                val momentoDelDia = message.trim()
                val momentosValidos = listOf("desayuno", "almuerzo", "cena", "snack")
                
                val momentoValido = momentosValidos.find { 
                    it.equals(momentoDelDia, ignoreCase = true) 
                }
                
                if (momentoValido != null) {
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.CHANGE_SELECT_UNIT,
                        mealTime = momentoValido.capitalize()
                    )
                    val alimentoSeleccionado = currentState.selectedFood
                    val unidadesValidas = currentState.validUnits
                    
                    // Mostrar las unidades válidas si están disponibles
                    val mensajeUnidades = if (!unidadesValidas.isNullOrEmpty()) {
                        val unidadesTexto = unidadesValidas.joinToString(", ")
                        "📏 **¿Cuál es la unidad de medida para ${alimentoSeleccionado?.nombreAlimento}?**\n\n" +
                        "Unidades disponibles: **$unidadesTexto**\n\n" +
                        "Por favor, elige una de las unidades listadas arriba."
                    } else {
                        "📏 **¿Cuál es la unidad de medida para ${alimentoSeleccionado?.nombreAlimento}?**\n" +
                        "(Ejemplo: gramos, tazas, porciones, unidades, etc.)"
                    }
                    
                    ChatbotResponse(
                        respuesta = "Perfecto, **${momentoValido.capitalize()}**.\n\n$mensajeUnidades",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    ChatbotResponse(
                        respuesta = "Por favor, selecciona un momento del día válido:\n" +
                                   "• **Desayuno**\n" +
                                   "• **Almuerzo**\n" +
                                   "• **Cena**\n" +
                                   "• **Snack**",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
            }
            
            NutriAIStep.CHANGE_SELECT_UNIT -> {
                val unidad = message.trim().lowercase()
                val alimentoSeleccionado = currentState.selectedFood
                val cantidad = currentState.quantity
                val momentoDelDia = currentState.mealTime
                val alimentoOriginal = currentState.originalFood
                val unidadesValidas = currentState.validUnits
                
                if (alimentoSeleccionado != null && cantidad != null && momentoDelDia != null) {
                    // Validar que la unidad esté en la lista de unidades válidas
                    if (!unidadesValidas.isNullOrEmpty()) {
                        val unidadValida = unidadesValidas.find { 
                            it.lowercase() == unidad 
                        }
                        
                        if (unidadValida == null) {
                            val unidadesTexto = unidadesValidas.joinToString(", ")
                            ChatbotResponse(
                                respuesta = "❌ La unidad **$unidad** no está disponible para **${alimentoSeleccionado.nombreAlimento}**.\n\n" +
                                           "Las unidades válidas son: **$unidadesTexto**\n\n" +
                                           "Por favor, elige una de las unidades listadas.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            // Usar la unidad válida (con el formato correcto)
                            conversationStates[userId] = currentState.copy(
                                currentStep = NutriAIStep.CHANGE_CONFIRMATION_NEW,
                                unit = unidadValida
                            )
                            ChatbotResponse(
                                respuesta = "📋 **Resumen de tu cambio:**\n\n" +
                                           "• **Alimento original:** $alimentoOriginal\n" +
                                           "• **Nuevo alimento:** ${alimentoSeleccionado.nombreAlimento}\n" +
                                           "• **Cantidad:** $cantidad\n" +
                                           "• **Unidad:** $unidadValida\n" +
                                           "• **Momento:** $momentoDelDia\n\n" +
                                           "¿Deseas cambiar **$alimentoOriginal** por **${alimentoSeleccionado.nombreAlimento} - $cantidad $unidadValida**?\n\n" +
                                           "💡 **Responde:**\n" +
                                           "• **Sí** o **cambiar** para confirmar\n" +
                                           "• **No** para cancelar",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        }
                    } else {
                        // Si no hay unidades válidas disponibles, aceptar cualquier unidad (fallback)
                        conversationStates[userId] = currentState.copy(
                            currentStep = NutriAIStep.CHANGE_CONFIRMATION_NEW,
                            unit = unidad
                        )
                        ChatbotResponse(
                            respuesta = "📋 **Resumen de tu cambio:**\n\n" +
                                       "• **Alimento original:** $alimentoOriginal\n" +
                                       "• **Nuevo alimento:** ${alimentoSeleccionado.nombreAlimento}\n" +
                                       "• **Cantidad:** $cantidad\n" +
                                       "• **Unidad:** $unidad\n" +
                                       "• **Momento:** $momentoDelDia\n\n" +
                                       "¿Deseas cambiar **$alimentoOriginal** por **${alimentoSeleccionado.nombreAlimento} - $cantidad $unidad**?\n\n" +
                                       "💡 **Responde:**\n" +
                                       "• **Sí** o **cambiar** para confirmar\n" +
                                       "• **No** para cancelar",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Modificar
                        )
                    }
                } else {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Lo siento, hubo un error. Por favor, intenta de nuevo.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            NutriAIStep.CHANGE_CONFIRMATION_NEW -> {
                val lowerMessage = message.lowercase()
                val alimentoSeleccionado = currentState.selectedFood
                val cantidad = currentState.quantity
                val unidad = currentState.unit
                val momentoDelDia = currentState.mealTime
                val alimentoOriginal = currentState.originalFood
                
                if (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("confirmo") || 
                    lowerMessage.contains("cambiar") || lowerMessage.contains("modificar")) {
                    
                    if (alimentoSeleccionado != null && cantidad != null && unidad != null && momentoDelDia != null && alimentoOriginal != null) {
                        // Realizar el cambio en la base de datos
                        val success = try {
                            repository.cambiarAlimentoDesdeChatbot(
                                idUsuario = userId,
                                alimentoOriginal = alimentoOriginal,
                                nuevoAlimento = alimentoSeleccionado.nombreAlimento,
                                cantidad = cantidad,
                                unidad = unidad,
                                momentoDelDia = momentoDelDia
                            )
                        } catch (e: Exception) {
                            println("Error cambiando alimento desde chatbot: ${e.message}")
                            false
                        }
                        
                        // Reset conversation state
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        
                        if (success) {
                            // Notificar que la rutina se actualizó
                            onRoutineUpdated?.invoke()
                            
                            ChatbotResponse(
                                respuesta = "Perfecto, se ha cambiado tu alimento a **${alimentoSeleccionado.nombreAlimento}** – **$cantidad $unidad**.\n\n" +
                                           "✅ **Tu rutina se ha actualizado correctamente.**\n\n" +
                                           "¿Te gustaría hacer algún otro cambio en tu rutina o necesitas ayuda con algo más?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            ChatbotResponse(
                                respuesta = "Lo siento, hubo un problema al realizar el cambio en tu rutina.\n\n" +
                                           "❌ **No se pudo actualizar la rutina.**\n\n" +
                                           "Por favor, inténtalo de nuevo.",
                                tipoIntento = TipoIntento.Otros,
                                tipoAccion = null
                            )
                        }
                    } else {
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un error. Por favor, intenta de nuevo.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    // Reset conversation state
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Entendido, no se realizará el cambio.\n\n" +
                                   "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            NutriAIStep.CHANGE_SELECT_FOOD -> {
                // Este estado ya no se usa, se maneja en CHANGE_SHOW_FOODS
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                ChatbotResponse(
                    respuesta = "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
            
            // Estados para el flujo de agregar alimento por categorías
            NutriAIStep.ADD_SELECT_CATEGORY -> {
                val categoriaSeleccionada = message.trim()
                val categoriasDisponibles = currentState.availableCategories ?: emptyList()
                
                // Verificar si la categoría existe
                val categoriaValida = categoriasDisponibles.find { 
                    it.equals(categoriaSeleccionada, ignoreCase = true) 
                }
                
                if (categoriaValida != null) {
                    try {
                        val alimentos = repository.obtenerAlimentosPorCategoriaParaChatbot(categoriaValida)
                        if (alimentos.isNotEmpty()) {
                            conversationStates[userId] = currentState.copy(
                                currentStep = NutriAIStep.ADD_SHOW_FOODS,
                                selectedCategory = categoriaValida,
                                availableFoods = alimentos
                            )
                            val alimentosTexto = alimentos.joinToString("\n") { "• ${it.nombreAlimento}" }
                            ChatbotResponse(
                                respuesta = "En la categoría **$categoriaValida** se encuentran:\n\n" +
                                           "$alimentosTexto\n\n" +
                                           "Elige uno para agregar.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else {
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            ChatbotResponse(
                                respuesta = "Actualmente no hay alimentos disponibles en esa categoría.\n\n" +
                                           "¿Te gustaría seleccionar otra categoría?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        }
                    } catch (e: Exception) {
                        println("Error obteniendo alimentos por categoría: ${e.message}")
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al obtener los alimentos de esa categoría.\n\n" +
                                       "¿Hay algo más en lo que pueda ayudarte?",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    val categoriasTexto = categoriasDisponibles.joinToString(", ")
                    ChatbotResponse(
                        respuesta = "Esa categoría no se encuentra disponible. Las categorías disponibles son:\n\n" +
                                   "**$categoriasTexto**\n\n" +
                                   "Por favor, selecciona una categoría válida.",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                }
            }
            
            NutriAIStep.ADD_SHOW_FOODS -> {
                val alimentoSeleccionado = message.trim()
                val alimentosDisponibles = currentState.availableFoods ?: emptyList()
                
                // Verificar si el alimento existe
                val alimentoValido = alimentosDisponibles.find { 
                    it.nombreAlimento.equals(alimentoSeleccionado, ignoreCase = true) 
                }
                
                if (alimentoValido != null) {
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.ADD_SELECT_FOOD_QUANTITY,
                        selectedFood = alimentoValido
                    )
                    ChatbotResponse(
                        respuesta = "¿Qué cantidad de **${alimentoValido.nombreAlimento}** deseas agregar?\n" +
                                   "(Solo ingresa el número: 1, 2, 3, 150, etc.)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                } else {
                    val alimentosTexto = alimentosDisponibles.joinToString("\n") { "• ${it.nombreAlimento}" }
                    ChatbotResponse(
                        respuesta = "Ese alimento no se encuentra en la base de datos. Solo puedes elegir alimentos registrados.\n\n" +
                                   "Los alimentos disponibles en la categoría **${currentState.selectedCategory}** son:\n\n" +
                                   "$alimentosTexto\n\n" +
                                   "Por favor, elige uno de la lista.",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                }
            }
            
            NutriAIStep.ADD_SELECT_FOOD_QUANTITY -> {
                val cantidadTexto = message.trim()
                val alimentoSeleccionado = currentState.selectedFood
                
                if (alimentoSeleccionado != null) {
                    // Validar que sea solo un número
                    if (cantidadTexto.matches(Regex("\\d+(\\.\\d+)?"))) {
                        // Obtener las unidades válidas para este alimento desde unidad_equivalencia
                        val unidadesValidas = try {
                            repository.obtenerUnidadesPorId(alimentoSeleccionado.idAlimento)
                        } catch (e: Exception) {
                            println("Error obteniendo unidades válidas: ${e.message}")
                            emptyList<String>()
                        }
                        
                        conversationStates[userId] = currentState.copy(
                            currentStep = NutriAIStep.ADD_SELECT_MEAL_TIME,
                            quantity = cantidadTexto,
                            validUnits = if (unidadesValidas.isNotEmpty()) unidadesValidas else null
                        )
                        ChatbotResponse(
                            respuesta = "Perfecto, **$cantidadTexto** de **${alimentoSeleccionado.nombreAlimento}**.\n\n" +
                                       "🕐 **¿En qué momento del día deseas agregar este alimento?**\n" +
                                       "(Desayuno, Almuerzo, Cena, Snack)",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    } else {
                        ChatbotResponse(
                            respuesta = "Por favor, ingresa solo un número para la cantidad.\n" +
                                       "Ejemplo: 1, 2, 3, 150, etc.",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    }
                } else {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Lo siento, hubo un error. Por favor, intenta de nuevo.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            NutriAIStep.ADD_SELECT_MEAL_TIME -> {
                val momentoDelDia = message.trim()
                val momentosValidos = listOf("desayuno", "almuerzo", "cena", "snack")
                
                val momentoValido = momentosValidos.find { 
                    it.equals(momentoDelDia, ignoreCase = true) 
                }
                
                if (momentoValido != null) {
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.ADD_SELECT_UNIT,
                        mealTime = momentoValido.capitalize()
                    )
                    val alimentoSeleccionado = currentState.selectedFood
                    val unidadesValidas = currentState.validUnits
                    
                    // Mostrar las unidades válidas si están disponibles
                    val mensajeUnidades = if (!unidadesValidas.isNullOrEmpty()) {
                        val unidadesTexto = unidadesValidas.joinToString(", ")
                        "📏 **¿Cuál es la unidad de medida para ${alimentoSeleccionado?.nombreAlimento}?**\n\n" +
                        "Unidades disponibles: **$unidadesTexto**\n\n" +
                        "Por favor, elige una de las unidades listadas arriba."
                    } else {
                        "📏 **¿Cuál es la unidad de medida para ${alimentoSeleccionado?.nombreAlimento}?**\n" +
                        "(Ejemplo: gramos, tazas, porciones, unidades, etc.)"
                    }
                    
                    ChatbotResponse(
                        respuesta = "Perfecto, **${momentoValido.capitalize()}**.\n\n$mensajeUnidades",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                } else {
                    ChatbotResponse(
                        respuesta = "Por favor, selecciona un momento del día válido:\n" +
                                   "• **Desayuno**\n" +
                                   "• **Almuerzo**\n" +
                                   "• **Cena**\n" +
                                   "• **Snack**",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                }
            }
            
            NutriAIStep.ADD_SELECT_UNIT -> {
                val unidad = message.trim().lowercase()
                val alimentoSeleccionado = currentState.selectedFood
                val cantidad = currentState.quantity
                val momentoDelDia = currentState.mealTime
                val unidadesValidas = currentState.validUnits
                
                if (alimentoSeleccionado != null && cantidad != null && momentoDelDia != null) {
                    // Validar que la unidad esté en la lista de unidades válidas
                    if (!unidadesValidas.isNullOrEmpty()) {
                        val unidadValida = unidadesValidas.find { 
                            it.lowercase() == unidad 
                        }
                        
                        if (unidadValida == null) {
                            val unidadesTexto = unidadesValidas.joinToString(", ")
                            ChatbotResponse(
                                respuesta = "❌ La unidad **$unidad** no está disponible para **${alimentoSeleccionado.nombreAlimento}**.\n\n" +
                                           "Las unidades válidas son: **$unidadesTexto**\n\n" +
                                           "Por favor, elige una de las unidades listadas.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else {
                            // Usar la unidad válida (con el formato correcto)
                            conversationStates[userId] = currentState.copy(
                                currentStep = NutriAIStep.ADD_CONFIRMATION,
                                unit = unidadValida
                            )
                            ChatbotResponse(
                                respuesta = "📋 **Resumen de tu solicitud:**\n\n" +
                                           "• **Alimento:** ${alimentoSeleccionado.nombreAlimento}\n" +
                                           "• **Cantidad:** $cantidad\n" +
                                           "• **Unidad:** $unidadValida\n" +
                                           "• **Momento:** $momentoDelDia\n\n" +
                                           "¿Deseas agregar **${alimentoSeleccionado.nombreAlimento} - $cantidad $unidadValida** a tu rutina nutricional?\n\n" +
                                           "💡 **Responde:**\n" +
                                           "• **Sí** o **agregar** para confirmar\n" +
                                           "• **No** para cancelar",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        }
                    } else {
                        // Si no hay unidades válidas disponibles, aceptar cualquier unidad (fallback)
                        conversationStates[userId] = currentState.copy(
                            currentStep = NutriAIStep.ADD_CONFIRMATION,
                            unit = unidad
                        )
                        ChatbotResponse(
                            respuesta = "📋 **Resumen de tu solicitud:**\n\n" +
                                       "• **Alimento:** ${alimentoSeleccionado.nombreAlimento}\n" +
                                       "• **Cantidad:** $cantidad\n" +
                                       "• **Unidad:** $unidad\n" +
                                       "• **Momento:** $momentoDelDia\n\n" +
                                       "¿Deseas agregar **${alimentoSeleccionado.nombreAlimento} - $cantidad $unidad** a tu rutina nutricional?\n\n" +
                                       "💡 **Responde:**\n" +
                                       "• **Sí** o **agregar** para confirmar\n" +
                                       "• **No** para cancelar",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    }
                } else {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Lo siento, hubo un error. Por favor, intenta de nuevo.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            NutriAIStep.ADD_CONFIRMATION -> {
                val lowerMessage = message.lowercase()
                val alimentoSeleccionado = currentState.selectedFood
                val cantidad = currentState.quantity
                val unidad = currentState.unit
                val momentoDelDia = currentState.mealTime
                
                if (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("confirmo") || 
                    lowerMessage.contains("agregar") || lowerMessage.contains("añadir")) {
                    
                    if (alimentoSeleccionado != null && cantidad != null && unidad != null && momentoDelDia != null) {
                        // Realizar el agregado en la base de datos
                        val success = try {
                            repository.agregarAlimentoDesdeChatbot(
                                idUsuario = userId,
                                nombreAlimento = alimentoSeleccionado.nombreAlimento,
                                cantidad = cantidad,
                                unidad = unidad,
                                momentoDelDia = momentoDelDia
                            )
                        } catch (e: Exception) {
                            println("Error agregando alimento desde chatbot: ${e.message}")
                            false
                        }
                        
                        // Reset conversation state
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        
                        if (success) {
                            // Notificar que la rutina se actualizó
                            onRoutineUpdated?.invoke()
                            
                            ChatbotResponse(
                                respuesta = "Excelente, se ha agregado **${alimentoSeleccionado.nombreAlimento}** – **$cantidad $unidad** a tu plan alimenticio.\n\n" +
                                           "✅ **Tu rutina se ha actualizado correctamente.**\n\n" +
                                           "¿Te gustaría agregar otro alimento o necesitas ayuda con algo más?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else {
                            ChatbotResponse(
                                respuesta = "Lo siento, hubo un problema al agregar el alimento a tu rutina.\n\n" +
                                           "❌ **No se pudo agregar el alimento.**\n\n" +
                                           "Por favor, inténtalo de nuevo.",
                                tipoIntento = TipoIntento.Otros,
                                tipoAccion = null
                            )
                        }
                    } else {
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un error. Por favor, intenta de nuevo.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    // Reset conversation state
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Entendido, no se agregará el alimento.\n\n" +
                                   "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            NutriAIStep.ADD_SELECT_FOOD -> {
                // Este estado ya no se usa
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                ChatbotResponse(
                    respuesta = "¿Hay algo más en lo que pueda ayudarte con tu rutina nutricional?",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
            
            else -> null
        }
    }
    
    // Function to generate user profile display with visual format
    private fun generateUserProfileDisplay(userProfile: Usuario?): String {
        if (userProfile == null) {
            return "❌ No se puede generar la rutina\n\n" +
                   "Para crear una rutina personalizada, necesitas configurar tu perfil de usuario primero.\n\n" +
                   "💡 ¿Cómo configurar tu perfil?\n" +
                   "1. Ve a la sección 'Perfil'\n" +
                   "2. Completa tu información personal\n" +
                   "3. Especifica tus objetivos de salud\n" +
                   "4. ¡Listo! Podrás generar rutinas personalizadas"
        }

        val edad = calcularEdad(userProfile.fechaNacimiento)
        
        
        fun formatValue(value: String, defaultValue: String): String {
            return if (isDefaultValue(value)) defaultValue else value
        }
        
        return "📋 Aquí tienes tus datos registrados:\n\n" +
               "👤 Género: ${formatValue(userProfile.sexo, "No especificado")}\n" +
               "🎂 Edad: $edad años\n" +
               "📏 Altura: ${formatValue(userProfile.altura.toString(), "No registrada")} cm\n" +
               "⚖️ Peso actual: ${formatValue(userProfile.peso.toString(), "No registrado")} kg\n" +
               "🎯 Peso objetivo: ${formatValue(userProfile.pesoObjetivo.toString(), "No establecido")} kg\n" +
               "🥗 Tipo de dieta: ${formatValue(userProfile.restriccionesDieta, "No especificada")}\n" +
               "🏃 Nivel de actividad: ${formatValue(userProfile.nivelActividad, "No especificado")}\n" +
               "💪 Objetivo: ${formatValue(userProfile.objetivosSalud, "No establecido")}"
    }
    
    // Helper function to generate user profile summary
    private fun generateUserProfileSummary(userProfile: Usuario?): String {
        if (userProfile == null) {
            return "❌ Perfil no disponible\n" +
                   "Para generar una rutina personalizada, necesitas configurar tu perfil de usuario."
        }
        
        val genero = when (userProfile.sexo.lowercase()) {
            "m", "masculino" -> "Masculino"
            "f", "femenino" -> "Femenino"
            else -> "No especificado"
        }
        
        val edad = if (userProfile.fechaNacimiento.isNotBlank()) {
            try {
                val birthYear = userProfile.fechaNacimiento.substring(0, 4).toInt()
                val currentYear = java.time.LocalDate.now().year
                currentYear - birthYear
            } catch (e: Exception) {
                "No calculada"
            }
        } else {
            "No especificada"
        }
        
        return "• Género: $genero\n" +
               "• Edad: $edad años\n" +
               "• Altura: ${userProfile.altura} cm\n" +
               "• Peso actual: ${userProfile.peso} kg\n" +
               "• Peso objetivo: ${userProfile.pesoObjetivo} kg\n" +
               "• Tipo de dieta: ${userProfile.restriccionesDieta.ifBlank { "Balanceada" }}\n" +
               "• Nivel de actividad: ${userProfile.nivelActividad.ifBlank { "Moderado" }}\n" +
               "• Objetivo: ${userProfile.objetivosSalud.ifBlank { "Mantener peso" }}"
    }
    
    // Function to generate personalized routine
    private suspend fun generatePersonalizedRoutine(userProfile: Usuario?, isSubsequentRoutine: Boolean = false): String {
        if (userProfile == null) {
            return "❌ No se puede generar la rutina\n\n" +
                   "Para crear una rutina personalizada, necesitas configurar tu perfil de usuario primero.\n\n" +
                   "💡 ¿Cómo configurar tu perfil?\n" +
                   "1. Ve a la sección 'Perfil'\n" +
                   "2. Completa tu información personal\n" +
                   "3. Especifica tus objetivos de salud\n" +
                   "4. ¡Listo! Podrás generar rutinas personalizadas"
        }
        
        val userId = userProfile.idUsuario ?: 1L
        val userName = userProfile.nombre.ifBlank { "Usuario" }
        
        
        val genero = when {
            isDefaultValue(userProfile.sexo) -> "No especificado"
            userProfile.sexo.lowercase() in listOf("m", "masculino") -> "Hombre"
            userProfile.sexo.lowercase() in listOf("f", "femenino") -> "Mujer"
            else -> userProfile.sexo
        }
        
        val edad = if (userProfile.fechaNacimiento.isNotBlank()) {
            try {
                val birthYear = userProfile.fechaNacimiento.substring(0, 4).toInt()
                val currentYear = java.time.LocalDate.now().year
                currentYear - birthYear
            } catch (e: Exception) {
                30 // Default age
            }
        } else {
            30 // Default age
        }
        
        val dieta = when {
            isDefaultValue(userProfile.restriccionesDieta) -> "balanceada"
            userProfile.restriccionesDieta.isBlank() -> "balanceada"
            else -> userProfile.restriccionesDieta
        }
        
        val actividad = when {
            isDefaultValue(userProfile.nivelActividad) -> "moderada"
            userProfile.nivelActividad.isBlank() -> "moderada"
            else -> userProfile.nivelActividad
        }
        
        val objetivo = when {
            isDefaultValue(userProfile.objetivosSalud) -> "mantener peso"
            userProfile.objetivosSalud.isBlank() -> "mantener peso"
            else -> userProfile.objetivosSalud
        }
        
        println("=== GENERANDO RUTINA PERSONALIZADA ===")
        println("Usuario: ${userProfile.nombre}")
        println("Peso: ${userProfile.peso} kg")
        println("Altura: ${userProfile.altura} cm")
        println("Objetivo: ${userProfile.objetivosSalud}")
        println("Dieta: ${userProfile.restriccionesDieta}")
        println("¿Es rutina subsecuente? $isSubsequentRoutine")
        
        // Obtener historial de rutinas del usuario
        val userRoutineHistory = routineHistory[userId] ?: mutableListOf()
        println("=== HISTORIAL DE RUTINAS ===")
        println("Rutinas anteriores: ${userRoutineHistory.size}")
        
        // Generar rutina usando API de Gemini con historial para evitar repeticiones
        val prompt = createRoutinePromptWithHistory(userProfile, userRoutineHistory)
        println("=== PROMPT PARA GEMINI CON HISTORIAL ===")
        println("Prompt: ${prompt.take(200)}...")
        
        try {
            val geminiResponse = withContext(Dispatchers.IO) {
                geminiService.generateResponse(prompt, userProfile, null)
            }
            println("=== RESPUESTA DE GEMINI ===")
            println("Respuesta: ${geminiResponse.take(200)}...")
            
            // Guardar rutina en historial
            userRoutineHistory.add(geminiResponse)
            routineHistory[userId] = userRoutineHistory
            
            // Generar mensaje de respuesta según si es la primera vez o no
            val introMessage = if (isSubsequentRoutine) {
                "🤖 Aquí tienes una nueva rutina para ti $userName 🥦\n\n"
            } else {
                "🤖 Perfecto $userName, con base en tu perfil te comparto una rutina pensada para ti 🥦\n\n"
            }
            
            return introMessage + geminiResponse + "\n\n" +
                   "✨ Recuerda hidratarte y mantener un descanso adecuado 💧😴\n\n" +
                   "¿Quieres que te genere otra rutina diferente?"
        } catch (e: Exception) {
            println("=== ERROR EN GEMINI ===")
            println("Error: ${e.message}")
            println("=== USANDO RUTINA DINÁMICA MEJORADA ===")
            
            // Fallback a rutina dinámica mejorada si Gemini falla
            val mealPlan = generateDynamicMealPlan(userProfile)
            
            // Guardar rutina en historial
            userRoutineHistory.add(mealPlan)
            routineHistory[userId] = userRoutineHistory
            
            // Generar mensaje de respuesta según si es la primera vez o no
            val introMessage = if (isSubsequentRoutine) {
                "🤖 Aquí tienes una nueva rutina para ti $userName 🥦\n\n"
            } else {
                "🤖 Perfecto $userName, con base en tu perfil te comparto una rutina pensada para ti 🥦\n\n"
            }
            
            return introMessage + mealPlan + "\n\n" +
                   "✨ Recuerda hidratarte y mantener un descanso adecuado 💧😴\n\n" +
                   "¿Quieres que te genere otra rutina diferente?"
        }
    }
    
    // Crear prompt específico para generar rutina con historial
    private fun createRoutinePromptWithHistory(userProfile: Usuario, routineHistory: List<String>): String {
        val nombre = userProfile.nombre
        val edad = calcularEdad(userProfile.fechaNacimiento)
        val genero = userProfile.sexo
        val peso = userProfile.peso
        val altura = userProfile.altura
        val pesoObjetivo = userProfile.pesoObjetivo
        val objetivo = userProfile.objetivosSalud
        val dieta = userProfile.restriccionesDieta
        val actividad = userProfile.nivelActividad
        
        val historyContext = if (routineHistory.isNotEmpty()) {
            "\n\nRUTINAS ANTERIORES GENERADAS (EVITAR REPETIR):\n" +
            routineHistory.takeLast(3).joinToString("\n\n") { "Rutina anterior:\n$it" } +
            "\n\nIMPORTANTE: Genera una rutina COMPLETAMENTE DIFERENTE a las anteriores. Varía los alimentos, cantidades y horarios."
        } else {
            ""
        }
        
        // Detectar si hay datos faltantes
        val hasIncompleteData = isDefaultValue(userProfile.peso.toString()) || 
                               isDefaultValue(userProfile.altura.toString()) ||
                               isDefaultValue(userProfile.pesoObjetivo.toString())
        
        val dataWarning = if (hasIncompleteData) {
            "\n\n⚠️ IMPORTANTE: El usuario tiene datos incompletos en su perfil. Genera una rutina general balanceada y recomienda completar el perfil para mayor personalización."
        } else ""
        
        return """
        Eres un nutricionista experto. Genera una rutina nutricional personalizada para el siguiente usuario:

        DATOS DEL USUARIO:
        - Nombre: $nombre
        - Edad: $edad años
        - Género: $genero
        - Peso actual: $peso kg
        - Altura: $altura cm
        - Peso objetivo: $pesoObjetivo kg
        - Objetivo de salud: $objetivo
        - Tipo de dieta: $dieta
        - Nivel de actividad: $actividad

        INSTRUCCIONES:
        1. Genera una rutina nutricional completa para TODO EL DÍA
        2. Incluye: Desayuno, Almuerzo, Cena, y 2 Snacks
        3. Especifica cantidades exactas en gramos para cada alimento
        4. Adapta las cantidades al peso del usuario ($peso kg)
        5. Considera el objetivo: $objetivo
        6. Respeta el tipo de dieta: $dieta
        7. Incluye alimentos variados y nutritivos
        8. NO uses emojis en la respuesta
        9. NO uses viñetas (•) ni asteriscos (*)
        10. Usa el formato exacto: "Alimento — Cantidad unidad"
        11. VARÍA los alimentos respecto a rutinas anteriores
        12. Incluye alimentos diferentes y creativos$dataWarning

        FORMATO DE RESPUESTA:
        Desayuno:

        Alimento 1 — Cantidad unidad
        Alimento 2 — Cantidad unidad
        Alimento 3 — Cantidad unidad

        Almuerzo:

        Alimento 1 — Cantidad unidad
        Alimento 2 — Cantidad unidad
        Alimento 3 — Cantidad unidad

        Cena:

        Alimento 1 — Cantidad unidad
        Alimento 2 — Cantidad unidad
        Alimento 3 — Cantidad unidad

        Snack 1: Alimento — Cantidad unidad
        Snack 2: Alimento — Cantidad unidad$historyContext
        """.trimIndent()
    }
    
    // Crear prompt específico para generar rutina con Gemini
    private fun createRoutinePrompt(userProfile: Usuario): String {
        val nombre = userProfile.nombre
        val edad = calcularEdad(userProfile.fechaNacimiento)
        val genero = userProfile.sexo
        val peso = userProfile.peso
        val altura = userProfile.altura
        val pesoObjetivo = userProfile.pesoObjetivo
        val objetivo = userProfile.objetivosSalud
        val dieta = userProfile.restriccionesDieta
        val actividad = userProfile.nivelActividad
        
        return """
        Eres un nutricionista experto. Genera una rutina nutricional personalizada para el siguiente usuario:

        DATOS DEL USUARIO:
        - Nombre: $nombre
        - Edad: $edad años
        - Género: $genero
        - Peso actual: $peso kg
        - Altura: $altura cm
        - Peso objetivo: $pesoObjetivo kg
        - Objetivo de salud: $objetivo
        - Tipo de dieta: $dieta
        - Nivel de actividad: $actividad

        INSTRUCCIONES:
        1. Genera una rutina nutricional completa para TODO EL DÍA
        2. Incluye: Desayuno, Almuerzo, Cena, y 2 Snacks
        3. Especifica cantidades exactas en gramos para cada alimento
        4. Adapta las cantidades al peso del usuario ($peso kg)
        5. Considera el objetivo: $objetivo
        6. Respeta el tipo de dieta: $dieta
        7. Incluye alimentos variados y nutritivos
        8. NO uses emojis en la respuesta
        9. NO uses viñetas (•) ni asteriscos (*)
        10. Usa el formato exacto: "Alimento — Cantidad unidad"

        FORMATO DE RESPUESTA:
        Desayuno:

        Alimento 1 — Cantidad unidad
        Alimento 2 — Cantidad unidad
        Alimento 3 — Cantidad unidad

        Almuerzo:

        Alimento 1 — Cantidad unidad
        Alimento 2 — Cantidad unidad
        Alimento 3 — Cantidad unidad

        Cena:

        Alimento 1 — Cantidad unidad
        Alimento 2 — Cantidad unidad
        Alimento 3 — Cantidad unidad

        Snack 1: Alimento — Cantidad unidad
        Snack 2: Alimento — Cantidad unidad

        Genera una rutina única y personalizada basada en estos datos específicos.
        """.trimIndent()
    }
    
    // Función para calcular la edad
    private fun calcularEdad(fechaNacimiento: String): Int {
        return try {
            val formato = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val fechaNac = formato.parse(fechaNacimiento)
            val hoy = Date()
            val diffInMillies = hoy.time - fechaNac.time
            val diffInDays = diffInMillies / (24 * 60 * 60 * 1000)
            (diffInDays / 365.25).toInt()
        } catch (e: Exception) {
            30 // Edad por defecto si hay error
        }
    }
    
    // Generar rutina dinámica mejorada con más variaciones
    private fun generateDynamicMealPlan(userProfile: Usuario): String {
        val objetivo = userProfile.objetivosSalud.lowercase()
        val dieta = userProfile.restriccionesDieta.lowercase()
        val peso = userProfile.peso
        val altura = userProfile.altura
        
        println("=== GENERANDO RUTINA DINÁMICA MEJORADA ===")
        println("Objetivo: $objetivo, Dieta: $dieta, Peso: $peso kg")
        
        // Generar variación basada en timestamp para más aleatoriedad
        val timestamp = System.currentTimeMillis()
        val variacion = (timestamp % 10).toInt() // 10 variaciones diferentes
        println("=== VARIACIÓN DINÁMICA: $variacion ===")
        
        return when {
            objetivo.contains("ganar") || objetivo.contains("masa") || objetivo.contains("músculo") -> {
                generateMuscleGainVariation(dieta, peso, altura, variacion)
            }
            objetivo.contains("perder") || objetivo.contains("bajar") || objetivo.contains("adelgazar") -> {
                generateWeightLossVariation(dieta, peso, altura, variacion)
            }
            else -> {
                generateMaintenanceVariation(dieta, peso, altura, variacion)
            }
        }
    }
    
    // Generar variaciones para ganancia de masa muscular
    private fun generateMuscleGainVariation(dieta: String, peso: Float, altura: Float, variacion: Int): String {
        val variaciones = listOf(
            "Desayuno:\n\nAvena cocida — 60 gramos\nBanano — 1 unidad\nLeche — 1 vaso\nHuevo cocido — 2 unidades\n\nAlmuerzo:\n\nPollo a la plancha — 120 gramos\nArroz integral — 80 gramos\nEnsalada mixta — 1 taza\nAguacate — 1/2 unidad\n\nCena:\n\nSalmón al horno — 100 gramos\nPuré de papa — 60 gramos\nBrócoli al vapor — 1 taza\n\nSnack 1: Yogur griego — 1 vaso\nSnack 2: Almendras — 30 gramos",
            
            "Desayuno:\n\nPan integral — 2 rebanadas\nMantequilla de maní — 2 cucharadas\nPlátano — 1 unidad\nLeche — 1 vaso\n\nAlmuerzo:\n\nCarne magra — 100 gramos\nQuinoa — 70 gramos\nEnsalada verde — 1 taza\nAceite de oliva — 1 cucharada\n\nCena:\n\nAtún — 90 gramos\nBatata — 80 gramos\nEspinacas — 1 taza\n\nSnack 1: Requesón — 1 taza\nSnack 2: Nueces — 25 gramos",
            
            "Desayuno:\n\nCereal integral — 50 gramos\nLeche — 1 vaso\nFresas — 1 taza\nHuevo revuelto — 2 unidades\n\nAlmuerzo:\n\nPechuga de pavo — 110 gramos\nPasta integral — 75 gramos\nTomate — 1 unidad\nQueso fresco — 30 gramos\n\nCena:\n\nMerluza — 95 gramos\nArroz blanco — 65 gramos\nZanahorias — 1 taza\n\nSnack 1: Kéfir — 1 vaso\nSnack 2: Pistachos — 28 gramos"
        )
        
        return variaciones[variacion % variaciones.size]
    }
    
    // Generar variaciones para pérdida de peso
    private fun generateWeightLossVariation(dieta: String, peso: Float, altura: Float, variacion: Int): String {
        val variaciones = listOf(
            "Desayuno:\n\nAvena — 40 gramos\nLeche descremada — 1 vaso\nManzana — 1 unidad\nHuevo cocido — 1 unidad\n\nAlmuerzo:\n\nPollo a la plancha — 80 gramos\nArroz integral — 50 gramos\nEnsalada mixta — 1 taza\nAceite de oliva — 1 cucharadita\n\nCena:\n\nPescado al vapor — 70 gramos\nVegetales al vapor — 1 taza\n\nSnack 1: Yogur natural — 1 vaso\nSnack 2: Almendras — 15 gramos",
            
            "Desayuno:\n\nPan integral — 1 rebanada\nAguacate — 1/4 unidad\nTomate — 2 rodajas\nTé verde — 1 taza\n\nAlmuerzo:\n\nSalmón — 75 gramos\nQuinoa — 40 gramos\nEspinacas — 1 taza\nLimón — 1 unidad\n\nCena:\n\nPechuga de pollo — 65 gramos\nEnsalada verde — 1 taza\n\nSnack 1: Manzana — 1 unidad\nSnack 2: Nueces — 10 gramos",
            
            "Desayuno:\n\nYogur griego — 1 vaso\nGranola — 30 gramos\nFrutas mixtas — 1/2 taza\n\nAlmuerzo:\n\nAtún en agua — 80 gramos\nArroz integral — 45 gramos\nVegetales — 1 taza\n\nCena:\n\nPescado blanco — 70 gramos\nEnsalada — 1 taza\n\nSnack 1: Pera — 1 unidad\nSnack 2: Almendras — 12 gramos"
        )
        
        return variaciones[variacion % variaciones.size]
    }
    
    // Generar variaciones para mantenimiento
    private fun generateMaintenanceVariation(dieta: String, peso: Float, altura: Float, variacion: Int): String {
        val variaciones = listOf(
            "Desayuno:\n\nAvena — 50 gramos\nLeche — 1 vaso\nBanano — 1 unidad\nHuevo — 1 unidad\n\nAlmuerzo:\n\nPollo — 90 gramos\nArroz integral — 60 gramos\nEnsalada — 1 taza\n\nCena:\n\nPescado — 80 gramos\nVegetales — 1 taza\n\nSnack 1: Yogur — 1 vaso\nSnack 2: Frutos secos — 20 gramos",
            
            "Desayuno:\n\nPan integral — 2 rebanadas\nMantequilla — 1 cucharada\nJugo natural — 1 vaso\n\nAlmuerzo:\n\nCarne magra — 85 gramos\nPasta — 55 gramos\nVegetales — 1 taza\n\nCena:\n\nPollo — 75 gramos\nEnsalada — 1 taza\n\nSnack 1: Fruta — 1 unidad\nSnack 2: Nueces — 15 gramos",
            
            "Desayuno:\n\nCereal — 45 gramos\nLeche — 1 vaso\nFrutas — 1/2 taza\n\nAlmuerzo:\n\nPescado — 85 gramos\nArroz — 50 gramos\nVegetales — 1 taza\n\nCena:\n\nPollo — 80 gramos\nEnsalada — 1 taza\n\nSnack 1: Yogur — 1 vaso\nSnack 2: Almendras — 18 gramos"
        )
        
        return variaciones[variacion % variaciones.size]
    }
    
    // Function to generate meal plan based on user profile
    private fun generateMealPlan(userProfile: Usuario): String {
        val objetivo = userProfile.objetivosSalud.lowercase()
        val actividad = userProfile.nivelActividad.lowercase()
        val dieta = userProfile.restriccionesDieta.lowercase()
        val peso = userProfile.peso
        val altura = userProfile.altura
        
        println("=== GENERANDO PLAN DE COMIDAS DINÁMICO ===")
        println("Objetivo: $objetivo")
        println("Actividad: $actividad")
        println("Dieta: $dieta")
        println("Peso: $peso kg")
        println("Altura: $altura cm")
        
        // Determine meal plan based on user profile
        println("=== EVALUANDO OBJETIVO ===")
        println("Objetivo original: '${userProfile.objetivosSalud}'")
        println("Objetivo lowercase: '$objetivo'")
        
        val mealPlan = when {
            objetivo.contains("ganar") || objetivo.contains("masa") || objetivo.contains("músculo") || 
            objetivo.contains("muscular") || objetivo.contains("crecer") -> {
                println("✅ DETECTADO: Ganancia de masa muscular")
                generateMuscleGainPlan(dieta, peso, altura)
            }
            objetivo.contains("perder") || objetivo.contains("bajar") || objetivo.contains("adelgazar") ||
            objetivo.contains("reducir") || objetivo.contains("dieta") -> {
                println("✅ DETECTADO: Pérdida de peso")
                generateWeightLossPlan(dieta, peso, altura)
            }
            else -> {
                println("✅ DETECTADO: Mantenimiento (objetivo por defecto)")
                generateMaintenancePlan(dieta, peso, altura)
            }
        }
        
        return mealPlan
    }
    
    // Generate meal plan for muscle gain
    private fun generateMuscleGainPlan(dieta: String, peso: Float, altura: Float): String {
        // Calcular necesidades calóricas basadas en peso y altura
        val bmr = 88.362 + (13.397 * peso) + (4.799 * altura) - (5.677 * 30) // Edad estimada 30
        val tdee = bmr * 1.6 // Factor de actividad moderada
        val targetCalories = tdee + 300 // Superávit para ganancia de masa
        
        println("=== GENERANDO RUTINA DINÁMICA PARA GANANCIA DE MASA ===")
        println("Peso: $peso kg, Altura: $altura cm")
        println("BMR: $bmr, TDEE: $tdee, Target: $targetCalories calorías")
        
        return when {
            dieta.contains("vegetariana") || dieta.contains("vegetariano") -> {
                generateVegetarianMuscleGainPlan(peso, targetCalories.toFloat())
            }
            dieta.contains("vegana") || dieta.contains("vegano") -> {
                generateVeganMuscleGainPlan(peso, targetCalories.toFloat())
            }
            else -> {
                generateStandardMuscleGainPlan(peso, targetCalories.toFloat())
            }
        }
    }
    
    // Generate meal plan for weight loss
    private fun generateWeightLossPlan(dieta: String, peso: Float, altura: Float): String {
        // Calcular déficit calórico para pérdida de peso
        val bmr = 88.362 + (13.397 * peso) + (4.799 * altura) - (5.677 * 30)
        val tdee = bmr * 1.6
        val targetCalories = tdee - 500 // Déficit de 500 calorías
        
        println("=== GENERANDO RUTINA DINÁMICA PARA PÉRDIDA DE PESO ===")
        println("Peso: $peso kg, Altura: $altura cm")
        println("BMR: $bmr, TDEE: $tdee, Target: $targetCalories calorías")
        
        return when {
            dieta.contains("vegetariana") || dieta.contains("vegetariano") -> {
                generateVegetarianWeightLossPlan(peso, targetCalories.toFloat())
            }
            else -> {
                generateStandardWeightLossPlan(peso, targetCalories.toFloat())
            }
        }
    }
    
    // Generate meal plan for weight maintenance
    private fun generateMaintenancePlan(dieta: String, peso: Float, altura: Float): String {
        // Calcular calorías de mantenimiento
        val bmr = 88.362 + (13.397 * peso) + (4.799 * altura) - (5.677 * 30)
        val tdee = bmr * 1.6
        val targetCalories = tdee // Mantenimiento
        
        println("=== GENERANDO RUTINA DINÁMICA PARA MANTENIMIENTO ===")
        println("Peso: $peso kg, Altura: $altura cm")
        println("BMR: $bmr, TDEE: $tdee, Target: $targetCalories calorías")
        
        return when {
            dieta.contains("vegetariana") || dieta.contains("vegetariano") -> {
                generateVegetarianMaintenancePlan(peso, targetCalories.toFloat())
            }
            else -> {
                generateStandardMaintenancePlan(peso, targetCalories.toFloat())
            }
        }
    }
    
    // ===== FUNCIONES DE GENERACIÓN DINÁMICA DE RUTINAS =====
    
    // Generar rutina vegetariana para ganancia de masa
    private fun generateVegetarianMuscleGainPlan(peso: Float, targetCalories: Float): String {
        val proteinNeeds = peso * 2.2 // 2.2g por kg de peso
        val carbNeeds = (targetCalories * 0.5) / 4 // 50% carbohidratos
        val fatNeeds = (targetCalories * 0.25) / 9 // 25% grasas
        
        println("=== RUTINA VEGETARIANA GANANCIA DE MASA ===")
        println("Proteína: ${proteinNeeds}g, Carbohidratos: ${carbNeeds}g, Grasas: ${fatNeeds}g")
        
        return "Desayuno:\n\n" +
               "Avena cocida — ${(peso * 0.8).toInt()} gramos\n" +
               "Plátano — 1 unidad\n" +
               "Leche de almendras — 1 vaso\n" +
               "Nueces — ${(peso * 0.3).toInt()} gramos\n\n" +
               "Almuerzo:\n\n" +
               "Quinoa — ${(peso * 0.6).toInt()} gramos\n" +
               "Garbanzos — ${(peso * 1.2).toInt()} gramos\n" +
               "Brócoli al vapor — 1 taza\n" +
               "Aguacate — ½ unidad\n\n" +
               "Cena:\n\n" +
               "Tofu a la plancha — ${(peso * 1.0).toInt()} gramos\n" +
               "Arroz integral — ${(peso * 0.4).toInt()} gramos\n" +
               "Espinacas — 1 taza\n" +
               "Aceite de oliva — 1 cucharada\n\n" +
               "Snack 1: Yogur griego — 1 vaso\n" +
               "Snack 2: Almendras — ${(peso * 0.4).toInt()} gramos"
    }
    
    // Generar rutina vegana para ganancia de masa
    private fun generateVeganMuscleGainPlan(peso: Float, targetCalories: Float): String {
        val proteinNeeds = peso * 2.2
        val carbNeeds = (targetCalories * 0.5) / 4
        val fatNeeds = (targetCalories * 0.25) / 9
        
        println("=== RUTINA VEGANA GANANCIA DE MASA ===")
        println("Proteína: ${proteinNeeds}g, Carbohidratos: ${carbNeeds}g, Grasas: ${fatNeeds}g")
        
        return "Desayuno:\n\n" +
               "Avena con leche de soja — ${(peso * 0.8).toInt()} gramos\n" +
               "Plátano — 1 unidad\n" +
               "Semillas de chía — 2 cucharadas\n" +
               "Nueces — ${(peso * 0.3).toInt()} gramos\n\n" +
               "Almuerzo:\n\n" +
               "Quinoa — ${(peso * 0.6).toInt()} gramos\n" +
               "Lentejas — ${(peso * 1.2).toInt()} gramos\n" +
               "Brócoli al vapor — 1 taza\n" +
               "Aguacate — ½ unidad\n\n" +
               "Cena:\n\n" +
               "Tempeh a la plancha — ${(peso * 1.0).toInt()} gramos\n" +
               "Arroz integral — ${(peso * 0.4).toInt()} gramos\n" +
               "Espinacas — 1 taza\n" +
               "Aceite de oliva — 1 cucharada\n\n" +
               "Snack 1: Leche de soja — 1 vaso\n" +
               "Snack 2: Almendras — ${(peso * 0.4).toInt()} gramos"
    }
    
    // Generar rutina estándar para ganancia de masa
    private fun generateStandardMuscleGainPlan(peso: Float, targetCalories: Float): String {
        val proteinNeeds = peso * 2.2
        val carbNeeds = (targetCalories * 0.5) / 4
        val fatNeeds = (targetCalories * 0.25) / 9
        
        println("=== RUTINA ESTÁNDAR GANANCIA DE MASA ===")
        println("Proteína: ${proteinNeeds}g, Carbohidratos: ${carbNeeds}g, Grasas: ${fatNeeds}g")
        
        // Generar variaciones aleatorias
        val variacion = (System.currentTimeMillis() % 3).toInt()
        
        return when (variacion) {
            0 -> {
                "Desayuno:\n\n" +
                "Avena cocida — ${(peso * 0.8).toInt()} gramos\n" +
                "Banano — 1 unidad\n" +
                "Leche — 1 vaso\n" +
                "Huevo cocido — 1 unidad\n\n" +
                "Almuerzo:\n\n" +
                "Pollo a la plancha — ${(peso * 1.2).toInt()} gramos\n" +
                "Arroz integral — ${(peso * 0.5).toInt()} gramos\n" +
                "Ensalada mixta — 1 taza\n\n" +
                "Cena:\n\n" +
                "Pescado al horno — ${(peso * 1.0).toInt()} gramos\n" +
                "Puré de papa — ${(peso * 0.4).toInt()} gramos\n" +
                "Brócoli al vapor — 1 taza\n\n" +
                "Snack 1: Yogur natural — 1 vaso\n" +
                "Snack 2: Nueces — ${(peso * 0.3).toInt()} gramos"
            }
            1 -> {
                "Desayuno:\n\n" +
                "Pan integral — 2 rebanadas\n" +
                "Huevos revueltos — 2 unidades\n" +
                "Aguacate — ½ unidad\n" +
                "Leche — 1 vaso\n\n" +
                "Almuerzo:\n\n" +
                "Carne magra — ${(peso * 1.1).toInt()} gramos\n" +
                "Quinoa — ${(peso * 0.6).toInt()} gramos\n" +
                "Verduras al vapor — 1 taza\n\n" +
                "Cena:\n\n" +
                "Salmón a la plancha — ${(peso * 0.9).toInt()} gramos\n" +
                "Batata — ${(peso * 0.5).toInt()} gramos\n" +
                "Espinacas — 1 taza\n\n" +
                "Snack 1: Queso cottage — 1 vaso\n" +
                "Snack 2: Almendras — ${(peso * 0.25).toInt()} gramos"
            }
            else -> {
                "Desayuno:\n\n" +
                "Cereal integral — ${(peso * 0.7).toInt()} gramos\n" +
                "Fruta fresca — 1 unidad\n" +
                "Yogur griego — 1 vaso\n" +
                "Miel — 1 cucharada\n\n" +
                "Almuerzo:\n\n" +
                "Pavo a la plancha — ${(peso * 1.3).toInt()} gramos\n" +
                "Pasta integral — ${(peso * 0.4).toInt()} gramos\n" +
                "Ensalada verde — 1 taza\n\n" +
                "Cena:\n\n" +
                "Atún al horno — ${(peso * 1.1).toInt()} gramos\n" +
                "Arroz integral — ${(peso * 0.3).toInt()} gramos\n" +
                "Zanahorias — 1 taza\n\n" +
                "Snack 1: Leche con cacao — 1 vaso\n" +
                "Snack 2: Pistachos — ${(peso * 0.2).toInt()} gramos"
            }
        }
    }
    
    // Generar rutina vegetariana para pérdida de peso
    private fun generateVegetarianWeightLossPlan(peso: Float, targetCalories: Float): String {
        val proteinNeeds = peso * 1.6 // Menos proteína para pérdida
        val carbNeeds = (targetCalories * 0.4) / 4 // Menos carbohidratos
        val fatNeeds = (targetCalories * 0.3) / 9 // Más grasas saludables
        
        println("=== RUTINA VEGETARIANA PÉRDIDA DE PESO ===")
        println("Proteína: ${proteinNeeds}g, Carbohidratos: ${carbNeeds}g, Grasas: ${fatNeeds}g")
        
        return "Desayuno:\n\n" +
               "Avena con frutas — ${(peso * 0.4).toInt()} gramos\n" +
               "Manzana — 1 unidad\n" +
               "Leche descremada — 1 vaso\n\n" +
               "Almuerzo:\n\n" +
               "Ensalada de quinoa — 1 taza\n" +
               "Garbanzos — ${(peso * 0.8).toInt()} gramos\n" +
               "Verduras mixtas — 1 taza\n" +
               "Vinagreta ligera — 1 cucharada\n\n" +
               "Cena:\n\n" +
               "Tofu a la plancha — ${(peso * 0.6).toInt()} gramos\n" +
               "Verduras al vapor — 1 taza\n" +
               "Arroz integral — ${(peso * 0.2).toInt()} gramos\n\n" +
               "Snack 1: Yogur griego bajo en grasa — 1 vaso\n" +
               "Snack 2: Frutos secos — ${(peso * 0.15).toInt()} gramos"
    }
    
    // Generar rutina estándar para pérdida de peso
    private fun generateStandardWeightLossPlan(peso: Float, targetCalories: Float): String {
        val proteinNeeds = peso * 1.6
        val carbNeeds = (targetCalories * 0.4) / 4
        val fatNeeds = (targetCalories * 0.3) / 9
        
        println("=== RUTINA ESTÁNDAR PÉRDIDA DE PESO ===")
        println("Proteína: ${proteinNeeds}g, Carbohidratos: ${carbNeeds}g, Grasas: ${fatNeeds}g")
        
        // Generar variaciones aleatorias
        val variacion = (System.currentTimeMillis() % 3).toInt()
        
        return when (variacion) {
            0 -> {
                "Desayuno:\n\n" +
                "Avena con frutas — ${(peso * 0.4).toInt()} gramos\n" +
                "Manzana — 1 unidad\n" +
                "Leche descremada — 1 vaso\n\n" +
                "Almuerzo:\n\n" +
                "Pollo a la plancha — ${(peso * 0.8).toInt()} gramos\n" +
                "Arroz integral — ${(peso * 0.2).toInt()} gramos\n" +
                "Ensalada verde — 1 taza\n" +
                "Vinagreta ligera — 1 cucharada\n\n" +
                "Cena:\n\n" +
                "Pescado al vapor — ${(peso * 0.7).toInt()} gramos\n" +
                "Verduras al vapor — 1 taza\n" +
                "Quinoa — ${(peso * 0.2).toInt()} gramos\n\n" +
                "Snack 1: Yogur griego bajo en grasa — 1 vaso\n" +
                "Snack 2: Frutos secos — ${(peso * 0.15).toInt()} gramos"
            }
            1 -> {
                "Desayuno:\n\n" +
                "Tostada integral — 1 rebanada\n" +
                "Huevo pochado — 1 unidad\n" +
                "Aguacate — ¼ unidad\n" +
                "Té verde — 1 taza\n\n" +
                "Almuerzo:\n\n" +
                "Pavo a la plancha — ${(peso * 0.7).toInt()} gramos\n" +
                "Ensalada mixta — 1 taza\n" +
                "Vinagreta balsámica — 1 cucharada\n\n" +
                "Cena:\n\n" +
                "Salmón a la plancha — ${(peso * 0.6).toInt()} gramos\n" +
                "Brócoli al vapor — 1 taza\n" +
                "Arroz integral — ${(peso * 0.15).toInt()} gramos\n\n" +
                "Snack 1: Manzana — 1 unidad\n" +
                "Snack 2: Almendras — ${(peso * 0.1).toInt()} gramos"
            }
            else -> {
                "Desayuno:\n\n" +
                "Smoothie verde — 1 vaso\n" +
                "Plátano — ½ unidad\n" +
                "Espinacas — 1 taza\n" +
                "Leche de almendras — 1 vaso\n\n" +
                "Almuerzo:\n\n" +
                "Pechuga de pollo — ${(peso * 0.9).toInt()} gramos\n" +
                "Ensalada de quinoa — 1 taza\n" +
                "Tomate — 1 unidad\n\n" +
                "Cena:\n\n" +
                "Atún al horno — ${(peso * 0.8).toInt()} gramos\n" +
                "Verduras asadas — 1 taza\n" +
                "Aceite de oliva — 1 cucharadita\n\n" +
                "Snack 1: Yogur natural — 1 vaso\n" +
                "Snack 2: Nueces — ${(peso * 0.12).toInt()} gramos"
            }
        }
    }
    
    // Generar rutina vegetariana para mantenimiento
    private fun generateVegetarianMaintenancePlan(peso: Float, targetCalories: Float): String {
        val proteinNeeds = peso * 1.8
        val carbNeeds = (targetCalories * 0.45) / 4
        val fatNeeds = (targetCalories * 0.25) / 9
        
        println("=== RUTINA VEGETARIANA MANTENIMIENTO ===")
        println("Proteína: ${proteinNeeds}g, Carbohidratos: ${carbNeeds}g, Grasas: ${fatNeeds}g")
        
        return "Desayuno:\n\n" +
               "Avena cocida — ${(peso * 0.6).toInt()} gramos\n" +
               "Plátano — 1 unidad\n" +
               "Leche de almendras — 1 vaso\n" +
               "Nueces — ${(peso * 0.2).toInt()} gramos\n\n" +
               "Almuerzo:\n\n" +
               "Quinoa — ${(peso * 0.6).toInt()} gramos\n" +
               "Garbanzos — ${(peso * 1.0).toInt()} gramos\n" +
               "Ensalada mixta — 1 taza\n" +
               "Aguacate — ¼ unidad\n\n" +
               "Cena:\n\n" +
               "Tofu a la plancha — ${(peso * 0.8).toInt()} gramos\n" +
               "Arroz integral — ${(peso * 0.4).toInt()} gramos\n" +
               "Verduras al vapor — 1 taza\n" +
               "Aceite de oliva — 1 cucharada\n\n" +
               "Snack 1: Yogur griego — 1 vaso\n" +
               "Snack 2: Almendras — ${(peso * 0.25).toInt()} gramos"
    }
    
    // Generar rutina estándar para mantenimiento
    private fun generateStandardMaintenancePlan(peso: Float, targetCalories: Float): String {
        val proteinNeeds = peso * 1.8
        val carbNeeds = (targetCalories * 0.45) / 4
        val fatNeeds = (targetCalories * 0.25) / 9
        
        println("=== RUTINA ESTÁNDAR MANTENIMIENTO ===")
        println("Proteína: ${proteinNeeds}g, Carbohidratos: ${carbNeeds}g, Grasas: ${fatNeeds}g")
        
        return "Desayuno:\n\n" +
               "Avena cocida — ${(peso * 0.6).toInt()} gramos\n" +
               "Plátano — 1 unidad\n" +
               "Leche — 1 vaso\n" +
               "Huevo cocido — 1 unidad\n\n" +
               "Almuerzo:\n\n" +
               "Pollo a la plancha — ${(peso * 1.0).toInt()} gramos\n" +
               "Arroz integral — ${(peso * 0.4).toInt()} gramos\n" +
               "Ensalada mixta — 1 taza\n" +
               "Aceite de oliva — 1 cucharada\n\n" +
               "Cena:\n\n" +
               "Pescado al horno — ${(peso * 0.9).toInt()} gramos\n" +
               "Puré de papa — ${(peso * 0.4).toInt()} gramos\n" +
               "Brócoli al vapor — 1 taza\n\n" +
               "Snack 1: Yogur natural — 1 vaso\n" +
               "Snack 2: Nueces — ${(peso * 0.25).toInt()} gramos"
    }
}

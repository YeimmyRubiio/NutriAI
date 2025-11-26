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
import kotlin.math.roundToInt

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
        val currentRoutineFoods: List<RegistroAlimentoSalida>? = null,
        // Rutina personalizada generada
        val generatedRoutine: String? = null,
        val generatedRoutineItems: List<Pair<String, Triple<String, String, String>>>? = null, // Lista de (momento, (alimento, cantidad, idAlimento))
        val viewingDate: String? = null // Fecha de la rutina que el usuario está viendo (null = día actual)
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
        ADD_CONFIRMATION,
        // Estados para el flujo de rutina personalizada
        WAITING_FOR_ROUTINE_CONFIRMATION, // Esperando confirmación para generar rutina
        ROUTINE_GENERATED,
        ROUTINE_CHANGE_COMPLETE,
        ROUTINE_CHANGE_FOOD,
        ROUTINE_CHANGE_CONFIRM,
        ROUTINE_FINALIZE,
        ROUTINE_CANCEL,
        // Estado para cuando el usuario está viendo su rutina actual
        VIEWING_ROUTINE
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
        
        println("=== SEND MESSAGE DEBUG ===")
        println("UserId: $userId")
        println("Mensaje recibido: '${request.mensaje}'")
        println("¿Estado en conversationStates? ${conversationStates.containsKey(userId)}")
        println("Tamaño del mapa conversationStates: ${conversationStates.size}")
        println("Keys en conversationStates: ${conversationStates.keys}")
        if (conversationStates.containsKey(userId)) {
            println("Estado encontrado para userId $userId: ${conversationStates[userId]?.currentStep}")
        } else {
            println("⚠️ NO se encontró estado para userId $userId")
        }
        
        val currentState = conversationStates[userId] ?: ConversationState(userId, NutriAIStep.IDLE)
        println("Current Step: ${currentState.currentStep}")
        println("¿Es ROUTINE_GENERATED? ${currentState.currentStep == NutriAIStep.ROUTINE_GENERATED}")
        
        // Check for "generar rutina" command - HIGHEST PRIORITY (before conversation flow)
        val lowerMessage = request.mensaje.lowercase()
        if (lowerMessage.contains("generar rutina") || 
            lowerMessage.contains("generar rutina personalizada") ||
            lowerMessage.contains("generar una rutina personalizada")) {
            val userName = userProfile?.nombre?.ifBlank { "Usuario" } ?: "Usuario"
            val greeting = if (userName != "Usuario") "¡Perfecto $userName! 🎯" else "¡Perfecto! 🎯"
            
            // Establecer estado de espera de confirmación
            conversationStates[userId] = ConversationState(userId, NutriAIStep.WAITING_FOR_ROUTINE_CONFIRMATION)
            
            return@withContext ChatbotResponse(
                respuesta = "$greeting Le ayudo a crear una rutina nutricional personalizada basada en su perfil actual.\n\n" +
                            generateUserProfileDisplay(userProfile) + "\n\n" +
                            "💡 Responda:\n" +
                            "✨ \"<b>Sí</b>\" o \"<b>Generar</b>\" para crear su rutina personalizada\n" +
                            "❌ \"<b>No</b>\" para cancelar",
                tipoIntento = TipoIntento.Modificar_Rutina,
                tipoAccion = TipoAccion.Agregar
            )
        }
        
        // Check for confirmation to generate routine - SOLO si NO está en otros flujos activos
        // NO interceptar si está en flujos de agregar/cambiar alimento
        val isInAddOrChangeFlow = currentState.currentStep == NutriAIStep.ADD_FOOD_CONFIRMATION ||
                                  currentState.currentStep == NutriAIStep.CHANGE_CONFIRMATION ||
                                  currentState.currentStep == NutriAIStep.CHANGE_CONFIRMATION_NEW ||
                                  currentState.currentStep == NutriAIStep.ROUTINE_CHANGE_CONFIRM
        
        // Verificar si está esperando confirmación de rutina
        val isWaitingForRoutineConfirmation = currentState.currentStep == NutriAIStep.WAITING_FOR_ROUTINE_CONFIRMATION
        
        // Si está esperando confirmación y el mensaje no es válido, mostrar opciones nuevamente
        if (isWaitingForRoutineConfirmation && 
            !lowerMessage.trim().equals("generar", ignoreCase = true) &&
            !lowerMessage.trim().equals("sí", ignoreCase = true) &&
            !lowerMessage.trim().equals("si", ignoreCase = true) &&
            !lowerMessage.trim().equals("yes", ignoreCase = true) &&
            !lowerMessage.trim().equals("no", ignoreCase = true) &&
            !lowerMessage.contains("generar") &&
            !lowerMessage.contains("sí") &&
            !lowerMessage.contains("si")) {
            println("=== MENSAJE NO VÁLIDO EN WAITING_FOR_ROUTINE_CONFIRMATION ===")
            val userName = userProfile?.nombre?.ifBlank { "Usuario" } ?: "Usuario"
            val greeting = if (userName != "Usuario") "¡Perfecto $userName! 🎯" else "¡Perfecto! 🎯"
            
            return@withContext ChatbotResponse(
                respuesta = "No entendí su mensaje. Por favor, escriba una de las opciones disponibles:\n\n" +
                            generateUserProfileDisplay(userProfile) + "\n\n" +
                            "💡 Responda:\n" +
                            "✨ \"<b>Sí</b>\" o \"<b>Generar</b>\" para crear su rutina personalizada\n" +
                            "❌ \"<b>No</b>\" para cancelar",
                tipoIntento = TipoIntento.Modificar_Rutina,
                tipoAccion = TipoAccion.Agregar
            )
        }
        
        if (!isInAddOrChangeFlow && 
            (lowerMessage.trim().equals("generar", ignoreCase = true) || 
             (lowerMessage.trim().equals("sí", ignoreCase = true) && (currentState.currentStep == NutriAIStep.IDLE || currentState.currentStep == NutriAIStep.WAITING_FOR_ROUTINE_CONFIRMATION)) ||
             (lowerMessage.trim().equals("si", ignoreCase = true) && (currentState.currentStep == NutriAIStep.IDLE || currentState.currentStep == NutriAIStep.WAITING_FOR_ROUTINE_CONFIRMATION)) ||
             lowerMessage.trim().equals("yes", ignoreCase = true) ||
             (lowerMessage.contains("sí") && lowerMessage.contains("generar")) ||
             (lowerMessage.contains("si") && lowerMessage.contains("generar")))) {
            println("=== DETECTADO: Confirmación para generar rutina personalizada ===")
            
            if (userProfile == null) {
                return@withContext ChatbotResponse(
                    respuesta = "Lo siento, no se pudo obtener su perfil de usuario. Por favor, inténtelo de nuevo.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
            
            // Generar rutina personalizada
            try {
                val routineResult = generatePersonalizedRoutineFromDatabase(userProfile, userId)
                
                if (routineResult.success) {
                    // Guardar rutina en el estado (resetear de WAITING_FOR_ROUTINE_CONFIRMATION a ROUTINE_GENERATED)
                    conversationStates[userId] = ConversationState(
                        userId = userId,
                        currentStep = NutriAIStep.ROUTINE_GENERATED,
                        generatedRoutine = routineResult.formattedRoutine,
                        generatedRoutineItems = routineResult.routineItems
                    )
                    
                    val optionsText = "\n\nEscriba <b>cambiar rutina</b> si desea generar una nueva rutina.\n" +
                                    "Escriba <b>finalizar</b> para guardar la rutina."
                    
                    return@withContext ChatbotResponse(
                        respuesta = routineResult.formattedRoutine + optionsText,
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                } else {
                    return@withContext ChatbotResponse(
                        respuesta = routineResult.errorMessage ?: "Lo siento, no se pudo generar su rutina personalizada. Por favor, inténtelo de nuevo.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            } catch (e: Exception) {
                println("Error generando rutina: ${e.message}")
                e.printStackTrace()
                return@withContext ChatbotResponse(
                    respuesta = "Lo siento, ocurrió un error al generar su rutina personalizada. Por favor, inténtelo de nuevo más tarde.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
        }
        
        // Check for "cambiar rutina" - HIGHEST PRIORITY (works from any state, especially ROUTINE_GENERATED)
        // IMPORTANTE: Esta verificación debe ir ANTES de "cambiar alimento" para evitar conflictos
        val isCambiarRutinaCommand = lowerMessage.contains("cambiar rutina") && 
                                     !lowerMessage.contains("cambiar alimento") &&
                                     !lowerMessage.contains("modificar alimento")
        
        if (isCambiarRutinaCommand) {
            println("=== ✅ DETECTADO: Cambiar rutina (desde cualquier estado) ===")
            println("Estado actual: ${currentState.currentStep}")
            
            if (userProfile == null) {
                return@withContext ChatbotResponse(
                    respuesta = "Lo siento, no se pudo obtener su perfil de usuario. Por favor, inténtelo de nuevo.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
            
            // Generar nueva rutina personalizada COMPLETA
            try {
                println("🔄 Generando nueva rutina personalizada completa...")
                val routineResult = generatePersonalizedRoutineFromDatabase(userProfile, userId)
                
                if (routineResult.success) {
                    println("✅ Nueva rutina generada exitosamente con ${routineResult.routineItems.size} alimentos")
                    // Actualizar estado con nueva rutina COMPLETA
                    conversationStates[userId] = ConversationState(
                        userId = userId,
                        currentStep = NutriAIStep.ROUTINE_GENERATED,
                        generatedRoutine = routineResult.formattedRoutine,
                        generatedRoutineItems = routineResult.routineItems
                    )
                    
                    val optionsText = "\n\nEscriba <b>cambiar rutina</b> si desea generar una nueva rutina.\n" +
                                    "Escriba <b>finalizar</b> para guardar la rutina."
                    
                    return@withContext ChatbotResponse(
                        respuesta = "✅ <b>Nueva rutina generada:</b>\n\n" +
                                   routineResult.formattedRoutine + optionsText,
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    return@withContext ChatbotResponse(
                        respuesta = routineResult.errorMessage ?: "Lo siento, no se pudo generar una nueva rutina. Por favor, inténtelo de nuevo.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            } catch (e: Exception) {
                println("❌ Error generando nueva rutina: ${e.message}")
                e.printStackTrace()
                return@withContext ChatbotResponse(
                    respuesta = "Lo siento, ocurrió un error al generar la nueva rutina. Por favor, inténtelo de nuevo más tarde.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
        }
        
        // Check for "agregar alimento" and "cambiar alimento" commands - HIGH PRIORITY (but after "cambiar rutina")
        // IMPORTANTE: NO procesar estos comandos si está en ROUTINE_GENERATED (solo permitir "cambiar rutina" y "finalizar")
        // Esto debe ir ANTES de handleConversationFlow para que los comandos tengan prioridad absoluta
        val lowerMsgForCommand = request.mensaje.lowercase().trim()
        // IMPORTANTE: Excluir "cambiar rutina" para evitar conflictos con "cambiar alimento"
        // IMPORTANTE: NO procesar si está en ROUTINE_GENERATED
        val isAgregarAlimentoCommand = (lowerMsgForCommand.contains("agregar alimento") || lowerMsgForCommand.contains("añadir alimento")) &&
                                       !lowerMsgForCommand.contains("cambiar rutina") &&
                                       currentState.currentStep != NutriAIStep.ROUTINE_GENERATED
        val isCambiarAlimentoCommand = (lowerMsgForCommand.contains("cambiar alimento") || lowerMsgForCommand.contains("modificar alimento")) &&
                                       !lowerMsgForCommand.contains("cambiar rutina") &&
                                       currentState.currentStep != NutriAIStep.ROUTINE_GENERATED
        
        // Si es un comando de agregar o cambiar alimento, procesarlo INMEDIATAMENTE sin pasar por otros flujos
        // IMPORTANTE: Si está en VIEWING_ROUTINE, resetear el estado ANTES de procesar para evitar mostrar rutina
        // IMPORTANTE: NO procesar si está en ROUTINE_GENERATED
        if ((isAgregarAlimentoCommand || isCambiarAlimentoCommand) && 
            currentState.currentStep != NutriAIStep.ADD_FOOD_CONFIRMATION &&
            currentState.currentStep != NutriAIStep.CHANGE_CONFIRMATION &&
            currentState.currentStep != NutriAIStep.CHANGE_CONFIRMATION_NEW &&
            currentState.currentStep != NutriAIStep.ROUTINE_GENERATED) {
            println("=== COMANDO 'AGREGAR ALIMENTO' O 'CAMBIAR ALIMENTO' DETECTADO - PRIORIDAD ABSOLUTA ===")
            // Resetear el estado a IDLE si estaba en VIEWING_ROUTINE para evitar mostrar rutina
            val userId = userProfile?.idUsuario ?: 1L
            if (currentState.currentStep == NutriAIStep.VIEWING_ROUTINE) {
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE, viewingDate = currentState.viewingDate)
                println("⚠️ Estado reseteado de VIEWING_ROUTINE a IDLE para procesar comando")
            }
            // Actualizar currentState para que el procesamiento use el estado correcto
            val finalState = conversationStates[userId] ?: currentState
            // Continuar con el procesamiento del comando (no retornar aquí, dejar que el flujo continúe)
        }
        
        // Check if we're in a conversation flow - PRIORITY OVER GEMINI
        // PERO saltar si es un comando de agregar/cambiar alimento (ya se procesará más abajo)
        val shouldSkipConversationFlow = (isAgregarAlimentoCommand || isCambiarAlimentoCommand) && 
                                         currentState.currentStep != NutriAIStep.ADD_FOOD_CONFIRMATION &&
                                         currentState.currentStep != NutriAIStep.CHANGE_CONFIRMATION &&
                                         currentState.currentStep != NutriAIStep.CHANGE_CONFIRMATION_NEW
        
        if (!shouldSkipConversationFlow && currentState.currentStep != NutriAIStep.IDLE) {
            println("=== EN FLUJO DE CONVERSACIÓN - USANDO LÓGICA PASO A PASO ===")
            println("Current Step: ${currentState.currentStep}")
            println("Mensaje: '${request.mensaje}'")
            val flowResponse = handleConversationFlow(request.mensaje, currentState, userProfile, currentRoutine)
            if (flowResponse != null) {
                println("✅ Respuesta del flujo paso a paso: ${flowResponse.respuesta.take(100)}...")
                return@withContext flowResponse
            } else {
                // Si estamos en VIEWING_ROUTINE y retornó null, verificar si es un comando válido
                // Si es "agregar alimento" o "cambiar alimento", NO mostrar rutina, dejar que el flujo continúe
                if (currentState.currentStep == NutriAIStep.VIEWING_ROUTINE) {
                    val lowerMsg = request.mensaje.lowercase().trim()
                    val isAgregarAlimento = lowerMsg.contains("agregar alimento") || lowerMsg.contains("añadir alimento")
                    val isCambiarAlimento = lowerMsg.contains("cambiar alimento")
                    
                    // Si es un comando válido, NO mostrar rutina, dejar que el flujo continúe
                    if (isAgregarAlimento || isCambiarAlimento) {
                        println("⚠️ VIEWING_ROUTINE: Comando válido detectado, continuando con flujo normal sin mostrar rutina")
                        // Resetear el estado a IDLE para que el comando se procese correctamente
                        val userId = userProfile?.idUsuario ?: 1L
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE, viewingDate = currentState.viewingDate)
                        // NO retornar aquí, dejar que el flujo continúe para procesar el comando
                    } else {
                        // Si NO es un comando válido, verificar si el mensaje contiene una nueva fecha
                        println("⚠️ VIEWING_ROUTINE retornó null, verificando si hay nueva fecha en el mensaje")
                        
                        // IMPORTANTE: Leer el estado actualizado (puede haber sido actualizado por el código anterior)
                        val estadoActualizado = conversationStates[userId] ?: currentState
                        val viewingDate = estadoActualizado.viewingDate
                        
                        // También verificar si el mensaje contiene una nueva fecha
                        val fechaEnMensaje = extractDateFromMessage(request.mensaje)
                        val viewingDateFinal = if (fechaEnMensaje != null && fechaEnMensaje.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                            // Validar la fecha
                            try {
                                val parsedDate = LocalDate.parse(fechaEnMensaje, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                val year = parsedDate.year
                                if (year >= 1900 && year <= 2100) {
                                    println("✅ Nueva fecha detectada en mensaje: $fechaEnMensaje")
                                    // Actualizar el estado con la nueva fecha
                                    conversationStates[userId] = estadoActualizado.copy(viewingDate = fechaEnMensaje)
                                    fechaEnMensaje
                                } else {
                                    println("❌ Año inválido: $year")
                                    viewingDate
                                }
                            } catch (e: Exception) {
                                println("❌ Error parseando fecha del mensaje: ${e.message}")
                                viewingDate
                            }
                        } else {
                            viewingDate
                        }
                        
                        val userName = userProfile?.nombre ?: ""
                        val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                        
                        println("=== CONSULTANDO RUTINA PARA VIEWING_ROUTINE ===")
                        println("ViewingDate final: $viewingDateFinal")
                        
                        // Si hay una fecha específica, consultar la rutina de esa fecha
                        val routineToShow = if (viewingDateFinal != null) {
                            val userId = userProfile?.idUsuario ?: 0L
                            println("Consultando rutina para fecha específica: $viewingDateFinal")
                            val rutina = getRoutineForSpecificDate(viewingDateFinal, userId)
                            println("Rutina obtenida: ${rutina?.size ?: 0} elementos")
                            rutina
                        } else {
                            println("No hay fecha específica, usando currentRoutine")
                            currentRoutine
                        }
                        
                        // Determinar si es el día actual
                        val isCurrentDate = viewingDateFinal == null || try {
                            val consultedDate = LocalDate.parse(viewingDateFinal, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                            val today = LocalDate.now()
                            val esHoy = consultedDate == today
                            println("Es fecha actual: $esHoy (consultada: $consultedDate, hoy: $today)")
                            esHoy
                        } catch (e: Exception) {
                            println("Error determinando si es fecha actual: ${e.message}")
                            false
                        }
                        
                        val dateContext = if (viewingDateFinal != null) viewingDateFinal else "hoy"
                        println("DateContext: $dateContext, IsCurrentDate: $isCurrentDate")
                        val routineContent = generateRoutineContent(routineToShow, dateContext, isCurrentDate)
                        
                        // Formato del título según la fecha
                        val today = LocalDate.now()
                        val dateFormatted = if (viewingDate != null) viewingDate else today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        val title = if (isCurrentDate) {
                            "<b>RUTINA DE HOY ($dateFormatted)</b>"
                        } else {
                            "<b>RUTINA DEL $viewingDate</b>"
                        }
                        
                        val optionsText = "\n\n<b>Opciones disponibles:</b>\n\n" +
                                        "Escriba <b>agregar alimento</b> si desea incluir un nuevo alimento.\n\n" +
                                        "Escriba <b>cambiar alimento</b> si desea reemplazar un alimento existente.\n\n" +
                                        "Escriba <b>ver rutina YYYY-MM-DD</b> si desea consultar la rutina de otra fecha.\n" +
                                        "👉 <b>Ejemplo:</b> ver rutina 2025-11-20\n\n" +
                                        "⚠️ <b>Nota:</b> Las opciones de agregar alimento y cambiar alimento solo están disponibles para la rutina del día actual."
                        
                        return@withContext ChatbotResponse(
                            respuesta = "$greeting\n\n$title\n\n$routineContent\n\n$optionsText",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                }
                println("⚠️ handleConversationFlow retornó null, continuando con flujo normal")
            }
        } else {
            println("=== ESTADO IDLE - NO EN FLUJO DE CONVERSACIÓN ===")
        }
        
        // Check for "agregar alimento" and "cambiar alimento" commands - HIGHEST PRIORITY (before any routine checks)
        // Esto debe ir ANTES de todas las verificaciones de rutina para que los comandos tengan prioridad
        // IMPORTANTE: Usar el estado actualizado después de resetear VIEWING_ROUTINE
        val updatedState = conversationStates[userProfile?.idUsuario ?: 1L] ?: currentState
        if ((lowerMessage.contains("agregar alimento") || lowerMessage.contains("añadir alimento")) && 
            updatedState.currentStep != NutriAIStep.ADD_FOOD_CONFIRMATION) {
            val userId = userProfile?.idUsuario ?: 1L
            val userName = userProfile?.nombre ?: ""
            val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
            
            // Validar que solo funcione para el día actual
            val viewingDate = updatedState.viewingDate
            if (viewingDate != null) {
                val isCurrentDate = try {
                    val consultedDate = LocalDate.parse(viewingDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    val today = LocalDate.now()
                    consultedDate == today
                } catch (e: Exception) {
                    false
                }
                
                if (!isCurrentDate) {
                    return@withContext ChatbotResponse(
                        respuesta = "❌ La opción <b>agregar alimento</b> solo está disponible para la rutina del día actual.\n\n" +
                                   "Actualmente está viendo la rutina del <b>$viewingDate</b>.\n\n" +
                                   "Para agregar alimentos, primero consulte la rutina de hoy escribiendo: <b>ver rutina</b> o <b>mostrar su rutina nutricional actual</b>.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            // Iniciar nuevo flujo con categorías - SIN mostrar rutina
            try {
                val categorias = repository.obtenerCategoriasUnicas()
                if (categorias.isNotEmpty()) {
                    conversationStates[userId] = ConversationState(
                        userId = userId,
                        currentStep = NutriAIStep.ADD_SELECT_CATEGORY,
                        availableCategories = categorias,
                        viewingDate = viewingDate
                    )
                    println("=== INICIANDO NUEVO FLUJO AGREGAR ALIMENTO CON CATEGORÍAS PARA USUARIO $userId ===")
                    
                    val categoriasTexto = categorias.joinToString("\n") { "• $it" }
                    return@withContext ChatbotResponse(
                        respuesta = "$greeting ¡Perfecto! Le ayudo a agregar un alimento a su rutina.\n\n" +
                                   "<b>Seleccione una categoría:</b>\n\n" +
                                   "$categoriasTexto",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                } else {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    return@withContext ChatbotResponse(
                        respuesta = "Lo siento, no hay categorías de alimentos disponibles en este momento.\n\n" +
                                   "¿Hay algo más en lo que pueda asistirle?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            } catch (e: Exception) {
                println("Error obteniendo categorías: ${e.message}")
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                return@withContext ChatbotResponse(
                    respuesta = "Lo siento, hubo un problema al obtener las categorías.\n\n" +
                               "¿Hay algo más en lo que pueda asistirle?",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
        }
        
        // IMPORTANTE: Usar el estado actualizado después de resetear VIEWING_ROUTINE
        // Actualizar el estado nuevamente por si cambió en el bloque anterior
        val finalStateForChange = conversationStates[userProfile?.idUsuario ?: 1L] ?: updatedState
        if ((lowerMessage.contains("cambiar alimento") || lowerMessage.contains("modificar alimento")) &&
            finalStateForChange.currentStep != NutriAIStep.CHANGE_CONFIRMATION &&
            finalStateForChange.currentStep != NutriAIStep.CHANGE_CONFIRMATION_NEW) {
            val userId = userProfile?.idUsuario ?: 1L
            val userName = userProfile?.nombre ?: ""
            val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
            
            // Validar que solo funcione para el día actual
            val viewingDate = finalStateForChange.viewingDate
            if (viewingDate != null) {
                val isCurrentDate = try {
                    val consultedDate = LocalDate.parse(viewingDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    val today = LocalDate.now()
                    consultedDate == today
                } catch (e: Exception) {
                    false
                }
                
                if (!isCurrentDate) {
                    return@withContext ChatbotResponse(
                        respuesta = "❌ La opción <b>cambiar alimento</b> solo está disponible para la rutina del día actual.\n\n" +
                                   "Actualmente está viendo la rutina del <b>$viewingDate</b>.\n\n" +
                                   "Para cambiar alimentos, primero consulte la rutina de hoy escribiendo: <b>ver rutina</b> o <b>mostrar su rutina nutricional actual</b>.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            println("=== INICIANDO NUEVO FLUJO CAMBIAR ALIMENTO PARA USUARIO $userId ===")
            
            // Obtener TODOS los alimentos de la rutina actual
            val alimentosEnRutina = currentRoutine?.filter { it != null } ?: emptyList()
            
            if (alimentosEnRutina.isEmpty()) {
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                return@withContext ChatbotResponse(
                    respuesta = "$greeting No tiene alimentos registrados actualmente en su rutina.\n\n" +
                               "¿Le gustaría agregar un alimento a su rutina?",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Agregar
                )
            }
            
            // Mostrar la rutina del día actual con el formato correcto
            val today = LocalDate.now()
            val dateFormatted = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val routineContent = generateRoutineContent(currentRoutine, "hoy", true)
            
            // Mostrar todos los alimentos de la rutina para que el usuario seleccione cuál cambiar
            val alimentosTexto = alimentosEnRutina.joinToString("\n") { 
                "• <b>${it.alimento.nombreAlimento}</b> - ${it.momentoDelDia}" 
            }
            
            conversationStates[userId] = ConversationState(
                userId = userId,
                currentStep = NutriAIStep.CHANGE_SELECT_ORIGINAL_FOOD,
                currentRoutineFoods = alimentosEnRutina
            )
            
            return@withContext ChatbotResponse(
                respuesta = "$greeting ¡Perfecto! Le ayudo a cambiar un alimento en su rutina.\n\n" +
                           "<b>RUTINA DE HOY ($dateFormatted)</b>\n\n" +
                           "$routineContent\n\n" +
                           "📝 <b>¿Qué alimento desea cambiar?</b>\n" +
                           "(Escriba el nombre del alimento que desea reemplazar)",
                tipoIntento = TipoIntento.Modificar_Rutina,
                tipoAccion = TipoAccion.Modificar
            )
        }
        
        // Check for "No" response to generate routine - HIGH PRIORITY
        // Verificar si está esperando confirmación de rutina
        if ((lowerMessage.trim().equals("no", ignoreCase = true) && 
             (currentState.currentStep == NutriAIStep.WAITING_FOR_ROUTINE_CONFIRMATION || currentState.routineCount == 0))) {
            println("=== DETECTADO: Usuario declina generar rutina personalizada ===")
            val userName = userProfile?.nombre?.ifBlank { "Usuario" } ?: "Usuario"
            val greeting = if (userName != "Usuario") "Entendido, $userName." else "Entendido."
            
            // Resetear el estado
            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
            
            return@withContext ChatbotResponse(
                respuesta = "$greeting No hay problema. Si en algún momento desea generar una rutina personalizada, solo indíqueme 'Generar una rutina personalizada para usted' y le ayudaré.",
                tipoIntento = TipoIntento.Otros,
                tipoAccion = null
            )
        }
        
        // Check for response to "generate another routine" question
        // NOTA: La generación de rutina está deshabilitada temporalmente
        if (lowerMessage.contains("otra rutina") || lowerMessage.contains("rutina diferente") ||
            lowerMessage.contains("generar otra") || lowerMessage.contains("otra diferente")) {
            if (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("generar")) {
                println("=== DETECTADO: Usuario quiere generar otra rutina ===")
                val userName = userProfile?.nombre?.ifBlank { "Usuario" } ?: "Usuario"
                val greeting = if (userName != "Usuario") "Entendido, $userName." else "Entendido."
                
                return@withContext ChatbotResponse(
                    respuesta = "$greeting La generación de rutina personalizada estará disponible próximamente.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            } else if (lowerMessage.contains("no")) {
                println("=== DETECTADO: Usuario no quiere generar otra rutina ===")
                return@withContext ChatbotResponse(
                    respuesta = "Perfecto, no hay problema. Si en algún momento desea generar una nueva rutina personalizada, solo indíqueme 'Generar' y le ayudaré.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
        }
        
        // Check for "No" response to generate routine - HIGH PRIORITY (duplicado - ya se procesó arriba)
        // Esta verificación ya se hizo anteriormente, no es necesario procesarla de nuevo
        
        // Check for response to "generate another routine" question
        // NOTA: La generación de rutina está deshabilitada temporalmente
        if (lowerMessage.contains("otra rutina") || lowerMessage.contains("rutina diferente") ||
            lowerMessage.contains("generar otra") || lowerMessage.contains("otra diferente")) {
            if (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("generar")) {
                println("=== DETECTADO: Usuario quiere generar otra rutina ===")
                val userName = userProfile?.nombre?.ifBlank { "Usuario" } ?: "Usuario"
                val greeting = if (userName != "Usuario") "Entendido, $userName." else "Entendido."
                
                return@withContext ChatbotResponse(
                    respuesta = "$greeting La generación de rutina personalizada estará disponible próximamente.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            } else if (lowerMessage.contains("no")) {
                println("=== DETECTADO: Usuario no quiere generar otra rutina ===")
                return@withContext ChatbotResponse(
                    respuesta = "Perfecto, no hay problema. Si en algún momento desea generar una nueva rutina personalizada, solo indíqueme 'Generar' y le ayudaré.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
        }
        
        // Verificar si es una solicitud de rutina - usar fallback directo
        // IMPORTANTE: Excluir "agregar alimento" y "cambiar alimento" para que no se confundan con solicitudes de rutina
        // También verificar que no estemos procesando un comando válido después de resetear VIEWING_ROUTINE
        val isRoutineRequest = (lowerMessage.contains("mostrar rutina") || 
                              lowerMessage.contains("ver rutina") || 
                              lowerMessage.contains("mi rutina") || 
                              lowerMessage.contains("rutina de hoy") ||
                              lowerMessage.contains("rutina del") ||
                              lowerMessage.contains("rutina de")) &&
                              !lowerMessage.contains("agregar alimento") &&
                              !lowerMessage.contains("cambiar alimento") &&
                              !lowerMessage.contains("añadir alimento") &&
                              !isAgregarAlimentoCommand &&
                              !isCambiarAlimentoCommand
        
         // Detectar si es una solicitud de rutina con fecha específica
         val datePattern = extractDateFromMessage(request.mensaje)
        
        // Validar si la fecha es realmente válida (formato y rango de año)
        var isValidDate = false
        if (datePattern != null) {
            try {
                val parsedDate = LocalDate.parse(datePattern, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                // Validar que el año esté en un rango razonable (1900-2100)
                val year = parsedDate.year
                if (year >= 1900 && year <= 2100) {
                    isValidDate = true
                    println("✅ Fecha válida: $datePattern (año: $year)")
                } else {
                    println("❌ Fecha inválida: $datePattern - Año $year fuera del rango permitido (1900-2100)")
                    isValidDate = false
                }
            } catch (e: DateTimeParseException) {
                println("❌ Fecha inválida: $datePattern - ${e.message}")
                isValidDate = false
            }
        }
         // Detectar formato "ver rutina YYYY-MM-DD" específico (solo formato, no valida la fecha)
         val isVerRutinaFormat = lowerMessage.matches(Regex("ver rutina \\d{4}-\\d{2}-\\d{2}"))
         // Detectar "ver rutina" sin fecha (para mostrar rutina de hoy)
         val isVerRutinaHoy = (lowerMessage == "ver rutina" || lowerMessage.contains("mostrar su rutina nutricional actual")) && 
                             !lowerMessage.contains("\\d{4}-\\d{2}-\\d{2}")
         
         val isSpecificDateRoutine = lowerMessage.contains("ver rutina") && datePattern != null && isValidDate
         
         println("=== DEBUGGING DETECCIÓN DE RUTINA ===")
         println("Mensaje original: ${request.mensaje}")
         println("LowerMessage: $lowerMessage")
         println("DatePattern extraído: $datePattern")
         println("IsValidDate: $isValidDate")
         println("¿Es ver rutina con fecha? $isVerRutinaFormat")
         println("¿Es ver rutina de hoy? $isVerRutinaHoy")
         println("¿Es rutina con fecha específica válida? $isSpecificDateRoutine")
         println("¿Contiene 'ver rutina'? ${lowerMessage.contains("ver rutina")}")
         println("¿Contiene fecha en formato YYYY-MM-DD? ${lowerMessage.matches(Regex(".*\\d{4}-\\d{2}-\\d{2}.*"))}")
         
         // Detectar fechas inválidas ANTES de cualquier otro procesamiento
         // Si el mensaje contiene "ver rutina" y hay una fecha pero es inválida, mostrar error
         if (lowerMessage.contains("ver rutina") && datePattern != null && !isValidDate) {
             println("=== DETECTADO: Fecha inválida en sendMessage ===")
             val userName = userProfile?.nombre ?: ""
             val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
             
             // Validar si la fecha tiene un año inválido (muy lejano en el futuro o pasado)
             val year = try {
                 datePattern.substring(0, 4).toInt()
             } catch (e: Exception) {
                 null
             }
             
             val errorMessage = if (year != null && (year < 1900 || year > 2100)) {
                 "$greeting\n\n❌ <b>La fecha ingresada no es válida.</b>\n\n" +
                 "El año <b>$year</b> está fuera del rango permitido (1900-2100).\n\n" +
                 "👉 <b>Ejemplo válido:</b> ver rutina 2025-10-20"
             } else {
                 "$greeting\n\n❌ <b>La fecha ingresada no es válida.</b>\n\n" +
                 "Use el formato <b>AAAA-MM-DD</b> (año-mes-día).\n\n" +
                 "👉 <b>Ejemplo:</b> ver rutina 2025-10-20"
             }
             
             return@withContext ChatbotResponse(
                 respuesta = errorMessage,
                 tipoIntento = TipoIntento.Otros,
                 tipoAccion = null
             )
         }
         
         // Manejar "ver rutina" sin fecha (mostrar rutina de hoy)
         if (isVerRutinaHoy) {
             println("=== DETECTADO: Ver rutina de hoy ===")
             val userId = userProfile?.idUsuario ?: 1L
             conversationStates[userId] = ConversationState(userId, NutriAIStep.VIEWING_ROUTINE, viewingDate = null)
             val fallbackResponse = generateRoutineResponse(userProfile, currentRoutine, null)
             return@withContext ChatbotResponse(
                 respuesta = fallbackResponse,
                 tipoIntento = request.tipoIntento ?: determineIntent(request.mensaje),
                 tipoAccion = determineAction(request.mensaje),
                 tema = "Rutina alimentaria"
             )
         }
         
         // Manejar formato "ver rutina YYYY-MM-DD" específico
         // IMPORTANTE: Esta condición debe tener PRIORIDAD sobre otras condiciones
         // IMPORTANTE: Solo procesar si la fecha es válida
         if (isVerRutinaFormat) {
             println("=== DETECTADO: Formato 'ver rutina YYYY-MM-DD' (PRIORIDAD ALTA) ===")
             val extractedDate = extractDateFromMessage(request.mensaje)
             println("Fecha extraída: $extractedDate")
             
             if (extractedDate != null) {
                 // Validar la fecha extraída directamente
                 var isExtractedDateValid = false
                 try {
                     val parsedDate = LocalDate.parse(extractedDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                     val year = parsedDate.year
                     if (year >= 1900 && year <= 2100) {
                         isExtractedDateValid = true
                         println("✅ Fecha extraída válida: $extractedDate (año: $year)")
                         println("✅ Fecha parseada: $parsedDate")
                     } else {
                         println("❌ Fecha extraída inválida: año $year fuera del rango")
                     }
                 } catch (e: Exception) {
                     println("❌ Error parseando fecha extraída: ${e.message}")
                     e.printStackTrace()
                 }
                 
                 if (isExtractedDateValid) {
                     // Establecer estado VIEWING_ROUTINE con la fecha consultada
                     val userId = userProfile?.idUsuario ?: 1L
                     conversationStates[userId] = ConversationState(userId, NutriAIStep.VIEWING_ROUTINE, viewingDate = extractedDate)
                     println("=== CONSULTANDO RUTINA PARA FECHA ESPECÍFICA: $extractedDate ===")
                     println("=== Usuario ID: $userId ===")
                     // IMPORTANTE: Pasar null como currentRoutine para que consulte la base de datos para la fecha específica
                     val fallbackResponse = generateRoutineResponse(userProfile, null, extractedDate)
                     println("✅ Respuesta generada para fecha $extractedDate")
                     println("=== TAMAÑO DE RESPUESTA: ${fallbackResponse.length} caracteres ===")
                     return@withContext ChatbotResponse(
                         respuesta = fallbackResponse,
                         tipoIntento = request.tipoIntento ?: determineIntent(request.mensaje),
                         tipoAccion = determineAction(request.mensaje),
                         tema = "Rutina alimentaria"
                     )
                 } else {
                     // Fecha inválida
                     val userName = userProfile?.nombre ?: ""
                     val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                     
                     val year = try {
                         extractedDate.substring(0, 4).toInt()
                     } catch (e: Exception) {
                         null
                     }
                     
                     val errorMessage = if (year != null && (year < 1900 || year > 2100)) {
                         "$greeting\n\n❌ <b>La fecha ingresada no es válida.</b>\n\n" +
                         "El año <b>$year</b> está fuera del rango permitido (1900-2100).\n\n" +
                         "👉 <b>Ejemplo válido:</b> ver rutina 2025-10-20"
                     } else {
                         "$greeting\n\n❌ <b>La fecha ingresada no es válida.</b>\n\n" +
                         "Use el formato <b>AAAA-MM-DD</b> (año-mes-día).\n\n" +
                         "👉 <b>Ejemplo:</b> ver rutina 2025-10-20"
                     }
                     
                     return@withContext ChatbotResponse(
                         respuesta = errorMessage,
                         tipoIntento = TipoIntento.Otros,
                         tipoAccion = null
                     )
                 }
             } else {
                 val userName = userProfile?.nombre ?: ""
                 val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                 val message = "$greeting\n\nNo se pudo extraer la fecha del mensaje. Por favor, use el formato YYYY-MM-DD.\n\n👉 <b>Ejemplo:</b> ver rutina 2025-10-05"
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
             val message = "$greeting\n\nPara ver su rutina en una fecha específica, escriba:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
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
        
        // IMPORTANTE: Procesar fechas específicas ANTES de rutinas de hoy
        // Si hay una fecha válida en el mensaje "ver rutina", procesarla primero
        if (isSpecificDateRoutine && !isVerRutinaFormat) {
            // Si isVerRutinaFormat ya procesó el mensaje, no procesar aquí
            println("=== DETECTADA SOLICITUD DE RUTINA CON FECHA ESPECÍFICA - USANDO FALLBACK DIRECTO ===")
            println("DatePattern detectado: $datePattern")
            println("UserProfile ID: ${userProfile?.idUsuario}")
            // IMPORTANTE: Pasar null como currentRoutine para que consulte la base de datos para la fecha específica
            val userId = userProfile?.idUsuario ?: 1L
            conversationStates[userId] = ConversationState(userId, NutriAIStep.VIEWING_ROUTINE, viewingDate = datePattern)
            val fallbackResponse = generateRoutineResponse(userProfile, null, datePattern)
            println("✅ Respuesta de fallback para rutina de fecha específica: $fallbackResponse")
            
            return@withContext ChatbotResponse(
                respuesta = fallbackResponse,
                tipoIntento = request.tipoIntento ?: determineIntent(request.mensaje),
                tipoAccion = determineAction(request.mensaje),
                tema = "Rutina alimentaria"
            )
        }
        
        // Solo usar bypass directo para rutinas de "hoy", no para fechas específicas
        // IMPORTANTE: Excluir comandos de agregar/cambiar alimento y fechas específicas
        val isTodayRoutine = (lowerMessage.contains("mi rutina") || 
                           lowerMessage.contains("rutina de hoy") ||
                           lowerMessage.contains("mostrar rutina nutricional") ||
                           lowerMessage.contains("mostrar su rutina nutricional actual") ||
                           lowerMessage.contains("rutina nutricional actual") ||
                           (lowerMessage.contains("ver rutina") && !lowerMessage.contains("/") && !lowerMessage.contains("-") && datePattern == null)) &&
                           !lowerMessage.contains("agregar alimento") &&
                           !lowerMessage.contains("cambiar alimento") &&
                           !lowerMessage.contains("añadir alimento") &&
                           !isAgregarAlimentoCommand &&
                           !isCambiarAlimentoCommand &&
                           !isVerRutinaFormat &&
                           !isSpecificDateRoutine
        
        if (isTodayRoutine) {
            println("=== DETECTADA SOLICITUD DE RUTINA DE HOY - USANDO FALLBACK DIRECTO ===")
            // Establecer estado para manejar errores después (viewingDate = null para día actual)
            val userId = userProfile?.idUsuario ?: 1L
            conversationStates[userId] = ConversationState(userId, NutriAIStep.VIEWING_ROUTINE, viewingDate = null)
            val fallbackResponse = generateSpecificResponse(request.mensaje, userProfile, currentRoutine)
            println("✅ Respuesta de fallback para rutina de hoy: $fallbackResponse")
            
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
            
            // Detectar fechas o entradas erróneas ANTES del fallback (DEBE IR ANTES DE GEMINI)
            // PERO NO interceptar si estamos en ROUTINE_GENERATED (para permitir opciones 1, 2, 3, 4)
            if (currentState.currentStep != NutriAIStep.ROUTINE_GENERATED) {
                if (lowerMessage.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || 
                    lowerMessage.matches(Regex("\\d{2}/\\d{2}/\\d{4}")) ||
                    lowerMessage.matches(Regex("\\d{2}-\\d{2}-\\d{4}")) ||
                    (lowerMessage.length <= 10 && lowerMessage.any { it.isDigit() } && !lowerMessage.matches(Regex("^[1-4]$")))) {
                    println("=== DETECTADO: Fecha errónea o entrada incorrecta ===")
                    val userName = userProfile?.nombre ?: ""
                    val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                    return@withContext ChatbotResponse(
                        respuesta = "$greeting Para consultar su rutina nutricional, use el formato correcto:\n\n" +
                                    "📅 <b>¿Desea ver la rutina de otra fecha?</b>\n" +
                                    "Escriba: \"Ver rutina 2025-10-01\" (formato: YYYY-MM-DD)",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
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
                "$greeting\n\nEsa fecha parece contener un error. Para ver su rutina en una fecha específica, escriba:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
            }
            
            // Detectar "ver rurina" (con error tipográfico)
            lowerMessage.contains("ver rurina") -> {
                println("=== DETECTADO: 'ver rurina' con error tipográfico ===")
                println("Mensaje: $message")
                val userName = userProfile?.nombre ?: ""
                val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                "$greeting\n\nPara ver su rutina en una fecha específica, escriba:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
            }
            
            // Detectar fechas que parecen fechas pero no contienen "ver rutina"
            lowerMessage.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && !lowerMessage.contains("ver rutina") -> {
                println("=== DETECTADO: Fecha sin 'ver rutina' ===")
                println("Mensaje: $message")
                val userName = userProfile?.nombre ?: ""
                val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                "$greeting\n\nPara ver su rutina en una fecha específica, escriba:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
            }
            
            // Detectar fechas o entradas erróneas sin formato correcto
            // NO interceptar números simples 1-4 (opciones del chatbot)
            lowerMessage.matches(Regex("\\d{2}/\\d{2}/\\d{4}")) ||
            lowerMessage.matches(Regex("\\d{2}-\\d{2}-\\d{4}")) ||
            (lowerMessage.length <= 10 && lowerMessage.any { it.isDigit() } && !lowerMessage.matches(Regex("^[1-4]$"))) -> {
                println("=== DETECTADO: Fecha errónea o entrada incorrecta ===")
                val userName = userProfile?.nombre ?: ""
                val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                "$greeting\n\nPara ver su rutina en una fecha específica, escriba:\nVer rutina YYYY-MM-DD (por ejemplo: Ver rutina 2025-10-01)"
            }
            
            // Arroz y granos
            lowerMessage.contains("arroz integral") || lowerMessage.contains("arroz") -> 
                "¡Sí, el arroz integral es excelente! Es mucho mejor que el arroz blanco porque conserva la fibra, vitaminas y minerales. Tiene más nutrientes, le proporciona energía sostenida y ayuda con la digestión. Es una excelente fuente de carbohidratos complejos. ¿Le gustaría saber cómo incluirlo en sus comidas?"
            
            lowerMessage.contains("quinoa") -> 
                "La quinoa es un superalimento completo. Tiene proteínas de alta calidad, fibra, vitaminas y minerales. Es perfecta para vegetarianos y veganos. ¿Le interesa saber cómo prepararla?"
            
            lowerMessage.contains("avena") || lowerMessage.contains("oatmeal") -> 
                "La avena es fantástica para el desayuno. Tiene fibra soluble que ayuda a controlar el colesterol y le proporciona energía duradera. Es rica en proteínas y le mantiene saciado. ¿Desea ideas de cómo prepararla?"
            
            // Carbohidratos
            lowerMessage.contains("carbohidratos") || lowerMessage.contains("carbohidrato") -> 
                "Los carbohidratos son la principal fuente de energía para su cuerpo. Se dividen en simples (azúcares) y complejos (almidones). Los carbohidratos complejos como arroz integral, avena y quinoa son mejores porque le proporcionan energía sostenida. ¿Le gustaría saber más sobre cómo incluirlos en su dieta?"
            
            // Proteínas
            lowerMessage.contains("proteínas") || lowerMessage.contains("proteína") -> 
                "Las proteínas son esenciales para construir y reparar músculos. Las encuentras en carnes, pescados, huevos, legumbres y lácteos. Para una dieta balanceada, incluye proteína en cada comida. ¿Necesitas sugerencias de fuentes de proteína específicas?"
            
            // Grasas
            lowerMessage.contains("grasas") || lowerMessage.contains("grasa") -> 
                "Las grasas son importantes para su salud, especialmente las grasas buenas como aguacate, nueces, aceite de oliva y pescados grasos. Evite las grasas trans y consuma grasas saturadas con moderación. ¿Desea saber qué grasas incluir en su dieta?"
            
            // Calorías
            lowerMessage.contains("calorías") || lowerMessage.contains("caloría") -> 
                "Las calorías son la energía que necesita su cuerpo. Para mantener un peso saludable, necesita equilibrar las calorías que consume con las que gasta. ¿Le gustaría que le ayude a calcular sus necesidades calóricas?"
            
            // Comidas específicas
            lowerMessage.contains("desayuno") -> 
                "El desayuno es muy importante para empezar el día con energía. Un buen desayuno incluye proteínas, carbohidratos complejos y algo de grasa saludable. ¿Le gustaría sugerencias específicas para su desayuno?"
            
            lowerMessage.contains("almuerzo") -> 
                "El almuerzo debe ser balanceado con proteínas, carbohidratos y verduras. Es la comida principal del día, así que asegúrese de incluir todos los macronutrientes. ¿Necesita ideas para su almuerzo?"
            
            lowerMessage.contains("cena") -> 
                "La cena debe ser más ligera que el almuerzo. Incluya proteínas magras con verduras y una porción moderada de carbohidratos. Evite comidas muy pesadas antes de dormir. ¿Qué le gustaría cenar hoy?"
            
            // Hidratación
            lowerMessage.contains("agua") || lowerMessage.contains("hidratación") -> 
                "El agua es esencial para su cuerpo. Se recomienda beber al menos 8 vasos de agua al día, más si hace ejercicio. ¿Está bebiendo suficiente agua durante el día?"
            
            // Frutas y verduras
            lowerMessage.contains("frutas") || lowerMessage.contains("fruta") -> 
                "Las frutas son excelentes fuentes de vitaminas, minerales y fibra. Son naturales, bajas en calorías y le proporcionan energía. ¿Le gustaría saber cuáles son las mejores frutas para incluir en su dieta?"
            
            lowerMessage.contains("verduras") || lowerMessage.contains("vegetales") -> 
                "Las verduras son fundamentales para una dieta saludable. Tienen pocas calorías, mucha fibra, vitaminas y minerales. ¿Desea saber cómo incluir más verduras en sus comidas?"
            
            // Masa muscular y fitness
            lowerMessage.contains("masa muscular") || lowerMessage.contains("ganar músculo") || lowerMessage.contains("músculo") -> 
                "Para ganar masa muscular necesita un excedente calórico y suficiente proteína. Le recomiendo: 1.6-2.2g de proteína por kg de peso, carbohidratos para energía, y entrenamiento de fuerza. ¿Le gustaría un plan específico de alimentación para ganar músculo?"
            
            lowerMessage.contains("perder peso") || lowerMessage.contains("adelgazar") || lowerMessage.contains("bajar peso") -> 
                "Para perder peso de forma saludable necesita un déficit calórico moderado (300-500 calorías menos al día), proteína suficiente para mantener músculo, y ejercicio regular. ¿Desea que le ayude con un plan específico?"
            
            lowerMessage.contains("dieta") || lowerMessage.contains("alimentación") -> 
                "Una dieta equilibrada incluye proteínas, carbohidratos complejos, grasas saludables, frutas y verduras. ¿Tiene algún objetivo específico como ganar músculo, perder peso, o mantener su peso actual?"
            
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
                    // IMPORTANTE: Pasar null como currentRoutine para que consulte la base de datos para la fecha específica
                    return generateRoutineResponse(userProfile, null, datePattern)
                } else {
                    val userName = userProfile?.nombre ?: ""
                    val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                    return "$greeting Para mostrarte la rutina de una fecha específica, necesito que me indiques la fecha en formato DD/MM/YYYY.\n\n" +
                    "📅 <b>Ejemplo de formato de fecha:</b>\n" +
                    "• \"05/10/2025\"\n\n" +
                    "💡 <b>Formato requerido:</b> DD/MM/YYYY (día/mes/año)\n\n" +
                    "¿De qué fecha le gustaría ver la rutina? (ejemplo: 05/10/2025)"
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
                    // IMPORTANTE: Pasar null como currentRoutine para que consulte la base de datos para la fecha específica
                    return generateRoutineResponse(userProfile, null, datePattern)
                } else {
                    val userName = userProfile?.nombre ?: ""
                    val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                    return "$greeting Para mostrarte la rutina de una fecha específica, necesito que me indiques la fecha en formato DD/MM/YYYY.\n\n" +
                    "📅 <b>Ejemplo de formato de fecha:</b>\n" +
                    "• \"05/10/2025\"\n\n" +
                    "💡 <b>Formato requerido:</b> DD/MM/YYYY (día/mes/año)\n\n" +
                    "¿De qué fecha le gustaría ver la rutina? (ejemplo: 05/10/2025)"
                }
            }
            
             // Gestión de rutina nutricional SIN fecha específica (solo "hoy")
             lowerMessage.contains("mi rutina") || lowerMessage.contains("rutina de hoy") ||
             lowerMessage.contains("mostrar rutina nutricional") ||
             lowerMessage.contains("mostrar su rutina nutricional actual") ||
             lowerMessage.contains("rutina nutricional actual") ||
             (lowerMessage.contains("ver rutina") && !lowerMessage.contains("/") && !lowerMessage.contains("-")) -> {
                 println("=== DETECTADO: Mostrar rutina nutricional ===")
                 println("UserProfile recibido: $userProfile")
                 println("Nombre del usuario: ${userProfile?.nombre}")
                 // Establecer estado para manejar errores después (viewingDate = null para día actual)
                 val userIdForState = userProfile?.idUsuario ?: 1L
                 conversationStates[userIdForState] = ConversationState(userIdForState, NutriAIStep.VIEWING_ROUTINE, viewingDate = null)
                 // generateRoutineResponse ya incluye las opciones cuando es rutina de hoy
                 return generateRoutineResponse(userProfile, currentRoutine, null)
             }
            
            
            // Manejar confirmaciones de cambios
            (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("confirmo") || 
             lowerMessage.contains("confirmar")) && lowerMessage.contains("cambio") -> {
                // Aquí se podría integrar con el ChatbotViewModel para registrar el cambio
                // Por ahora, solo mostramos el mensaje de confirmación
                "¡Perfecto! He registrado su cambio en la rutina.\n\n" +
                "✅ <b>Cambio aplicado exitosamente</b>\n\n" +
                "💡 <b>Nota:</b> Para que los cambios se reflejen en su rutina, asegúrese de actualizar la pantalla de rutina.\n\n" +
                "¿Le gustaría hacer algún otro cambio en su rutina o necesita ayuda con algo más?"
            }
            
            
            
            // Manejar cancelaciones de cambios
            (lowerMessage.contains("no") || lowerMessage.contains("cancelar") || lowerMessage.contains("cancel")) && 
            lowerMessage.contains("cambio") -> {
                "Entendido, no se realizará ningún cambio.\n\n" +
                "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?"
            }
            
            // Manejar solicitudes completas de modificación con todos los datos
            isCompleteModificationRequest(message) -> {
                
                val modificationData = parseModificationRequest(message)
                return if (modificationData != null) {
                    "¡Perfecto! Entiendo que desea:\n\n" +
                    "🔄 <b>Modificación solicitada:</b>\n" +
                    "• <b>Acción:</b> ${modificationData.action}\n" +
                    "• <b>Alimento:</b> ${modificationData.foodName}\n" +
                    "• <b>Momento:</b> ${modificationData.mealTime}\n" +
                    "• <b>Cantidad:</b> ${modificationData.quantity}\n" +
                    "• <b>Unidad:</b> ${modificationData.unit}\n\n" +
                    "¿Confirma este cambio? (Responda 'sí' para proceder o 'no' para cancelar)"
                } else {
                    "No pude entender completamente su solicitud de modificación.\n\n" +
                    "Por favor, asegúrate de incluir:\n" +
                    "• El nombre del alimento\n" +
                    "• El momento del día\n" +
                    "• La cantidad y unidad\n\n" +
                    "💡 <b>Ejemplo:</b> \"Quiero agregar 100 gramos de avena al desayuno\""
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
                val response = "$greeting Soy NutriAI, su asistente de nutrición.\n\nEstoy aquí para resolver todas sus dudas sobre alimentación saludable, dietas, control de peso, suplementos y mucho más.\n\n¿Qué tema le gustaría consultar hoy?"
                println("Respuesta generada: $response")
                response
            }
            
            // Preguntas generales de nutrición (DESPUÉS de la condición específica)
            lowerMessage.contains("nutrición") || lowerMessage.contains("alimentación saludable") -> 
                "La nutrición es fundamental para su salud. Una alimentación balanceada incluye todos los macronutrientes: proteínas para músculos, carbohidratos para energía, y grasas saludables. ¿Hay algún aspecto específico que le interese?"
            
            lowerMessage.contains("vitaminas") || lowerMessage.contains("minerales") -> 
                "Las vitaminas y minerales son micronutrientes esenciales. Las frutas y verduras son las mejores fuentes. ¿Le gustaría saber sobre alguna vitamina específica o cómo obtener más micronutrientes?"
            
            else -> {
                println("No se encontró coincidencia específica, usando respuesta genérica")
                "Entiendo su consulta. Como NutriAI, puedo asistirle con información sobre nutrición, macronutrientes, planificación de comidas y consejos para una alimentación saludable. ¿Hay algo específico sobre nutrición que le gustaría saber?"
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
                        "¡Perfecto! Le ayudo a agregar alimentos a su rutina. ¿Qué alimento le gustaría agregar y en qué momento del día (desayuno, almuerzo, cena, snack)?"
                    lowerMessage.contains("eliminar") || lowerMessage.contains("quitar") -> 
                        "Entiendo que desea eliminar algo de su rutina. ¿Qué alimento específico le gustaría quitar y de qué comida?"
                    lowerMessage.contains("cambiar") || lowerMessage.contains("modificar") || 
                    lowerMessage.contains("cambiar alimento") || lowerMessage.contains("modificar alimento") ||
                    lowerMessage.contains("modifica tu rutina") -> 
                        "Le ayudo a modificar su rutina. ¿Qué alimento le gustaría cambiar y por cuál le gustaría reemplazarlo?"
                    else -> 
                        "¿En qué puedo asistirle con su rutina alimentaria? Puedo ayudarle a agregar, eliminar o modificar alimentos según sus necesidades."
                }
            }
            TipoIntento.Pregunta_Nutricional -> {
                when {
                    lowerMessage.contains("calorías") -> 
                        "Las calorías son la energía que necesita su cuerpo. Para darle recomendaciones precisas, necesito conocer su perfil completo. ¿Ha configurado su información personal en la aplicación?"
                    lowerMessage.contains("desayuno") -> 
                        "El desayuno es muy importante para empezar el día con energía. Basándome en su perfil, le puedo sugerir opciones nutritivas. ¿Le gustaría que le ayude con eso?"
                    lowerMessage.contains("almuerzo") -> 
                        "El almuerzo debe ser balanceado con proteínas, carbohidratos y verduras. ¿Necesita ideas específicas para su almuerzo según su perfil?"
                    lowerMessage.contains("cena") -> 
                        "La cena debe ser más ligera que el almuerzo. Le recomiendo proteínas magras con verduras. ¿Qué le parece si le sugiero opciones basadas en sus objetivos?"
                    else -> 
                        "Tengo información sobre nutrición y alimentación saludable. ¿Sobre qué aspecto específico le gustaría saber más? Puedo personalizar mis respuestas según su perfil."
                }
            }
            else -> {
                when {
                    lowerMessage.contains("hola") || lowerMessage.contains("hi") -> {
                        "¡Hola! Soy NutriAI, su asistente nutricional personal. Estoy aquí para asistirle con su rutina alimentaria y responder sus preguntas sobre nutrición. ¿En qué puedo asistirle hoy?"
                    }
                    lowerMessage.contains("gracias") -> 
                        "¡De nada! Estoy aquí para asistirle siempre que lo necesite. ¿Hay algo más en lo que pueda asistirle con su nutrición?"
                    else -> 
                        "Entiendo su consulta. Como NutriAI, puedo asistirle con información nutricional, sugerencias de alimentos, gestión de su rutina alimentaria, y mucho más. ¿Hay algo específico en lo que pueda asistirle?"
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
                "$greeting ¡Perfecto! Le ayudo a modificar su rutina.\n\n" +
                "📋 <b>Su rutina de hoy:</b>\n$rutinaActual\n\n" +
                "🔄 <b>¿Qué le gustaría modificar?</b>\n\n" +
                "1️⃣ <b>¿En qué momento del día?</b> (Desayuno, Almuerzo, Cena, Snack)\n" +
                "2️⃣ <b>¿Qué comida específica desea cambiar?</b>\n" +
                "3️⃣ <b>¿Por cuál alimento le gustaría reemplazarla?</b>\n\n" +
                "💡 <b>Ejemplo:</b> \"Quiero cambiar el arroz del almuerzo por quinoa\""
            }
            
            // Si el usuario especifica momento del día pero no el alimento
            momentoDelDia != null && alimentosEnRutina.isNotEmpty() && 
            !lowerMessage.contains("por") && !lowerMessage.contains("reemplazar") -> {
                val alimentosTexto = alimentosEnRutina.joinToString(", ")
                "$greeting Perfecto, desea modificar el <b>$momentoDelDia</b>.\n\n" +
                "🍽️ <b>Alimentos actuales en $momentoDelDia:</b>\n$alimentosTexto\n\n" +
                "¿Cuál de estos alimentos desea cambiar y por cuál le gustaría reemplazarlo?\n\n" +
                "💡 <b>Ejemplo:</b> \"Quiero cambiar el arroz por quinoa\""
            }
            
            // Si el usuario especifica momento del día pero no hay alimentos
            momentoDelDia != null && alimentosEnRutina.isEmpty() -> {
                "$greeting Veo que no tiene alimentos registrados para el <b>$momentoDelDia</b>.\n\n" +
                "¿Le gustaría agregar algún alimento a esta comida en lugar de modificar?\n\n" +
                "💡 <b>Ejemplo:</b> \"Quiero agregar avena al desayuno\""
            }
            
            // Si el usuario está especificando el cambio completo
            momentoDelDia != null && lowerMessage.contains("por") -> {
                val alimentoOriginal = extractFoodFromMessage(message, alimentosEnRutina)
                val alimentoNuevo = extractNewFoodFromMessage(message)
                
                return if (alimentoOriginal != null && alimentoNuevo != null) {
                    "$greeting ¡Perfecto! Entiendo que desea cambiar:\n\n" +
                    "🔄 <b>Cambio solicitado:</b>\n" +
                    "• <b>De:</b> $alimentoOriginal\n" +
                    "• <b>Por:</b> $alimentoNuevo\n" +
                    "• <b>Momento:</b> $momentoDelDia\n\n" +
                    "¿Confirma este cambio? (Responda 'sí' para proceder o 'no' para cancelar)"
                } else {
                    "$greeting No pude entender completamente el cambio que desea hacer.\n\n" +
                    "Por favor, especifique:\n" +
                    "• ¿Qué alimento desea cambiar?\n" +
                    "• ¿Por cuál desea reemplazarlo?\n\n" +
                    "💡 <b>Ejemplo:</b> \"Quiero cambiar el arroz por quinoa en el almuerzo\""
                }
            }
            
            else -> {
                "$greeting Para ayudarle a modificar su rutina, necesito que me indique:\n\n" +
                "1️⃣ <b>¿En qué momento del día?</b> (Desayuno, Almuerzo, Cena, Snack)\n" +
                "2️⃣ <b>¿Qué alimento desea cambiar?</b>\n" +
                "3️⃣ <b>¿Por cuál alimento lo desea reemplazar?</b>\n\n" +
                "💡 <b>Ejemplo:</b> \"Quiero cambiar el arroz del almuerzo por quinoa\""
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
            return "📝 <b>No tiene alimentos registrados para hoy</b>\n\n" +
                   "Para modificar su rutina, primero necesita registrar algunos alimentos."
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
                "$emoji <b>$momento:</b>\n- No hay alimentos registrados"
            } else {
                "$emoji <b>$momento:</b>\n" + alimentos.joinToString("\n") { 
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
            println("=== GENERANDO RUTINA PARA FECHA ESPECÍFICA ===")
            println("Fecha solicitada: $datePattern")
            println("Usuario ID: $userId")
            
            val specificRoutine = getRoutineForSpecificDate(datePattern, userId)
            
            println("=== RESULTADO DE getRoutineForSpecificDate ===")
            println("SpecificRoutine: ${specificRoutine?.size ?: 0} elementos")
            if (specificRoutine != null && specificRoutine.isNotEmpty()) {
                specificRoutine.forEach { registro ->
                    val fechaRegistro = registro.consumidoEn.substring(0, 10)
                    println("  - ${registro.alimento.nombreAlimento} (${registro.momentoDelDia}) - Fecha: $fechaRegistro")
                }
            } else {
                println("⚠️ ADVERTENCIA: specificRoutine es null o vacío")
            }
            
            // Verificar si la fecha consultada es el día actual
            val isCurrentDate = try {
                val consultedDate = LocalDate.parse(datePattern, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val today = LocalDate.now()
                consultedDate == today
            } catch (e: Exception) {
                false
            }
            
            // Usar el mismo formato siempre: RUTINA DE HOY (YYYY-MM-DD) si es hoy, RUTINA DEL YYYY-MM-DD si es otra fecha
            val title = if (isCurrentDate) {
                "<b>RUTINA DE HOY ($datePattern)</b>"
            } else {
                "<b>RUTINA DEL $datePattern</b>"
            }
            
            // IMPORTANTE: Usar specificRoutine (puede ser null o vacío, pero NO usar currentRoutine)
            val routineContent = "$greeting\n\n" +
            "$title\n\n" +
            generateRoutineContent(specificRoutine, datePattern, isCurrentDate)
            
            println("=== RUTINA GENERADA PARA FECHA ESPECÍFICA ===")
            println("Fecha consultada: $datePattern")
            println("Es fecha actual: $isCurrentDate")
            println("Registros encontrados: ${specificRoutine?.size ?: 0}")
            println("Título generado: $title")
            
            // Formato de opciones según especificación
            val optionsText = "\n\n<b>Opciones disponibles:</b>\n\n" +
                            "Escriba <b>agregar alimento</b> si desea incluir un nuevo alimento.\n\n" +
                            "Escriba <b>cambiar alimento</b> si desea reemplazar un alimento existente.\n\n" +
                            "Escriba <b>ver rutina YYYY-MM-DD</b> si desea consultar la rutina de otra fecha.\n" +
                            "👉 <b>Ejemplo:</b> ver rutina 2025-11-20\n\n" +
                            "⚠️ <b>Nota:</b> Las opciones de agregar alimento y cambiar alimento solo están disponibles para la rutina del día actual."
            
            routineContent + optionsText
        } else {
            // Rutina de hoy - Formato: RUTINA DE HOY (AAAA-MM-DD)
            val today = LocalDate.now()
            val dateFormatted = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val routineContent = generateRoutineContent(currentRoutine, "hoy", true)
            
            "$greeting\n\n" +
            "<b>RUTINA DE HOY ($dateFormatted)</b>\n\n" +
            "$routineContent\n\n" +
            "<b>Opciones disponibles:</b>\n\n" +
            "Escriba <b>agregar alimento</b> si desea incluir un nuevo alimento.\n\n" +
            "Escriba <b>cambiar alimento</b> si desea reemplazar un alimento existente.\n\n" +
            "Escriba <b>ver rutina YYYY-MM-DD</b> si desea consultar la rutina de otra fecha.\n" +
            "👉 <b>Ejemplo:</b> ver rutina 2025-11-20\n\n" +
            "⚠️ <b>Nota:</b> Las opciones de agregar alimento y cambiar alimento solo están disponibles para la rutina del día actual."
        }
    }
    
    private fun generateRoutineContent(currentRoutine: List<RegistroAlimentoSalida>?, dateContext: String, isCurrentDate: Boolean = true): String {
        println("=== GENERANDO CONTENIDO DE RUTINA ===")
        println("CurrentRoutine recibida: ${currentRoutine?.size ?: 0} elementos")
        println("DateContext: $dateContext")
        println("IsCurrentDate: $isCurrentDate")
        
        if (currentRoutine != null && currentRoutine.isNotEmpty()) {
            println("CurrentRoutine detalle:")
            currentRoutine.forEach { registro ->
                val fechaRegistro = registro.consumidoEn.substring(0, 10)
                println("  - ${registro.alimento.nombreAlimento} (${registro.momentoDelDia}) - Fecha: $fechaRegistro")
            }
        } else {
            println("⚠️ CurrentRoutine está vacía o es null")
        }
        
        // Si está vacía, mostrar mensaje específico
        if (currentRoutine == null || currentRoutine.isEmpty()) {
            println("⚠️ No hay registros para mostrar")
            return if (dateContext == "hoy" || isCurrentDate) {
                "Aún no hay alimentos registrados para hoy."
            } else {
                "No hay rutina registrada para esta fecha."
            }
        }
        
        // IMPORTANTE: Verificar que los registros correspondan a la fecha correcta
        val fechaObjetivo = if (dateContext != "hoy" && !isCurrentDate && dateContext.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            try {
                val fecha = LocalDate.parse(dateContext, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                println("Fecha objetivo para filtrar: $fecha")
                fecha
            } catch (e: Exception) {
                println("❌ Error parseando fecha objetivo: ${e.message}")
                null
            }
        } else {
            println("No se requiere filtrado por fecha (dateContext: $dateContext, isCurrentDate: $isCurrentDate)")
            null
        }
        
        // Filtrar registros por fecha si es necesario (doble verificación)
        val registrosFiltrados = if (fechaObjetivo != null) {
            println("=== FILTRANDO REGISTROS POR FECHA ===")
            println("Fecha objetivo: $fechaObjetivo")
            val filtrados = currentRoutine.filter { registro ->
                try {
                    val fechaRegistro = registro.consumidoEn.substring(0, 10)
                    val registroDate = LocalDate.parse(fechaRegistro)
                    val coincide = registroDate == fechaObjetivo
                    if (!coincide) {
                        println("  ❌ Registro ${registro.alimento.nombreAlimento} - Fecha: $fechaRegistro NO coincide con $fechaObjetivo")
                    }
                    coincide
                } catch (e: Exception) {
                    println("  ❌ Error parseando fecha del registro: ${e.message}")
                    false
                }
            }
            println("Registros antes del filtro: ${currentRoutine.size}")
            println("Registros después del filtro: ${filtrados.size}")
            filtrados
        } else {
            println("No se aplica filtro por fecha, usando todos los registros")
            currentRoutine
        }
        
        println("=== REGISTROS FINALES PARA MOSTRAR ===")
        println("Total de registros: ${registrosFiltrados.size}")
        registrosFiltrados.forEach { registro ->
            val fechaRegistro = registro.consumidoEn.substring(0, 10)
            println("  ✅ ${registro.alimento.nombreAlimento} (${registro.momentoDelDia}) - Fecha: $fechaRegistro")
        }
        
        // Si después del filtro no hay registros, mostrar mensaje
        if (registrosFiltrados.isEmpty()) {
            return if (dateContext == "hoy" || isCurrentDate) {
                "Aún no hay alimentos registrados para hoy."
            } else {
                "No hay rutina registrada para esta fecha."
            }
        }
        
        // Usar rutina real del usuario
        val comidasAgrupadas = registrosFiltrados.groupBy { it.momentoDelDia }
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
                "$emoji <b>$momento:</b>\n- No hay alimentos registrados"
            } else {
                "$emoji <b>$momento:</b>\n" + alimentos.joinToString("\n") { alimento ->
                    "- ${alimento.alimento.nombreAlimento}"
                }
            }
        }
        
        return rutinaContent
    }
    
    private fun getInitialMenu(userName: String): String {
        return "<b>Opciones disponibles:</b>\n\n" +
               "Escriba <b>agregar alimento</b> si desea incluir un nuevo alimento.\n\n" +
               "Escriba <b>cambiar alimento</b> si desea reemplazar un alimento existente.\n\n" +
               "Escriba <b>ver rutina YYYY-MM-DD</b> si desea consultar la rutina de otra fecha.\n" +
               "👉 <b>Ejemplo:</b> ver rutina 2025-10-05\n\n" +
               "⚠️ <b>Nota:</b> Las opciones de agregar alimento y cambiar alimento solo están disponibles para la rutina del día actual."
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
                val fechaEncontrada = match.value
                println("✅ Fecha encontrada: $fechaEncontrada")
                
                // Si es formato YYYY-MM-DD, devolverlo directamente
                if (fechaEncontrada.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    return fechaEncontrada
                }
                // Si es formato YYYY-M-D o YYYY-MM-D, normalizarlo a YYYY-MM-DD
                else if (fechaEncontrada.matches(Regex("\\d{4}-\\d{1,2}-\\d{1,2}"))) {
                    val partes = fechaEncontrada.split("-")
                    val año = partes[0]
                    val mes = partes[1].padStart(2, '0')
                    val dia = partes[2].padStart(2, '0')
                    val fechaNormalizada = "$año-$mes-$dia"
                    println("📅 Fecha normalizada: $fechaNormalizada")
                    return fechaNormalizada
                }
                // Para otros formatos, devolver el valor encontrado
                else {
                    return fechaEncontrada
                }
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
            
            println("=== RESULTADO DEL FILTRADO ===")
            println("Registros encontrados para $dateString: ${registrosDelDia.size}")
            println("Total de registros del usuario: ${allRegistros.size}")
            println("Fecha objetivo: $targetDate")
            
            if (registrosDelDia.isNotEmpty()) {
                println("✅ REGISTROS ENCONTRADOS PARA LA FECHA ESPECÍFICA:")
                registrosDelDia.forEach { 
                    val fechaRegistro = it.consumidoEn.substring(0, 10)
                    println("  - ${it.alimento.nombreAlimento} (${it.momentoDelDia}) - Fecha: $fechaRegistro")
                }
            } else {
                println("⚠️ NO SE ENCONTRARON REGISTROS PARA LA FECHA $dateString")
                if (allRegistros.isNotEmpty()) {
                    println("Registros disponibles (primeros 5):")
                    allRegistros.take(5).forEach { registro ->
                        val fechaRegistro = registro.consumidoEn.substring(0, 10)
                        println("  - ${registro.alimento.nombreAlimento} - Fecha: $fechaRegistro")
                    }
                }
            }
            
            // IMPORTANTE: Devolver SOLO los registros de la fecha específica, nunca todos los registros
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
                    respuesta = "${greeting}¡Excelente! Ha elegido <b>$foodName</b>.\n\n" +
                               "⚖️ <b>¿Qué cantidad desea agregar?</b>\n" +
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
                        val alimentoEncontrado = repository.buscarAlimentoPorNombre(currentState.foodName ?: "")
                        if (alimentoEncontrado == null) {
                            println("⚠️ Alimento no encontrado: ${currentState.foodName}")
                        } else {
                            println("✅ Alimento encontrado: ${alimentoEncontrado.nombreAlimento} (ID: ${alimentoEncontrado.idAlimento})")
                        }
                        alimentoEncontrado
                    } catch (e: Exception) {
                        println("❌ Error buscando alimento: ${e.message}")
                        null
                    }
                    
                    // Obtener las unidades válidas si el alimento existe
                    val unidadesValidas = if (alimento != null) {
                        try {
                            val unidades = repository.obtenerUnidadesPorId(alimento.idAlimento)
                            println("✅ Unidades obtenidas para ${alimento.nombreAlimento}: $unidades")
                            unidades
                        } catch (e: Exception) {
                            println("❌ Error obteniendo unidades válidas: ${e.message}")
                            emptyList<String>()
                        }
                    } else {
                        println("⚠️ No se pueden obtener unidades: alimento no encontrado")
                        emptyList<String>()
                    }
                    
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.ADD_FOOD_UNIT,
                        quantity = quantity,
                        validUnits = if (unidadesValidas.isNotEmpty()) unidadesValidas else null
                    )
                    
                    val mensajeUnidades = if (unidadesValidas.isNotEmpty()) {
                        val unidadesTexto = unidadesValidas.joinToString(", ")
                        "📏 <b>¿Cuál es la unidad de medida para ${currentState.foodName}?</b>\n\n" +
                        "Unidades disponibles: <b>$unidadesTexto</b>\n\n" +
                        "Por favor, elija una de las unidades listadas arriba."
                    } else {
                        "📏 <b>¿Cuál es la unidad de medida para ${currentState.foodName}?</b>\n" +
                        "(ejemplo: gramos, tazas, porciones, unidades, etc.)"
                    }
                    
                    ChatbotResponse(
                        respuesta = "Perfecto, <b>$quantity</b> de <b>${currentState.foodName}</b>.\n\n$mensajeUnidades",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                } else {
                    ChatbotResponse(
                        respuesta = "Por favor, ingrese solo un número para la cantidad.\n\n" +
                                   "⚖️ <b>¿Qué cantidad desea agregar?</b>\n" +
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
                            respuesta = "❌ La unidad <b>$unit</b> no está disponible para <b>${currentState.foodName}</b>.\n\n" +
                                       "Las unidades válidas son: <b>$unidadesTexto</b>\n\n" +
                                       "Por favor, elija una de las unidades listadas.",
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
                            respuesta = "Excelente, <b>${currentState.quantity} $unidadValida</b> de <b>${currentState.foodName}</b>.\n\n" +
                                       "🕐 <b>¿En qué momento del día?</b>\n" +
                                       "(Desayuno, Almuerzo, Cena, Snack)",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    }
                } else {
                    // Si no hay unidades válidas disponibles, buscar el alimento en la BD para obtener su unidad base
                    val alimento = try {
                        repository.buscarAlimentoPorNombre(currentState.foodName ?: "")
                    } catch (e: Exception) {
                        null
                    }
                    
                    if (alimento != null) {
                        // Intentar obtener unidades válidas de la BD
                        val unidadesValidasReintento = try {
                            repository.obtenerUnidadesPorId(alimento.idAlimento)
                        } catch (e: Exception) {
                            emptyList<String>()
                        }
                        
                        if (unidadesValidasReintento.isNotEmpty()) {
                            // Actualizar el estado con las unidades válidas y pedir nuevamente
                            conversationStates[userId] = currentState.copy(
                                validUnits = unidadesValidasReintento
                            )
                            val unidadesTexto = unidadesValidasReintento.joinToString(", ")
                            return ChatbotResponse(
                                respuesta = "📏 <b>¿Cuál es la unidad de medida para ${currentState.foodName}?</b>\n\n" +
                                           "Unidades disponibles: <b>$unidadesTexto</b>\n\n" +
                                           "Por favor, elija una de las unidades listadas arriba.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else if (alimento.unidadBase.isNotBlank()) {
                            // Validar que la unidad ingresada coincida con la unidad base
                            if (unit.lowercase() == alimento.unidadBase.lowercase()) {
                                conversationStates[userId] = currentState.copy(
                                    currentStep = NutriAIStep.ADD_FOOD_MEAL_TIME,
                                    unit = alimento.unidadBase
                                )
                                ChatbotResponse(
                                    respuesta = "Excelente, <b>${currentState.quantity} ${alimento.unidadBase}</b> de <b>${currentState.foodName}</b>.\n\n" +
                                               "🕐 <b>¿En qué momento del día?</b>\n" +
                                               "(Desayuno, Almuerzo, Cena, Snack)",
                                    tipoIntento = TipoIntento.Modificar_Rutina,
                                    tipoAccion = TipoAccion.Agregar
                                )
                            } else {
                                ChatbotResponse(
                                    respuesta = "❌ La unidad <b>$unit</b> no está disponible para <b>${currentState.foodName}</b>.\n\n" +
                                               "La unidad base disponible es: <b>${alimento.unidadBase}</b>\n\n" +
                                               "Por favor, use la unidad base.",
                                    tipoIntento = TipoIntento.Modificar_Rutina,
                                    tipoAccion = TipoAccion.Agregar
                                )
                            }
                        } else {
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            ChatbotResponse(
                                respuesta = "❌ No se pudo determinar la unidad de medida para <b>${currentState.foodName}</b>.\n\n" +
                                           "Por favor, intente nuevamente o contacte al soporte.",
                                tipoIntento = TipoIntento.Otros,
                                tipoAccion = null
                            )
                        }
                    } else {
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        ChatbotResponse(
                            respuesta = "❌ No se pudo encontrar el alimento <b>${currentState.foodName}</b> en la base de datos.\n\n" +
                                       "Por favor, intente nuevamente.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                }
            }
            
            NutriAIStep.ADD_FOOD_MEAL_TIME -> {
                val mealTime = message.trim()
                val momentosValidos = listOf("desayuno", "almuerzo", "cena", "snack")
                
                val momentoValido = momentosValidos.find { 
                    it.equals(mealTime, ignoreCase = true) 
                }
                
                if (momentoValido != null) {
                    // Usar selectedFood si está disponible (nuevo flujo), sino usar foodName (flujo antiguo)
                    val alimentoNombre = currentState.selectedFood?.nombreAlimento ?: currentState.foodName ?: ""
                    val cantidad = currentState.quantity ?: ""
                    val unidad = currentState.unit ?: ""
                    
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.ADD_CONFIRMATION,
                        mealTime = momentoValido.capitalize()
                    )
                    ChatbotResponse(
                        respuesta = "¡Perfecto! Resumen de su solicitud:\n\n" +
                                   "🥦 <b>Alimento:</b> $alimentoNombre\n" +
                                   "⚖️ <b>Cantidad:</b> $cantidad\n" +
                                   "📏 <b>Unidad:</b> $unidad\n" +
                                   "🕐 <b>Momento:</b> ${momentoValido.capitalize()}\n\n" +
                                   "¿Desea agregar <b>$alimentoNombre - $cantidad $unidad</b> a su rutina nutricional?\n\n" +
                                   "💡 <b>Responda:</b>\n" +
                                   "• <b>Sí</b> o <b>agregar</b> para confirmar\n" +
                                   "• <b>No</b> para cancelar",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                } else {
                    ChatbotResponse(
                        respuesta = "Por favor, seleccione un momento del día válido:\n" +
                                   "• <b>Desayuno</b>\n" +
                                   "• <b>Almuerzo</b>\n" +
                                   "• <b>Cena</b>\n" +
                                   "• <b>Snack</b>",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                }
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
                    
                        if (success) {
                            // Reset conversation state solo si fue exitoso
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            // Notificar que la rutina se actualizó
                            onRoutineUpdated?.invoke()
                            
                            ChatbotResponse(
                                respuesta = "¡Perfecto! He registrado <b>${currentState.foodName} - ${currentState.quantity} ${currentState.unit}</b> en su rutina.\n\n" +
                                           "✅ <b>Su rutina se ha actualizado correctamente.</b>\n\n" +
                                           "¿Le gustaría hacer algún otro cambio en su rutina o necesita ayuda con algo más?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else {
                            // NO resetear el estado si hay error - redirigir al mensaje anterior con el resumen
                            ChatbotResponse(
                                respuesta = "Lo siento, hubo un problema al agregar el alimento a su rutina.\n\n" +
                                           "❌ <b>No se pudo agregar el alimento.</b>\n\n" +
                                           "<b>Resumen de su solicitud:</b>\n" +
                                           "• <b>Alimento:</b> ${currentState.foodName ?: "N/A"}\n" +
                                           "• <b>Cantidad:</b> ${currentState.quantity ?: "N/A"}\n" +
                                           "• <b>Unidad:</b> ${currentState.unit ?: "N/A"}\n" +
                                           "• <b>Momento:</b> ${currentState.mealTime ?: "N/A"}\n\n" +
                                           "¿Desea agregar <b>${currentState.foodName ?: ""} - ${currentState.quantity ?: ""} ${currentState.unit ?: ""}</b> a su rutina nutricional?\n\n" +
                                           "💡 <b>Responde:</b>\n" +
                                           "• <b>Sí</b> o <b>agregar</b> para confirmar\n" +
                                           "• <b>No</b> para cancelar",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        }
                    } else if (lowerMessage.contains("no") || lowerMessage.contains("cancelar")) {
                    // Cancelar
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Entendido, no se realizará ningún cambio.\n\n" +
                                   "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                } else {
                    // Redireccionar al mensaje anterior cuando el usuario escribe algo incorrecto
                    ChatbotResponse(
                        respuesta = "No entendí su mensaje. Por favor, escriba una de las opciones disponibles:\n\n" +
                                   "<b>Resumen de su solicitud:</b>\n" +
                                   "• <b>Alimento:</b> ${currentState.foodName ?: "N/A"}\n" +
                                   "• <b>Cantidad:</b> ${currentState.quantity ?: "N/A"}\n" +
                                   "• <b>Unidad:</b> ${currentState.unit ?: "N/A"}\n" +
                                   "• <b>Momento:</b> ${currentState.mealTime ?: "N/A"}\n\n" +
                                   "¿Desea agregar <b>${currentState.foodName ?: ""} - ${currentState.quantity ?: ""} ${currentState.unit ?: ""}</b> a su rutina nutricional?\n\n" +
                                   "💡 <b>Responde:</b>\n" +
                                   "• <b>Sí</b> o <b>agregar</b> para confirmar\n" +
                                   "• <b>No</b> para cancelar",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
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
                    respuesta = "${greeting}Entendido, desea cambiar <b>$originalFood</b>.\n\n" +
                               "🥦 <b>¿Por cuál alimento lo desea reemplazar?</b>\n" +
                               "(mencione el nuevo alimento)",
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
                    respuesta = "Perfecto, desea reemplazar <b>${currentState.originalFood}</b> por <b>$newFood</b>.\n\n" +
                               "⚖️ <b>¿Qué cantidad del nuevo alimento?</b>\n" +
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
                        respuesta = "Excelente, <b>$quantity</b> de <b>${currentState.newFood}</b>.\n\n" +
                                   "📏 <b>¿Cuál es la unidad de medida?</b>\n" +
                                   "(ejemplo: gramos, tazas, porciones, etc.)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    ChatbotResponse(
                        respuesta = "Por favor, ingrese solo un número para la cantidad.\n\n" +
                                   "⚖️ <b>¿Qué cantidad del nuevo alimento?</b>\n" +
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
                    respuesta = "$greeting Perfecto, <b>${currentState.quantity} $unit</b> de <b>${currentState.newFood}</b>.\n\n" +
                               "🕐 <b>¿En qué momento del día?</b>\n" +
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
                    respuesta = "¡Perfecto! Resumen de su cambio:\n\n" +
                               "🔄 <b>Cambio solicitado:</b>\n" +
                               "• <b>De:</b> ${currentState.originalFood}\n" +
                               "• <b>Por:</b> ${currentState.newFood}\n" +
                               "• <b>Cantidad:</b> ${currentState.quantity}\n" +
                               "• <b>Unidad:</b> ${currentState.unit}\n" +
                               "• <b>Momento:</b> $mealTime\n\n" +
                               "¿Desea reemplazar <b>${currentState.originalFood}</b> por <b>${currentState.newFood} - ${currentState.quantity} ${currentState.unit}</b>?\n\n" +
                               "💡 <b>Responda:</b>\n" +
                               "• <b>Sí</b> o <b>cambiar</b> para confirmar\n" +
                               "• <b>No</b> para cancelar",
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
                            respuesta = "¡Perfecto! He realizado el cambio en su rutina.\n\n" +
                                       "✅ <b>Su rutina se ha actualizado correctamente.</b>\n\n" +
                                       "¿Le gustaría hacer algún otro cambio en su rutina o necesita ayuda con algo más?",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Modificar
                        )
                    } else {
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al realizar el cambio en su rutina.\n\n" +
                                       "❌ <b>No se pudo actualizar la rutina.</b>\n\n" +
                                       "Por favor, verifique que los nombres de los alimentos sean correctos e inténtelo de nuevo.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    // Reset conversation state
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Entendido, no se realizará ningún cambio.\n\n" +
                                   "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?",
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
                    respuesta = "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
            
            NutriAIStep.CHANGE_SHOW_CURRENT_FOOD -> {
                // Este estado ya no se usa, el alimento actual se muestra directamente en la detección del comando
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                ChatbotResponse(
                    respuesta = "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?",
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
                    // Alimento encontrado, guardar información y mostrar categorías (igual que agregar)
                    try {
                        // Obtener todas las categorías disponibles
                        val categorias = repository.obtenerCategoriasUnicas()
                        if (categorias.isNotEmpty()) {
                            conversationStates[userId] = currentState.copy(
                                currentStep = NutriAIStep.CHANGE_SELECT_CATEGORY,
                                originalFood = alimentoValido.alimento.nombreAlimento,
                                mealTime = alimentoValido.momentoDelDia,
                                availableCategories = categorias,
                                currentRoutineFoods = alimentosDisponibles
                            )
                            
                            val categoriasTexto = categorias.joinToString("\n") { "• $it" }
                            return ChatbotResponse(
                                respuesta = "Desea cambiar <b>${alimentoValido.alimento.nombreAlimento}</b> de <b>${alimentoValido.momentoDelDia}</b>.\n\n" +
                                           "<b>Seleccione una categoría:</b>\n\n" +
                                           "$categoriasTexto",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            return ChatbotResponse(
                                respuesta = "Lo siento, no hay categorías de alimentos disponibles en este momento.\n\n" +
                                           "¿Hay algo más en lo que pueda asistirle?",
                                tipoIntento = TipoIntento.Otros,
                                tipoAccion = null
                            )
                        }
                    } catch (e: Exception) {
                        println("Error obteniendo categorías: ${e.message}")
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        return ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al obtener las categorías.\n\n" +
                                       "¿Hay algo más en lo que pueda asistirle?",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    // Alimento no encontrado, mostrar lista nuevamente
                    val alimentosTexto = alimentosDisponibles.joinToString("\n") { 
                        "• <b>${it.alimento.nombreAlimento}</b> - ${it.momentoDelDia}" 
                    }
                    return ChatbotResponse(
                        respuesta = "❌ No encontré <b>$alimentoSeleccionado</b> en su rutina actual.\n\n" +
                                   "Estos son los alimentos registrados en su rutina:\n\n" +
                                   "$alimentosTexto\n\n" +
                                   "📝 <b>Por favor, elija uno de los alimentos listados arriba.</b>",
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
                                respuesta = "En la categoría <b>$categoriaValida</b> se encuentran los siguientes alimentos:\n\n" +
                                           "$alimentosTexto\n\n" +
                                           "Por favor, elija uno.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            ChatbotResponse(
                                respuesta = "Actualmente no hay alimentos registrados en esa categoría.\n\n" +
                                           "¿Le gustaría consultar otra categoría?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        }
                    } catch (e: Exception) {
                        println("Error obteniendo alimentos por categoría: ${e.message}")
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al obtener los alimentos de esa categoría.\n\n" +
                                       "¿Hay algo más en lo que pueda asistirle?",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    val categoriasTexto = categoriasDisponibles.joinToString(", ")
                    ChatbotResponse(
                        respuesta = "Esa categoría no se encuentra disponible. Las categorías disponibles son:\n\n" +
                                   "<b>$categoriasTexto</b>\n\n" +
                                   "Por favor, escriba una categoría válida.",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
            }
            
            NutriAIStep.CHANGE_SHOW_FOODS -> {
                val alimentoSeleccionado = message.trim()
                val alimentosDisponibles = currentState.availableFoods ?: emptyList()
                val alimentoOriginal = currentState.originalFood ?: ""
                
                // Verificar si el alimento existe
                val alimentoValido = alimentosDisponibles.find { 
                    it.nombreAlimento.equals(alimentoSeleccionado, ignoreCase = true) 
                }
                
                if (alimentoValido != null) {
                    // Seguir el mismo flujo que agregar: cantidad -> unidades -> momento
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.CHANGE_SELECT_FOOD_QUANTITY,
                        selectedFood = alimentoValido
                    )
                    return ChatbotResponse(
                        respuesta = "¿Qué cantidad de <b>${alimentoValido.nombreAlimento}</b> desea registrar?\n" +
                                   "(Solo ingresa el número: 1, 2, 3, 150, etc.)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    val alimentosTexto = alimentosDisponibles.joinToString("\n") { "• ${it.nombreAlimento}" }
                    return ChatbotResponse(
                        respuesta = "Ese alimento no se encuentra disponible. Solo puede elegir alimentos registrados en la base de datos.\n\n" +
                                   "Los alimentos disponibles en la categoría <b>${currentState.selectedCategory}</b> son:\n\n" +
                                   "$alimentosTexto\n\n" +
                                   "Por favor, elija uno de la lista.",
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
                            currentStep = NutriAIStep.CHANGE_SELECT_UNIT,
                            quantity = cantidadTexto,
                            validUnits = if (unidadesValidas.isNotEmpty()) unidadesValidas else null
                        )
                        
                        // Mostrar las unidades disponibles
                        val mensajeUnidades = if (!unidadesValidas.isNullOrEmpty()) {
                            val unidadesTexto = unidadesValidas.joinToString(", ")
                            "📏 <b>¿Cuál es la unidad de medida para ${alimentoSeleccionado.nombreAlimento}?</b>\n\n" +
                            "Unidades disponibles: <b>$unidadesTexto</b>\n\n" +
                            "Por favor, elija una de las unidades listadas arriba."
                        } else {
                            "📏 <b>¿Cuál es la unidad de medida para ${alimentoSeleccionado.nombreAlimento}?</b>\n" +
                            "(Ejemplo: gramos, tazas, porciones, unidades, etc.)"
                        }
                        
                        ChatbotResponse(
                            respuesta = "Perfecto, <b>$cantidadTexto</b> de <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n$mensajeUnidades",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Modificar
                        )
                    } else {
                        ChatbotResponse(
                            respuesta = "Por favor, ingrese solo un número para la cantidad.\n" +
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
                val alimentoSeleccionado = currentState.selectedFood
                val cantidad = currentState.quantity
                val unidad = currentState.unit
                val alimentoOriginal = currentState.originalFood
                
                val momentoValido = momentosValidos.find { 
                    it.equals(momentoDelDia, ignoreCase = true) 
                }
                
                if (momentoValido != null && alimentoSeleccionado != null && cantidad != null && unidad != null) {
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.CHANGE_CONFIRMATION_NEW,
                        mealTime = momentoValido.capitalize()
                    )
                    
                    return ChatbotResponse(
                        respuesta = "📋 <b>Resumen de su cambio:</b>\n\n" +
                                   "• <b>Alimento original:</b> $alimentoOriginal\n" +
                                   "• <b>Nuevo alimento:</b> ${alimentoSeleccionado.nombreAlimento}\n" +
                                   "• <b>Cantidad:</b> $cantidad\n" +
                                   "• <b>Unidad:</b> $unidad\n" +
                                   "• <b>Momento:</b> ${momentoValido.capitalize()}\n\n" +
                                   "¿Desea cambiar <b>$alimentoOriginal</b> por <b>${alimentoSeleccionado.nombreAlimento} - $cantidad $unidad</b> en <b>${momentoValido.capitalize()}</b>?\n\n" +
                                   "💡 <b>Responde:</b>\n" +
                                   "• <b>Sí</b> o <b>cambiar</b> para confirmar\n" +
                                   "• <b>No</b> para cancelar",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    return ChatbotResponse(
                        respuesta = "Por favor, seleccione un momento del día válido:\n" +
                                   "• <b>Desayuno</b>\n" +
                                   "• <b>Almuerzo</b>\n" +
                                   "• <b>Cena</b>\n" +
                                   "• <b>Snack</b>",
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
                
                if (alimentoSeleccionado != null && cantidad != null) {
                    // Validar que la unidad esté en la lista de unidades válidas
                    if (!unidadesValidas.isNullOrEmpty()) {
                        val unidadValida = unidadesValidas.find { 
                            it.lowercase() == unidad 
                        }
                        
                        if (unidadValida == null) {
                            val unidadesTexto = unidadesValidas.joinToString(", ")
                            return ChatbotResponse(
                                respuesta = "❌ La unidad <b>$unidad</b> no está disponible para <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                           "Las unidades válidas son: <b>$unidadesTexto</b>\n\n" +
                                           "Por favor, elija una de las unidades listadas.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            // Usar la unidad válida y pedir momento del día
                            conversationStates[userId] = currentState.copy(
                                currentStep = NutriAIStep.CHANGE_SELECT_MEAL_TIME,
                                unit = unidadValida
                            )
                            return ChatbotResponse(
                                respuesta = "Excelente, <b>$cantidad $unidadValida</b> de <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                           "🕐 <b>¿En qué momento del día?</b>\n" +
                                           "(Desayuno, Almuerzo, Cena, Snack)",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        }
                    } else {
                        // Si no hay unidades válidas, intentar obtenerlas nuevamente de la BD
                        val unidadesValidasReintento = try {
                            repository.obtenerUnidadesPorId(alimentoSeleccionado.idAlimento)
                        } catch (e: Exception) {
                            println("Error obteniendo unidades válidas: ${e.message}")
                            emptyList<String>()
                        }
                        
                        if (unidadesValidasReintento.isNotEmpty()) {
                            // Actualizar el estado con las unidades válidas y pedir nuevamente
                            conversationStates[userId] = currentState.copy(
                                validUnits = unidadesValidasReintento
                            )
                            val unidadesTexto = unidadesValidasReintento.joinToString(", ")
                            return ChatbotResponse(
                                respuesta = "📏 <b>¿Cuál es la unidad de medida para ${alimentoSeleccionado.nombreAlimento}?</b>\n\n" +
                                           "Unidades disponibles: <b>$unidadesTexto</b>\n\n" +
                                           "Por favor, elija una de las unidades listadas arriba.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            // Si realmente no hay unidades válidas, usar solo la unidad base si está disponible
                            val unidadBase = alimentoSeleccionado.unidadBase
                            if (unidadBase.isNotBlank()) {
                                // Validar que la unidad ingresada coincida con la unidad base
                                if (unidad.lowercase() == unidadBase.lowercase()) {
                                    conversationStates[userId] = currentState.copy(
                                        currentStep = NutriAIStep.CHANGE_SELECT_MEAL_TIME,
                                        unit = unidadBase
                                    )
                                    return ChatbotResponse(
                                        respuesta = "Excelente, <b>$cantidad $unidadBase</b> de <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                                   "🕐 <b>¿En qué momento del día?</b>\n" +
                                                   "(Desayuno, Almuerzo, Cena, Snack)",
                                        tipoIntento = TipoIntento.Modificar_Rutina,
                                        tipoAccion = TipoAccion.Modificar
                                    )
                                } else {
                                    // La unidad ingresada no coincide con la unidad base
                                    return ChatbotResponse(
                                        respuesta = "❌ La unidad <b>$unidad</b> no está disponible para <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                                   "La unidad base disponible es: <b>$unidadBase</b>\n\n" +
                                                   "Por favor, use la unidad base.",
                                        tipoIntento = TipoIntento.Modificar_Rutina,
                                        tipoAccion = TipoAccion.Modificar
                                    )
                                }
                            } else {
                                // Si no hay unidad base, mostrar error
                                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                                return ChatbotResponse(
                                    respuesta = "❌ No se pudo determinar la unidad de medida para <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                               "Por favor, intente nuevamente o contacte al soporte.",
                                    tipoIntento = TipoIntento.Otros,
                                    tipoAccion = null
                                )
                            }
                        }
                    }
                } else {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    return ChatbotResponse(
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
                                respuesta = "Perfecto, se ha cambiado su alimento a <b>${alimentoSeleccionado.nombreAlimento}</b> – <b>$cantidad $unidad</b>.\n\n" +
                                           "✅ <b>Su rutina se ha actualizado correctamente.</b>\n\n" +
                                           "¿Le gustaría hacer algún otro cambio en su rutina o necesita ayuda con algo más?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Modificar
                            )
                        } else {
                            ChatbotResponse(
                                respuesta = "Lo siento, hubo un problema al realizar el cambio en su rutina.\n\n" +
                                           "❌ <b>No se pudo actualizar la rutina.</b>\n\n" +
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
                                   "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            NutriAIStep.CHANGE_SELECT_FOOD -> {
                // Este estado ya no se usa, se maneja en CHANGE_SHOW_FOODS
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                ChatbotResponse(
                    respuesta = "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?",
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
                                respuesta = "En la categoría <b>$categoriaValida</b> se encuentran:\n\n" +
                                           "$alimentosTexto\n\n" +
                                           "Elija uno para agregar.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else {
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            ChatbotResponse(
                                respuesta = "Actualmente no hay alimentos disponibles en esa categoría.\n\n" +
                                           "¿Le gustaría seleccionar otra categoría?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        }
                    } catch (e: Exception) {
                        println("Error obteniendo alimentos por categoría: ${e.message}")
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        ChatbotResponse(
                            respuesta = "Lo siento, hubo un problema al obtener los alimentos de esa categoría.\n\n" +
                                       "¿Hay algo más en lo que pueda asistirle?",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                } else {
                    val categoriasTexto = categoriasDisponibles.joinToString("\n") { "• $it" }
                    ChatbotResponse(
                        respuesta = "❌ La categoría <b>$categoriaSeleccionada</b> no se encuentra disponible.\n\n" +
                                   "<b>Categorías disponibles:</b>\n\n" +
                                   "$categoriasTexto\n\n" +
                                   "Por favor, seleccione una categoría de la lista.",
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
                        respuesta = "¿Qué cantidad de <b>${alimentoValido.nombreAlimento}</b> desea agregar?\n" +
                                   "(Solo ingresa el número: 1, 2, 3, 150, etc.)",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                } else {
                    val alimentosTexto = alimentosDisponibles.joinToString("\n") { "• ${it.nombreAlimento}" }
                    ChatbotResponse(
                        respuesta = "❌ El alimento <b>$alimentoSeleccionado</b> no se encuentra en la base de datos.\n\n" +
                                   "<b>Alimentos disponibles en la categoría ${currentState.selectedCategory}:</b>\n\n" +
                                   "$alimentosTexto\n\n" +
                                   "Por favor, elija uno de la lista.",
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
                            currentStep = NutriAIStep.ADD_SELECT_UNIT,
                            quantity = cantidadTexto,
                            validUnits = if (unidadesValidas.isNotEmpty()) unidadesValidas else null
                        )
                        
                        // Mostrar las unidades disponibles
                        val mensajeUnidades = if (!unidadesValidas.isNullOrEmpty()) {
                            val unidadesTexto = unidadesValidas.joinToString(", ")
                            "📏 <b>¿Cuál es la unidad de medida para ${alimentoSeleccionado.nombreAlimento}?</b>\n\n" +
                            "Unidades disponibles: <b>$unidadesTexto</b>\n\n" +
                            "Por favor, elija una de las unidades listadas arriba."
                        } else {
                            "📏 <b>¿Cuál es la unidad de medida para ${alimentoSeleccionado.nombreAlimento}?</b>\n" +
                            "(Ejemplo: gramos, tazas, porciones, unidades, etc.)"
                        }
                        
                        ChatbotResponse(
                            respuesta = "Perfecto, <b>$cantidadTexto</b> de <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n$mensajeUnidades",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    } else {
                        ChatbotResponse(
                            respuesta = "❌ La cantidad ingresada no es válida.\n\n" +
                                       "Por favor, ingrese un número positivo mayor a 0.\n" +
                                       "Ejemplo: 1, 2, 3, 150, 2.5, etc.",
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
            
            NutriAIStep.ADD_SELECT_UNIT -> {
                val unidad = message.trim().lowercase()
                val alimentoSeleccionado = currentState.selectedFood
                val cantidad = currentState.quantity
                val unidadesValidas = currentState.validUnits
                
                if (alimentoSeleccionado != null && cantidad != null) {
                    // Validar que la unidad esté en la lista de unidades válidas
                    if (!unidadesValidas.isNullOrEmpty()) {
                        val unidadValida = unidadesValidas.find { 
                            it.lowercase() == unidad 
                        }
                        
                        if (unidadValida == null) {
                            val unidadesTexto = unidadesValidas.joinToString(", ")
                            ChatbotResponse(
                                respuesta = "❌ La unidad <b>$unidad</b> no está disponible para <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                           "Las unidades válidas son: <b>$unidadesTexto</b>\n\n" +
                                           "Por favor, elija una de las unidades listadas.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else {
                            // Usar la unidad válida y pedir momento del día
                            conversationStates[userId] = currentState.copy(
                                currentStep = NutriAIStep.ADD_FOOD_MEAL_TIME,
                                unit = unidadValida
                            )
                            ChatbotResponse(
                                respuesta = "Excelente, <b>$cantidad $unidadValida</b> de <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                           "🕐 <b>¿En qué momento del día?</b>\n" +
                                           "(Desayuno, Almuerzo, Cena, Snack)",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        }
                    } else {
                        // Si no hay unidades válidas, intentar obtenerlas nuevamente de la BD
                        val unidadesValidasReintento = try {
                            repository.obtenerUnidadesPorId(alimentoSeleccionado.idAlimento)
                        } catch (e: Exception) {
                            println("Error obteniendo unidades válidas: ${e.message}")
                            emptyList<String>()
                        }
                        
                        if (unidadesValidasReintento.isNotEmpty()) {
                            // Actualizar el estado con las unidades válidas y pedir nuevamente
                            conversationStates[userId] = currentState.copy(
                                validUnits = unidadesValidasReintento
                            )
                            val unidadesTexto = unidadesValidasReintento.joinToString(", ")
                            return ChatbotResponse(
                                respuesta = "📏 <b>¿Cuál es la unidad de medida para ${alimentoSeleccionado.nombreAlimento}?</b>\n\n" +
                                           "Unidades disponibles: <b>$unidadesTexto</b>\n\n" +
                                           "Por favor, elija una de las unidades listadas arriba.",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else {
                            // Si realmente no hay unidades válidas, usar solo la unidad base si está disponible
                            val unidadBase = alimentoSeleccionado.unidadBase
                            if (unidadBase.isNotBlank()) {
                                // Validar que la unidad ingresada coincida con la unidad base
                                if (unidad.lowercase() == unidadBase.lowercase()) {
                                    conversationStates[userId] = currentState.copy(
                                        currentStep = NutriAIStep.ADD_FOOD_MEAL_TIME,
                                        unit = unidadBase
                                    )
                                    ChatbotResponse(
                                        respuesta = "Excelente, <b>$cantidad $unidadBase</b> de <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                                   "🕐 <b>¿En qué momento del día?</b>\n" +
                                                   "(Desayuno, Almuerzo, Cena, Snack)",
                                        tipoIntento = TipoIntento.Modificar_Rutina,
                                        tipoAccion = TipoAccion.Agregar
                                    )
                                } else {
                                    // La unidad ingresada no coincide con la unidad base
                                    ChatbotResponse(
                                        respuesta = "❌ La unidad <b>$unidad</b> no está disponible para <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                                   "La unidad base disponible es: <b>$unidadBase</b>\n\n" +
                                                   "Por favor, use la unidad base.",
                                        tipoIntento = TipoIntento.Modificar_Rutina,
                                        tipoAccion = TipoAccion.Agregar
                                    )
                                }
                            } else {
                                // Si no hay unidad base, mostrar error
                                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                                ChatbotResponse(
                                    respuesta = "❌ No se pudo determinar la unidad de medida para <b>${alimentoSeleccionado.nombreAlimento}</b>.\n\n" +
                                               "Por favor, intente nuevamente o contacte al soporte.",
                                    tipoIntento = TipoIntento.Otros,
                                    tipoAccion = null
                                )
                            }
                        }
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
                val alimentoNombre = alimentoSeleccionado?.nombreAlimento ?: currentState.foodName
                val cantidad = currentState.quantity
                val unidad = currentState.unit
                val momentoDelDia = currentState.mealTime
                
                if (lowerMessage.contains("sí") || lowerMessage.contains("si") || lowerMessage.contains("confirmo") || 
                    lowerMessage.contains("agregar") || lowerMessage.contains("añadir")) {
                    
                    if (alimentoNombre != null && cantidad != null && unidad != null && momentoDelDia != null) {
                        // Realizar el agregado en la base de datos
                        val success = try {
                            repository.agregarAlimentoDesdeChatbot(
                                idUsuario = userId,
                                nombreAlimento = alimentoNombre,
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
                                respuesta = "Excelente, se ha agregado <b>$alimentoNombre</b> – <b>$cantidad $unidad</b> a su plan alimenticio.\n\n" +
                                           "✅ <b>Su rutina se ha actualizado correctamente.</b>\n\n" +
                                           "¿Le gustaría agregar otro alimento o necesita ayuda con algo más?",
                                tipoIntento = TipoIntento.Modificar_Rutina,
                                tipoAccion = TipoAccion.Agregar
                            )
                        } else {
                            ChatbotResponse(
                                respuesta = "Lo siento, hubo un problema al agregar el alimento a su rutina.\n\n" +
                                           "❌ <b>No se pudo agregar el alimento.</b>\n\n" +
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
                } else if (lowerMessage.contains("no") || lowerMessage.contains("cancelar")) {
                    // Cancelar
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    ChatbotResponse(
                        respuesta = "Entendido, no se agregará el alimento.\n\n" +
                                   "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                } else {
                    // Redireccionar al mensaje anterior cuando el usuario escribe algo incorrecto
                    ChatbotResponse(
                        respuesta = "No entendí su mensaje. Por favor, escriba una de las opciones disponibles:\n\n" +
                                   "<b>Resumen de su solicitud:</b>\n" +
                                   "• <b>Alimento:</b> ${alimentoSeleccionado?.nombreAlimento ?: "N/A"}\n" +
                                   "• <b>Cantidad:</b> ${cantidad ?: "N/A"}\n" +
                                   "• <b>Unidad:</b> ${unidad ?: "N/A"}\n" +
                                   "• <b>Momento:</b> ${momentoDelDia ?: "N/A"}\n\n" +
                                   "¿Desea agregar <b>${alimentoSeleccionado?.nombreAlimento ?: ""} - ${cantidad ?: ""} ${unidad ?: ""}</b> a su rutina nutricional?\n\n" +
                                   "💡 <b>Responde:</b>\n" +
                                   "• <b>Sí</b> o <b>agregar</b> para confirmar\n" +
                                   "• <b>No</b> para cancelar",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Agregar
                    )
                }
            }
            
            NutriAIStep.ADD_SELECT_FOOD -> {
                // Este estado ya no se usa
                conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                ChatbotResponse(
                    respuesta = "¿Hay algo más en lo que pueda asistirle con su rutina nutricional?",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
            }
            
            // Handlers para el flujo de rutina personalizada
            NutriAIStep.ROUTINE_GENERATED -> {
                val lowerMessage = message.lowercase().trim()
                
                println("=== ROUTINE_GENERATED STATE ===")
                println("Mensaje recibido: '$message'")
                println("LowerMessage: '$lowerMessage'")
                
                when {
                    (lowerMessage.contains("cambiar rutina") && !lowerMessage.contains("cambiar alimento")) -> {
                        println("=== ✅ DETECTADO: Cambiar rutina ===")
                        
                        if (userProfile == null) {
                            return ChatbotResponse(
                                respuesta = "Lo siento, no se pudo obtener su perfil de usuario. Por favor, inténtelo de nuevo.",
                                tipoIntento = TipoIntento.Otros,
                                tipoAccion = null
                            )
                        }
                        
                        // Generar nueva rutina personalizada
                        try {
                            val routineResult = generatePersonalizedRoutineFromDatabase(userProfile, userId)
                            
                            if (routineResult.success) {
                                // Actualizar estado con nueva rutina
                                conversationStates[userId] = ConversationState(
                                    userId = userId,
                                    currentStep = NutriAIStep.ROUTINE_GENERATED,
                                    generatedRoutine = routineResult.formattedRoutine,
                                    generatedRoutineItems = routineResult.routineItems
                                )
                                
                                val optionsText = "\n\nEscriba <b>cambiar rutina</b> si desea generar una nueva rutina.\n" +
                                                "Escriba <b>finalizar</b> para guardar la rutina."
                                
                                return ChatbotResponse(
                                    respuesta = "Esta es tu nueva rutina:\n\n" +
                                               routineResult.formattedRoutine + optionsText,
                                    tipoIntento = TipoIntento.Modificar_Rutina,
                                    tipoAccion = TipoAccion.Modificar
                                )
                            } else {
                                return ChatbotResponse(
                                    respuesta = routineResult.errorMessage ?: "Lo siento, no se pudo generar una nueva rutina. Por favor, inténtelo de nuevo.",
                                    tipoIntento = TipoIntento.Otros,
                                    tipoAccion = null
                                )
                            }
                        } catch (e: Exception) {
                            println("Error generando nueva rutina: ${e.message}")
                            e.printStackTrace()
                            return ChatbotResponse(
                                respuesta = "Lo siento, ocurrió un error al generar la nueva rutina. Por favor, inténtelo de nuevo más tarde.",
                                tipoIntento = TipoIntento.Otros,
                                tipoAccion = null
                            )
                        }
                    }
                    
                    lowerMessage.contains("finalizar") || 
                    lowerMessage.contains("estoy satisfecho") ||
                    lowerMessage.contains("satisfecho") -> {
                        // Finalizar y guardar la rutina en la base de datos
                        val routineItems = currentState.generatedRoutineItems ?: emptyList()
                        
                        if (routineItems.isEmpty()) {
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            ChatbotResponse(
                                respuesta = "Lo siento, no hay una rutina para guardar. Por favor, genere una rutina primero.",
                                tipoIntento = TipoIntento.Otros,
                                tipoAccion = null
                            )
                        } else {
                            // Eliminar alimentos anteriores del día actual antes de guardar la nueva rutina
                            val fechaActual = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                            val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
                            
                            momentos.forEach { momento ->
                                try {
                                    val response = repository.eliminarRegistrosPorFechaYMomento(userId, fechaActual, momento)
                                    if (response.isSuccessful) {
                                        println("✅ Alimentos eliminados para $momento del día $fechaActual")
                                    }
                                } catch (e: Exception) {
                                    println("⚠️ Error eliminando alimentos de $momento: ${e.message}")
                                }
                            }
                            
                            // Guardar cada alimento de la rutina en la base de datos
                            var successCount = 0
                            var errorCount = 0
                            val alimentosGuardados = mutableListOf<String>()
                            val alimentosFallidos = mutableListOf<Pair<String, String>>() // (nombre, error)
                            
                            println("🔄 Iniciando guardado de ${routineItems.size} alimentos de la rutina")
                            
                            routineItems.forEachIndexed { index, item ->
                                val (momento, alimentoInfo) = item
                                val (nombreAlimento, cantidadUnidad, idAlimento) = alimentoInfo
                                
                                println("📝 Guardando alimento ${index + 1}/${routineItems.size}: $nombreAlimento ($momento)")
                                println("   Cantidad/Unidad original: $cantidadUnidad")
                                
                                // Parsear cantidad y unidad (formato: "100.0 gramos" o "104,0 gramos" o "100.0 g")
                                val partes = cantidadUnidad.trim().split(" ", limit = 2)
                                var cantidadStr = partes[0].replace(",", ".").trim() // Asegurar punto decimal
                                val unidadOriginal = if (partes.size > 1) partes[1] else ""
                                
                                // Convertir a float y validar
                                val cantidadFloat = cantidadStr.toFloatOrNull()
                                if (cantidadFloat == null || cantidadFloat <= 0f) {
                                    println("   ❌ Error: Cantidad inválida '$cantidadStr' para $nombreAlimento")
                                    errorCount++
                                    return@forEachIndexed
                                }
                                
                                // Formatear a un decimal para guardar (usar punto decimal)
                                cantidadStr = "%.1f".format(cantidadFloat)
                                
                                // La unidad ya viene en el string de la rutina (es la unidad válida del alimento)
                                // Si no viene, se obtendrá en agregarAlimentoDesdeChatbot
                                val unidad = unidadOriginal.ifBlank { "gramos" }
                                
                                println("   Cantidad parseada: $cantidadStr (float: $cantidadFloat)")
                                println("   Unidad parseada: $unidad (unidad válida del alimento)")
                                
                                try {
                                    val success = repository.agregarAlimentoDesdeChatbot(
                                        idUsuario = userId,
                                        nombreAlimento = nombreAlimento,
                                        cantidad = cantidadStr,
                                        unidad = unidad,
                                        momentoDelDia = momento
                                    )
                                    if (success) {
                                        successCount++
                                        alimentosGuardados.add("$nombreAlimento ($momento)")
                                        println("   ✅ Guardado exitoso")
                                    } else {
                                        errorCount++
                                        alimentosFallidos.add(Pair(nombreAlimento, "Error del backend (retornó false)"))
                                        println("   ❌ Error: No se pudo guardar (retornó false)")
                                    }
                                } catch (e: Exception) {
                                    errorCount++
                                    val errorMsg = e.message ?: "Error desconocido"
                                    alimentosFallidos.add(Pair(nombreAlimento, errorMsg))
                                    println("   ❌ Excepción al guardar: $errorMsg")
                                    e.printStackTrace()
                                }
                            }
                            
                            println("📊 Resumen de guardado: $successCount exitosos, $errorCount errores de ${routineItems.size} totales")
                            if (alimentosGuardados.isNotEmpty()) {
                                println("✅ Alimentos guardados exitosamente: ${alimentosGuardados.joinToString(", ")}")
                            }
                            if (alimentosFallidos.isNotEmpty()) {
                                println("❌ Alimentos que fallaron: ${alimentosFallidos.joinToString(", ") { "${it.first} (${it.second})" }}")
                            }
                            
                            conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                            
                            if (successCount > 0) {
                                // Esperar un momento para asegurar que todas las operaciones de base de datos se completen
                                kotlinx.coroutines.delay(500)
                                
                                // Notificar que la rutina se actualizó DESPUÉS de guardar exitosamente
                                // Esto asegura que la pantalla se actualice con los nuevos alimentos
                                println("🔄 Invocando callback onRoutineUpdated después de guardar $successCount alimentos")
                                onRoutineUpdated?.invoke()
                                println("✅ Callback onRoutineUpdated invocado")
                                
                                // Construir mensaje de respuesta
                                val mensajeExito = if (errorCount > 0) {
                                    val alimentosGuardadosTexto = alimentosGuardados.joinToString("\n• ", "• ")
                                    val alimentosFallidosTexto = if (alimentosFallidos.isNotEmpty()) {
                                        "\n\n⚠️ Los siguientes alimentos no se pudieron guardar debido a un error técnico:\n" +
                                        alimentosFallidos.joinToString("\n• ", "• ") { "${it.first}" } +
                                        "\n\nPuede agregarlos manualmente desde la pantalla de Rutina y Consumo Diario."
                                    } else {
                                        ""
                                    }
                                    
                                    "Se guardaron $successCount de ${routineItems.size} alimentos correctamente:\n$alimentosGuardadosTexto$alimentosFallidosTexto"
                                } else {
                                    "La rutina ha sido guardada correctamente.\n\n" +
                                    "Gracias por utilizar NutriAI. Esperamos que este plan contribuya de manera positiva a sus objetivos de bienestar. Puede consultar su rutina en la pantalla 'Rutina y Consumo diario'."
                                }
                                
                                return ChatbotResponse(
                                    respuesta = mensajeExito,
                                    tipoIntento = TipoIntento.Modificar_Rutina,
                                    tipoAccion = TipoAccion.Agregar
                                )
                            } else {
                                println("⚠️ No se invocó el callback porque no hubo alimentos guardados exitosamente")
                                val erroresTexto = if (alimentosFallidos.isNotEmpty()) {
                                    "\n\nErrores encontrados:\n" + alimentosFallidos.joinToString("\n") { 
                                        "• ${it.first}: ${it.second}" 
                                    }
                                } else {
                                    ""
                                }
                                
                                return ChatbotResponse(
                                    respuesta = "No se pudo guardar la rutina. Por favor, intente nuevamente.$erroresTexto",
                                    tipoIntento = TipoIntento.Otros,
                                    tipoAccion = null
                                )
                            }
                        }
                    }
                    
                    lowerMessage.contains("cancelar") -> {
                        // Cancelar
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        
                        ChatbotResponse(
                            respuesta = "Proceso cancelado correctamente. Si desea volver a generar una rutina o resolver alguna duda, estaré disponible para ayudarle.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                    
                    else -> {
                        // Redireccionar al mensaje anterior mostrando la rutina actual y las opciones disponibles
                        // IMPORTANTE: En ROUTINE_GENERATED solo se permiten "cambiar rutina" y "finalizar"
                        val routineItems = currentState.generatedRoutineItems ?: emptyList()
                        val routineText = if (routineItems.isNotEmpty()) {
                            formatRoutineForDisplay(routineItems) + "\n\n"
                        } else {
                            ""
                        }
                        
                        // Verificar si el usuario intentó usar comandos no permitidos
                        val mensajeError = if (lowerMessage.contains("cambiar alimento") || lowerMessage.contains("agregar alimento") || 
                                             lowerMessage.contains("añadir alimento") || lowerMessage.contains("modificar alimento")) {
                            "⚠️ <b>Opción no disponible:</b> En la opción 'Generar una rutina personalizada para usted' solo puede:\n\n"
                        } else {
                            "No entendí su mensaje. Por favor, escriba una de las opciones disponibles:\n\n"
                        }
                        
                        ChatbotResponse(
                            respuesta = "$mensajeError$routineText" +
                                       "Escriba <b>cambiar rutina</b> si desea generar una nueva rutina.\n" +
                                       "Escriba <b>finalizar</b> para guardar la rutina.",
                            tipoIntento = TipoIntento.Modificar_Rutina,
                            tipoAccion = TipoAccion.Agregar
                        )
                    }
                }
            }
            
            NutriAIStep.ROUTINE_CHANGE_FOOD -> {
                val alimentoACambiar = message.trim()
                val routineItems = currentState.generatedRoutineItems ?: emptyList()
                
                // Buscar el alimento en la rutina
                val alimentoEncontrado = routineItems.find { 
                    it.second.first.equals(alimentoACambiar, ignoreCase = true) 
                }
                
                if (alimentoEncontrado == null) {
                    // Redireccionar al mensaje anterior mostrando la rutina actual
                    val routineText = formatRoutineForDisplay(routineItems)
                    val optionsText = "\n\nEscriba <b>cambiar rutina</b> si desea generar una nueva rutina.\n" +
                                    "Escriba <b>finalizar</b> para guardar la rutina."
                    
                    return ChatbotResponse(
                        respuesta = "No se encontró el alimento <b>$alimentoACambiar</b> en su rutina actual.\n\n" +
                                   "Esta es su rutina actual:\n\n" +
                                   "$routineText$optionsText",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
                
                if (userProfile == null) {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    return ChatbotResponse(
                        respuesta = "Lo siento, no se pudo obtener su perfil de usuario. Por favor, inténtelo de nuevo.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
                
                // Obtener alimentos disponibles de la base de datos
                val allFoods = try {
                    repository.obtenerTodos()
                } catch (e: Exception) {
                    emptyList()
                }
                
                if (allFoods.isEmpty()) {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    return ChatbotResponse(
                        respuesta = "Lo siento, no hay alimentos disponibles en la base de datos para realizar el cambio.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
                
                // Obtener el alimento original de la base de datos
                val alimentoOriginal = allFoods.find { 
                    it.nombreAlimento.equals(alimentoACambiar, ignoreCase = true) 
                } ?: return ChatbotResponse(
                    respuesta = "No se encontró el alimento en la base de datos.",
                    tipoIntento = TipoIntento.Otros,
                    tipoAccion = null
                )
                
                // Obtener la categoría del alimento original
                val categoriaOriginal = alimentoOriginal.categoria
                
                // Obtener alimentos de la misma categoría
                val alimentosMismaCategoria = try {
                    repository.obtenerAlimentosPorCategoriaParaChatbot(categoriaOriginal)
                } catch (e: Exception) {
                    emptyList()
                }
                
                // Filtrar alimentos ya seleccionados en la rutina y el alimento original
                val alimentosYaSeleccionados = routineItems.map { it.second.first.lowercase() }.toSet()
                val alimentosDisponibles = alimentosMismaCategoria.filter { 
                    val nombreLower = it.nombreAlimento.lowercase()
                    !alimentosYaSeleccionados.contains(nombreLower) &&
                    !nombreLower.equals(alimentoACambiar, ignoreCase = true)
                }
                
                if (alimentosDisponibles.isEmpty()) {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    return ChatbotResponse(
                        respuesta = "Lo siento, no hay otros alimentos disponibles en la categoría <b>$categoriaOriginal</b> para reemplazar <b>$alimentoACambiar</b>.\n\n" +
                                   "Puede intentar cambiar otro alimento de la rutina.",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
                
                // Mostrar lista de alimentos de la misma categoría
                val alimentosTexto = alimentosDisponibles.joinToString("\n") { "• ${it.nombreAlimento}" }
                
                // Guardar estado para que el usuario seleccione un alimento
                conversationStates[userId] = currentState.copy(
                    originalFood = alimentoACambiar,
                    selectedCategory = categoriaOriginal,
                    availableFoods = alimentosDisponibles,
                    currentStep = NutriAIStep.CHANGE_SHOW_FOODS
                )
                
                return ChatbotResponse(
                    respuesta = "Desea cambiar <b>$alimentoACambiar</b>.\n\n" +
                               "Estos son los alimentos disponibles en la categoría <b>$categoriaOriginal</b>:\n\n" +
                               "$alimentosTexto\n\n" +
                               "Por favor, elija uno de la lista para reemplazar <b>$alimentoACambiar</b>.",
                    tipoIntento = TipoIntento.Modificar_Rutina,
                    tipoAccion = TipoAccion.Modificar
                )
            }
            
            NutriAIStep.ROUTINE_CHANGE_CONFIRM -> {
                val lowerMessage = message.lowercase().trim()
                val routineItems = currentState.generatedRoutineItems ?: emptyList()
                val alimentoOriginal = currentState.originalFood ?: ""
                val alimentoReemplazo = currentState.selectedFood
                
                if (lowerMessage.contains("sí") || lowerMessage.contains("si") || 
                    lowerMessage.contains("confirmo") || lowerMessage.contains("confirmar")) {
                    // Confirmar el cambio
                    if (userProfile == null || alimentoReemplazo == null) {
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        return ChatbotResponse(
                            respuesta = "Lo siento, no se pudo procesar la confirmación. Por favor, inténtelo de nuevo.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                    
                    // Usar el alimento ya seleccionado
                    val alimentoValido = alimentoReemplazo
                    
                    println("🔄 PROCESANDO REEMPLAZO DE ALIMENTO")
                    println("   Alimento original a reemplazar: '$alimentoOriginal'")
                    println("   Alimento nuevo: '${alimentoValido.nombreAlimento}'")
                    println("   Rutina actual tiene ${routineItems.size} alimentos")
                    routineItems.forEach { item ->
                        println("   - ${item.first}: ${item.second.first}")
                    }
                    
                    // Encontrar el alimento original en la rutina
                    val alimentoEncontrado = routineItems.find { 
                        it.second.first.equals(alimentoOriginal, ignoreCase = true) 
                    }
                    
                    if (alimentoEncontrado == null) {
                        println("❌ ERROR: No se encontró '$alimentoOriginal' en la rutina")
                        conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                        return ChatbotResponse(
                            respuesta = "No se encontró el alimento <b>$alimentoOriginal</b> en la rutina actual.",
                            tipoIntento = TipoIntento.Otros,
                            tipoAccion = null
                        )
                    }
                    
                    val momento = alimentoEncontrado.first
                    println("✅ Alimento encontrado en momento: $momento")
                    
                    // Obtener unidades disponibles
                    val unidadesDisponibles = try {
                        repository.obtenerUnidadesPorId(alimentoValido.idAlimento)
                    } catch (e: Exception) {
                        listOf(alimentoValido.unidadBase)
                    }
                    
                    // Seleccionar unidad válida (coherente con el tipo de alimento)
                    val unidadValida = selectAppropriateUnitForFood(alimentoValido, unidadesDisponibles)
                    
                    // Calcular cantidad
                    val cantidadEnGramosStr = calculateFoodQuantity(alimentoValido, userProfile, momento, unidadValida)
                    val cantidadEnGramos = cantidadEnGramosStr.replace(",", ".").toFloatOrNull() ?: 0f
                    
                    val cantidadFinal = if (unidadValida.lowercase().contains("gramo") || unidadValida.lowercase() == "g") {
                        cantidadEnGramosStr
                    } else {
                        val baseQuantity = alimentoValido.cantidadBase
                        val cantidadEnUnidades = (cantidadEnGramos / baseQuantity).coerceIn(0.5f, 10f)
                        val cantidadEntera = cantidadEnUnidades.roundToInt().coerceAtLeast(1)
                        cantidadEntera.toString()
                    }
                    
                    // Actualizar la rutina - SOLO reemplazar el alimento específico, mantener el resto igual
                    val updatedRoutineItems = routineItems.map { item ->
                        if (item.second.first.equals(alimentoOriginal, ignoreCase = true)) {
                            println("   ✅ Reemplazando '${item.second.first}' por '${alimentoValido.nombreAlimento}' en $momento")
                            Pair(momento, Triple(alimentoValido.nombreAlimento, "$cantidadFinal $unidadValida", alimentoValido.idAlimento.toString()))
                        } else {
                            item // Mantener los demás alimentos sin cambios
                        }
                    }
                    
                    println("📋 Rutina actualizada:")
                    updatedRoutineItems.forEach { item ->
                        println("   - ${item.first}: ${item.second.first} - ${item.second.second}")
                    }
                    
                    // Actualizar estado - IMPORTANTE: mantener la misma rutina, solo cambiar el alimento específico
                    conversationStates[userId] = currentState.copy(
                        generatedRoutineItems = updatedRoutineItems,
                        generatedRoutine = formatRoutineForDisplay(updatedRoutineItems),
                        currentStep = NutriAIStep.ROUTINE_GENERATED
                    )
                    
                    val optionsText = "\n\nEscriba <b>cambiar rutina</b> si desea generar una nueva rutina.\n" +
                                    "Escriba <b>finalizar</b> para guardar la rutina."
                    
                    return ChatbotResponse(
                        respuesta = "<b>$alimentoOriginal</b> ha sido reemplazado por <b>${alimentoValido.nombreAlimento}</b>.\n\n" +
                                   formatRoutineForDisplay(updatedRoutineItems) +
                                   optionsText,
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else if (lowerMessage.contains("no") || lowerMessage.contains("cancelar")) {
                    // Cancelar el cambio
                    conversationStates[userId] = currentState.copy(
                        currentStep = NutriAIStep.ROUTINE_GENERATED
                    )
                    
                    val optionsText = "\n\nEscriba <b>cambiar rutina</b> si desea generar una nueva rutina.\n" +
                                    "Escriba <b>finalizar</b> para guardar la rutina."
                    
                    return ChatbotResponse(
                        respuesta = "Cambio cancelado. La rutina se mantiene sin modificaciones.\n\n" +
                                   formatRoutineForDisplay(routineItems) +
                                   optionsText,
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    return ChatbotResponse(
                        respuesta = "Por favor, responda 'sí' para confirmar el reemplazo o 'no' para cancelar.",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
            }
            
            NutriAIStep.ROUTINE_CHANGE_COMPLETE -> {
                val nuevoAlimento = message.trim()
                val routineItems = currentState.generatedRoutineItems ?: emptyList()
                val alimentoOriginal = currentState.originalFood ?: ""
                val availableFoods = currentState.availableFoods ?: emptyList()
                
                if (userProfile == null) {
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE)
                    return ChatbotResponse(
                        respuesta = "Lo siento, no se pudo obtener su perfil de usuario. Por favor, inténtelo de nuevo.",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
                
                // Smart cast: userProfile es no-null después de la verificación
                val profile: Usuario = userProfile
                
                // Referencia local a las funciones para evitar problemas de scope
                val formatearRutina: (List<Pair<String, Triple<String, String, String>>>) -> String = { items ->
                    val grouped = items.groupBy { it.first }
                    val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
                    momentos.joinToString("\n\n") { momento ->
                        val emoji = when (momento) {
                            "Desayuno" -> "🌅"
                            "Almuerzo" -> "🌞"
                            "Cena" -> "🌙"
                            "Snack" -> "🍎"
                            else -> "🍽️"
                        }
                        val alimentos = grouped[momento] ?: emptyList()
                        if (alimentos.isEmpty()) {
                            "$emoji <b>$momento:</b>\n- No hay alimentos asignados"
                        } else {
                            "$emoji <b>$momento:</b>\n" + alimentos.joinToString("\n") { 
                                // Formato: nombre alimento - cantidad unidad (un decimal)
                                val cantidadUnidad = it.second.second
                                val partes = cantidadUnidad.trim().split(" ", limit = 2)
                                val cantidad = partes[0].replace(",", ".")
                                val unidad = if (partes.size > 1) partes[1] else "gramos"
                                
                                // Formatear a un decimal (usar punto decimal)
                                val cantidadFloat = cantidad.toFloatOrNull() ?: 0f
                                val cantidadFormateada = "%.1f".format(cantidadFloat).replace(",", ".")
                                
                                "  - ${it.second.first} - $cantidadFormateada $unidad"
                            }
                        }
                    }
                }
                
                val calcularCantidad: (Alimento, Usuario, String, String) -> String = 
                    { alimento, userProfile, momento, unidad ->
                        val baseQuantity = alimento.cantidadBase
                        val momentoMultiplier = when (momento) {
                            "Desayuno" -> 0.8f
                            "Almuerzo" -> 1.2f
                            "Cena" -> 0.9f
                            "Snack" -> 0.5f
                            else -> 1.0f
                        }
                        val pesoMultiplier = (userProfile.peso / 70.0f).coerceIn(0.7f, 1.3f)
                        
                        // Obtener objetivo del usuario directamente desde el perfil
                        val objetivoLower = userProfile.objetivosSalud.lowercase()
                        val objetivoMultiplier = when {
                            objetivoLower.contains("bajar peso") || objetivoLower.contains("perder peso") || 
                            objetivoLower.contains("adelgazar") || objetivoLower.contains("definir") -> when {
                                alimento.calorias > 300f -> 0.7f
                                alimento.azucares > 15f -> 0.75f
                                alimento.grasas > 10f -> 0.8f
                                else -> 0.85f
                            }
                            objetivoLower.contains("ganar músculo") || objetivoLower.contains("ganar musculo") ||
                            objetivoLower.contains("aumentar masa") || objetivoLower.contains("subir peso") -> when {
                                alimento.proteinas > 15f -> 1.3f
                                alimento.carbohidratos > 20f -> 1.25f
                                alimento.calorias > 200f -> 1.2f
                                else -> 1.15f
                            }
                            else -> 1.0f
                        }
                        
                        var cantidadEnGramos = baseQuantity * momentoMultiplier * pesoMultiplier * objetivoMultiplier
                        
                        // Ajustar límites según objetivo
                        cantidadEnGramos = when {
                            objetivoLower.contains("bajar peso") || objetivoLower.contains("perder peso") || 
                            objetivoLower.contains("adelgazar") -> cantidadEnGramos.coerceIn(30f, 350f)
                            objetivoLower.contains("ganar músculo") || objetivoLower.contains("ganar musculo") ||
                            objetivoLower.contains("aumentar masa") -> cantidadEnGramos.coerceIn(80f, 600f)
                            else -> cantidadEnGramos.coerceIn(50f, 500f)
                        }
                        
                        val unidadLower = unidad.lowercase()
                        when {
                            unidadLower.contains("porción") || unidadLower.contains("porcion") || 
                            unidadLower.contains("unidad") || unidadLower.contains("pieza") ||
                            unidadLower.contains("rebanada") || unidadLower.contains("filete") -> {
                                val cantidadPorciones = (cantidadEnGramos / baseQuantity).coerceIn(0.5f, 5f)
                                cantidadPorciones.toInt().coerceAtLeast(1).toString()
                            }
                            unidadLower.contains("cucharada") -> {
                                val gramosPorCucharada = 12f
                                val cantidadCucharadas = (cantidadEnGramos / gramosPorCucharada).coerceIn(1f, 10f)
                                cantidadCucharadas.toInt().toString()
                            }
                            unidadLower.contains("cucharadita") -> {
                                val gramosPorCucharadita = 5f
                                val cantidadCucharaditas = (cantidadEnGramos / gramosPorCucharadita).coerceIn(1f, 15f)
                                cantidadCucharaditas.toInt().toString()
                            }
                            else -> cantidadEnGramos.toInt().toString()
                        }
                    }
                
                // Buscar el nuevo alimento en la lista de disponibles
                val alimentoValido = availableFoods.find { 
                    it.nombreAlimento.equals(nuevoAlimento, ignoreCase = true) 
                }
                
                if (alimentoValido != null) {
                    // Obtener unidades disponibles para el nuevo alimento
                    val unidadesNuevoAlimento = try {
                        repository.obtenerUnidadesPorId(alimentoValido.idAlimento)
                    } catch (e: Exception) {
                        listOf(alimentoValido.unidadBase)
                    }
                    
                    val unidadSeleccionada = when {
                        unidadesNuevoAlimento.contains("porción") || unidadesNuevoAlimento.contains("porcion") -> 
                            unidadesNuevoAlimento.first { it.lowercase().contains("porción") || it.lowercase().contains("porcion") }
                        unidadesNuevoAlimento.contains("unidad") -> 
                            unidadesNuevoAlimento.first { it.lowercase().contains("unidad") }
                        unidadesNuevoAlimento.isNotEmpty() -> unidadesNuevoAlimento.first()
                        else -> alimentoValido.unidadBase
                    }
                    
                    val momentoDelAlimento = routineItems.find { it.second.first.equals(alimentoOriginal, ignoreCase = true) }?.first ?: "Almuerzo"
                    // Smart cast explícito para alimentoValido
                    val alimento: Alimento = alimentoValido
                    // Calcular cantidad usando la función local (sin criterios nutricionales)
                    val cantidadNueva: String = calcularCantidad(alimento, profile, momentoDelAlimento, unidadSeleccionada)
                    
                    // Reemplazar el alimento en la rutina
                    val updatedRoutineItems = routineItems.map { item ->
                        if (item.second.first.equals(alimentoOriginal, ignoreCase = true)) {
                            // Cambiar el alimento, cantidad y unidad
                            Pair(item.first, Triple(alimentoValido.nombreAlimento, "$cantidadNueva $unidadSeleccionada", alimentoValido.idAlimento.toString()))
                        } else {
                            item
                        }
                    }
                    
                    // NOTA: El cambio NO se guarda automáticamente aquí
                    // Solo se guardará cuando el usuario confirme con "finalizar"
                    println("✅ Alimento cambiado en la rutina (pendiente de guardar): ${alimentoValido.nombreAlimento}")
                    
                    // Actualizar el estado con la rutina modificada
                    conversationStates[userId] = currentState.copy(
                        generatedRoutineItems = updatedRoutineItems,
                        generatedRoutine = formatearRutina(updatedRoutineItems),
                        currentStep = NutriAIStep.ROUTINE_GENERATED
                    )
                    
                    val optionsText = "\n\nEscriba <b>cambiar rutina</b> si desea generar una nueva rutina.\n" +
                                    "Escriba <b>finalizar</b> para guardar la rutina."
                    
                    ChatbotResponse(
                        respuesta = "Alimento actualizado en la rutina:\n\n" +
                                   formatearRutina(updatedRoutineItems) +
                                   optionsText,
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                } else {
                    // Redireccionar al mensaje anterior con la lista de alimentos disponibles
                    val alimentosTexto = availableFoods.joinToString("\n") { "• ${it.nombreAlimento}" }
                    val categoria = currentState.selectedCategory ?: "la categoría seleccionada"
                    
                    ChatbotResponse(
                        respuesta = "No se encontró el alimento <b>$nuevoAlimento</b> en la lista de opciones.\n\n" +
                                   "Estos son los alimentos disponibles en la categoría <b>$categoria</b>:\n\n" +
                                   "$alimentosTexto\n\n" +
                                   "Por favor, elija uno de la lista para reemplazar <b>$alimentoOriginal</b>.",
                        tipoIntento = TipoIntento.Modificar_Rutina,
                        tipoAccion = TipoAccion.Modificar
                    )
                }
            }
            
            NutriAIStep.VIEWING_ROUTINE -> {
                val lowerMessage = message.lowercase().trim()
                
                // Verificar si el mensaje es una de las opciones válidas
                val isAgregarAlimento = lowerMessage.contains("agregar alimento") || lowerMessage.contains("añadir alimento")
                val isCambiarAlimento = lowerMessage.contains("cambiar alimento")
                val isVerRutinaFecha = lowerMessage.contains("ver rutina") && lowerMessage.matches(Regex(".*ver rutina \\d{4}-\\d{2}-\\d{2}.*"))
                val isVerRutina = lowerMessage == "ver rutina" || lowerMessage == "mostrar su rutina nutricional actual" || 
                                 lowerMessage.contains("mostrar rutina nutricional actual")
                
                if (isVerRutinaFecha || isVerRutina) {
                    // Extraer la fecha del mensaje si existe
                    val datePattern = extractDateFromMessage(message)
                    val viewingDate = if (datePattern != null && datePattern.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                        // Validar la fecha
                        try {
                            val parsedDate = LocalDate.parse(datePattern, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                            val year = parsedDate.year
                            if (year >= 1900 && year <= 2100) {
                                println("✅ Fecha válida detectada en VIEWING_ROUTINE: $datePattern")
                                datePattern
                            } else {
                                println("❌ Año inválido: $year")
                                currentState.viewingDate
                            }
                        } catch (e: Exception) {
                            println("❌ Error parseando fecha: ${e.message}")
                            currentState.viewingDate
                        }
                    } else {
                        null
                    }
                    // Actualizar el estado con la nueva fecha consultada
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.VIEWING_ROUTINE, viewingDate = viewingDate)
                    println("=== ESTADO ACTUALIZADO EN VIEWING_ROUTINE ===")
                    println("Nueva viewingDate: $viewingDate")
                    // IMPORTANTE: Retornar null para que el código en la línea 358-406 procese la rutina con la nueva fecha
                    null
                } else if (isAgregarAlimento || isCambiarAlimento) {
                    // Si es una opción válida, resetear el estado y retornar null para que el flujo normal lo procese
                    // Mantener la fecha consultada si existe
                    conversationStates[userId] = ConversationState(userId, NutriAIStep.IDLE, viewingDate = currentState.viewingDate)
                    null
                } else {
                    // Si NO es una opción válida, redireccionar mostrando la rutina y las opciones disponibles
                    val userName = userProfile?.nombre ?: ""
                    val greeting = if (userName.isNotBlank() && userName != "Usuario") "👋 ¡Hola $userName!" else "👋 ¡Hola!"
                    val viewingDate = currentState.viewingDate
                    
                    // IMPORTANTE: Si hay una fecha específica, consultar la rutina de esa fecha, NO usar currentRoutine
                    val routineToShow = if (viewingDate != null) {
                        val userId = userProfile?.idUsuario ?: 0L
                        println("=== VIEWING_ROUTINE: Consultando rutina para fecha específica: $viewingDate ===")
                        getRoutineForSpecificDate(viewingDate, userId)
                    } else {
                        println("=== VIEWING_ROUTINE: Usando rutina de hoy ===")
                        currentRoutine
                    }
                    
                    val dateContext = if (viewingDate != null) viewingDate else "hoy"
                    val isCurrentDate = viewingDate == null || try {
                        val consultedDate = LocalDate.parse(viewingDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        val today = LocalDate.now()
                        consultedDate == today
                    } catch (e: Exception) {
                        false
                    }
                    println("=== VIEWING_ROUTINE: RoutineToShow tiene ${routineToShow?.size ?: 0} elementos ===")
                    val routineContent = generateRoutineContent(routineToShow, dateContext, isCurrentDate)
                    val dateHeader = if (dateContext == "hoy") {
                        val today = LocalDate.now()
                        val dateFormatted = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        "<b>RUTINA DE HOY ($dateFormatted)</b>"
                    } else {
                        "<b>RUTINA DEL $dateContext</b>"
                    }
                    val optionsText = "\n\n<b>Opciones disponibles:</b>\n\n" +
                                    "Escriba <b>agregar alimento</b> si desea incluir un nuevo alimento.\n\n" +
                                    "Escriba <b>cambiar alimento</b> si desea reemplazar un alimento existente.\n\n" +
                                    "Escriba <b>ver rutina YYYY-MM-DD</b> si desea consultar la rutina de otra fecha.\n" +
                                    "👉 <b>Ejemplo:</b> ver rutina 2025-10-05\n\n" +
                                    "⚠️ <b>Nota:</b> Las opciones de agregar alimento y cambiar alimento solo están disponibles para la rutina del día actual."
                    
                    ChatbotResponse(
                        respuesta = "❌ <b>Comando no reconocido.</b>\n\n" +
                                   "$greeting\n\n" +
                                   "$dateHeader\n\n" +
                                   "$routineContent\n\n$optionsText",
                        tipoIntento = TipoIntento.Otros,
                        tipoAccion = null
                    )
                }
            }
            
            else -> null
        }
    }
    
    // Function to generate user profile display with visual format
    private fun generateUserProfileDisplay(userProfile: Usuario?): String {
        if (userProfile == null) {
            return "❌ No se puede generar la rutina\n\n" +
                   "Para crear una rutina personalizada, necesita configurar su perfil de usuario primero.\n\n" +
                   "💡 ¿Cómo configurar su perfil?\n" +
                   "1. Vaya a la sección 'Perfil'\n" +
                   "2. Complete su información personal\n" +
                   "3. Especifique sus objetivos de salud\n" +
                   "4. ¡Listo! Podrá generar rutinas personalizadas"
        }

        val edad: Int = try {
            val formato = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val fechaNac = formato.parse(userProfile.fechaNacimiento)
            val hoy = java.util.Date()
            val diffInMillies = hoy.time - fechaNac.time
            val diffInDays = diffInMillies / (24 * 60 * 60 * 1000)
            (diffInDays / 365.25).toInt()
        } catch (e: Exception) {
            30 // Edad por defecto si hay error
        }
        
        
        fun formatValue(value: String, defaultValue: String): String {
            return if (isDefaultValue(value)) defaultValue else value
        }
        
        // Formatear género para mostrar correctamente
        val genero = when (userProfile.sexo.lowercase()) {
            "m", "masculino" -> "Masculino"
            "f", "femenino" -> "Femenino"
            else -> formatValue(userProfile.sexo, "No especificado")
        }
        
        return "📋 Aquí tiene sus datos registrados:\n\n" +
               "👤 <b>Género:</b> $genero\n" +
               "🧠 <b>Edad:</b> $edad años\n" +
               "📏 <b>Altura:</b> ${formatValue(userProfile.altura.toString(), "No registrada")} cm\n" +
               "⚖️ <b>Peso actual:</b> ${formatValue(userProfile.peso.toString(), "No registrado")} kg\n" +
               "🎯 <b>Peso objetivo:</b> ${formatValue(userProfile.pesoObjetivo.toString(), "No establecido")} kg\n" +
               "🥗 <b>Tipo de dieta:</b> ${formatValue(userProfile.restriccionesDieta, "No especificada")}\n" +
               "🏃 <b>Nivel de actividad:</b> ${formatValue(userProfile.nivelActividad, "No especificado")}\n" +
               "💪 <b>Objetivo:</b> ${formatValue(userProfile.objetivosSalud, "No establecido")}"
    }
    
    // Helper function to generate user profile summary
    private fun generateUserProfileSummary(userProfile: Usuario?): String {
        if (userProfile == null) {
            return "❌ Perfil no disponible\n" +
                   "Para generar una rutina personalizada, necesita configurar su perfil de usuario."
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
    
    // Data class para el resultado de la generación de rutina
    private data class RoutineGenerationResult(
        val success: Boolean,
        val routineItems: List<Pair<String, Triple<String, String, String>>>,
        val formattedRoutine: String,
        val errorMessage: String? = null
    )
    
    /**
     * PROMPT PARA GENERACIÓN DE RUTINAS PERSONALIZADAS:
     * 
     * Eres NutriAI, un asistente nutricional que genera rutinas alimenticias 100% coherentes 
     * utilizando únicamente alimentos existentes en la base de datos del sistema. 
     * Nunca inventes alimentos ni cantidades.
     * 
     * Tu tarea es generar una rutina nutricional personalizada según los datos del usuario:
     * - Género
     * - Edad
     * - Altura
     * - Peso actual
     * - Peso objetivo
     * - Objetivo (pérdida de peso, mantener peso, ganancia de masa muscular, hábitos saludables)
     * - Tipo de dieta preferida (recomendada, alta en proteínas, baja en carbohidratos, keto, baja en grasas)
     * - Nivel de actividad física (sedentario, ligera actividad, actividad moderada, alta actividad, actividad extrema)
     * 
     * REGLAS OBLIGATORIAS:
     * 1. La rutina debe incluir exactamente 4 momentos del día en este orden:
     *    - Desayuno
     *    - Almuerzo
     *    - Cena
     *    - Snack
     * 
     * 2. Solo usar alimentos que EXISTEN en la base de datos, respetando:
     *    - Unidad de medida registrada en la base de datos.
     *    - Cantidad exacta manejada en la base de datos.
     *    - Nunca mostrar cantidades decimales. Si la base está en decimales, redondear de forma natural (0.8 → 1).
     * 
     * 3. Nunca repetir alimentos dentro de la misma rutina.
     * 
     * 4. La rutina debe ser coherente con:
     *    - El objetivo del usuario.
     *    - El tipo de dieta escogida.
     *    - Su nivel de actividad física.
     *    - Su peso actual vs peso objetivo.
     *    - Momento del día (ejemplo: no dar pasta en snack, no dar fruta alta en azúcar en cena si el objetivo es bajar de peso).
     * 
     * 5. Coherencia nutricional obligatoria:
     *    - Pérdida de peso → bajas calorías, bajas grasas saturadas, carbohidratos controlados.
     *    - Baja en carbohidratos → evitar tubérculos dulces, arroz blanco, pasta regular, frutas muy dulces.
     *    - Keto → carbohidratos extremadamente bajos, grasas saludables altas.
     *    - Alta en proteínas → priorizar fuentes de proteína magra.
     *    - Baja en grasas → eliminar quesos grasos, yema de huevo, fritos, carnes altas en grasa.
     *    - Sedentario → evitar carbohidratos altos especialmente en cena.
     * 
     * 6. Presentar la rutina de forma clara y legible:
     *    Ejemplo:
     *    Desayuno:
     *    • 1 porción de avena
     *    • 1 unidad de manzana
     *    • 2 claras de huevo
     * 
     * 7. Después de generar la rutina, mostrar siempre estas opciones textuales:
     *    "Escriba `cambiar rutina` si desea generar una nueva rutina."
     *    "Escriba `cambiar alimento` si desea reemplazar un alimento específico."
     *    "Escriba `finalizar` para guardar la rutina."
     * 
     * 8. No guardar ningún cambio hasta que el usuario escriba "finalizar".
     */
    // Función principal para generar rutina personalizada usando Gemini AI
    private suspend fun generatePersonalizedRoutineFromDatabase(
        userProfile: Usuario,
        userId: Long
    ): RoutineGenerationResult = withContext(Dispatchers.IO) {
        try {
            println("=== INICIANDO GENERACIÓN DE RUTINA PERSONALIZADA CON GEMINI ===")
            println("Usuario: ${userProfile.nombre}")
            println("Objetivo: ${userProfile.objetivosSalud}")
            println("Dieta: ${userProfile.restriccionesDieta}")
            
            // 1. Obtener todos los alimentos de la base de datos
            val allFoods = try {
                repository.obtenerTodos()
            } catch (e: Exception) {
                println("Error obteniendo alimentos: ${e.message}")
                return@withContext RoutineGenerationResult(
                    success = false,
                    routineItems = emptyList(),
                    formattedRoutine = "",
                    errorMessage = "No se pudieron obtener los alimentos de la base de datos."
                )
            }
            
            if (allFoods.isEmpty()) {
                return@withContext RoutineGenerationResult(
                    success = false,
                    routineItems = emptyList(),
                    formattedRoutine = "",
                    errorMessage = "No hay alimentos disponibles en la base de datos."
                )
            }
            
            println("✅ Alimentos obtenidos: ${allFoods.size}")
            
            // 2. Generar rutina usando Gemini AI
            val geminiRoutineText = try {
                geminiService.generatePersonalizedRoutine(
                    userProfile, 
                    allFoods,
                    obtenerUnidades = { idAlimento -> 
                        repository.obtenerUnidadesPorId(idAlimento)
                    }
                )
            } catch (e: Exception) {
                println("❌ Error generando rutina con Gemini: ${e.message}")
                e.printStackTrace()
                // Fallback a lógica programática si Gemini falla
                return@withContext generateRoutineProgrammatically(userProfile, allFoods)
            }
            
            println("✅ Rutina generada por Gemini")
            
            // 3. Parsear la respuesta de Gemini para extraer alimentos
            val parsedRoutine = parseGeminiRoutineResponse(geminiRoutineText, allFoods)
            
            // Verificar si la rutina está completa (debe tener al menos 8-10 alimentos para 4 momentos)
            val rutinaCompleta = parsedRoutine.routineItems.isNotEmpty() && 
                                 parsedRoutine.routineItems.size >= 8 &&
                                 parsedRoutine.routineItems.groupBy { it.first }.keys.size == 4 // 4 momentos del día
            
            if (parsedRoutine.routineItems.isEmpty() || !rutinaCompleta) {
                println("⚠️ Rutina de Gemini incompleta (${parsedRoutine.routineItems.size} alimentos, ${parsedRoutine.routineItems.groupBy { it.first }.keys.size} momentos), usando fallback")
                return@withContext generateRoutineProgrammatically(userProfile, allFoods)
            }
            
            // 4. Formatear rutina para mostrar
            val formattedRoutine = formatRoutineForDisplay(parsedRoutine.routineItems)
            
            return@withContext RoutineGenerationResult(
                success = true,
                routineItems = parsedRoutine.routineItems,
                formattedRoutine = formattedRoutine
            )
            
        } catch (e: Exception) {
            println("Error en generatePersonalizedRoutineFromDatabase: ${e.message}")
            e.printStackTrace()
            return@withContext RoutineGenerationResult(
                success = false,
                routineItems = emptyList(),
                formattedRoutine = "",
                errorMessage = "Ocurrió un error al generar la rutina: ${e.message}"
            )
        }
    }
    
    // Función de fallback: generar rutina programáticamente si Gemini falla
    private suspend fun generateRoutineProgrammatically(
        userProfile: Usuario,
        allFoods: List<Alimento>
    ): RoutineGenerationResult = withContext(Dispatchers.IO) {
        try {
            println("=== USANDO FALLBACK: GENERACIÓN PROGRAMÁTICA ===")
            
            // Filtrar alimentos según restricciones dietéticas
            var filteredFoods = filterByDietaryRestrictions(allFoods, userProfile.restriccionesDieta)
            println("✅ Alimentos después de filtrar por dieta: ${filteredFoods.size}")
            
            // 3. Generar rutina para cada momento del día
            val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
            val routineItems = mutableListOf<Pair<String, Triple<String, String, String>>>()
            val alimentosYaSeleccionados = mutableSetOf<String>()
            
            momentos.forEach { momento ->
                val alimentosParaMomento = selectFoodsForMeal(
                    availableFoods = filteredFoods,
                    momento = momento,
                    userProfile = userProfile,
                    alimentosYaSeleccionados = alimentosYaSeleccionados,
                    cantidadNecesaria = when (momento) {
                        "Desayuno", "Almuerzo", "Cena" -> 3
                        "Snack" -> 2
                        else -> 2
                    }
                )
                
                alimentosParaMomento.forEach { alimento ->
                    val nombreLower = alimento.nombreAlimento.lowercase()
                    if (!alimentosYaSeleccionados.contains(nombreLower)) {
                        alimentosYaSeleccionados.add(nombreLower)
                        
                        // Obtener unidades disponibles
                        val unidadesDisponibles = try {
                            repository.obtenerUnidadesPorId(alimento.idAlimento)
                        } catch (e: Exception) {
                            listOf(alimento.unidadBase)
                        }
                        
                        // Seleccionar una unidad válida: priorizar "porción", "unidad", "pieza", etc. sobre "gramos"
                        val unidadValida = when {
                            // Priorizar unidades más naturales para el usuario
                            unidadesDisponibles.any { it.lowercase() in listOf("porción", "porcion", "unidad", "pieza", "rebanada", "taza", "filete") } -> {
                                unidadesDisponibles.first { it.lowercase() in listOf("porción", "porcion", "unidad", "pieza", "rebanada", "taza", "filete") }
                            }
                            // Si no hay unidades naturales, usar gramos si está disponible
                            unidadesDisponibles.any { it.lowercase().contains("gramo") || it.lowercase().contains(" g") } -> {
                                unidadesDisponibles.first { it.lowercase().contains("gramo") || it.lowercase().contains(" g") }
                            }
                            // Si no hay ninguna de las anteriores, usar la primera disponible
                            unidadesDisponibles.isNotEmpty() -> unidadesDisponibles.first()
                            // Fallback a unidad base
                            else -> alimento.unidadBase
                        }
                        
                        println("📦 Alimento: ${alimento.nombreAlimento}, Unidades disponibles: $unidadesDisponibles, Unidad seleccionada: $unidadValida")
                        
                        // Calcular cantidad en gramos primero
                        val cantidadEnGramosStr = calculateFoodQuantity(alimento, userProfile, momento, unidadValida)
                        val cantidadEnGramos = cantidadEnGramosStr.replace(",", ".").toFloatOrNull() ?: 0f
                        
                        // Si la unidad no es gramos, convertir la cantidad a la unidad seleccionada
                        val cantidadFinal = if (unidadValida.lowercase().contains("gramo") || unidadValida.lowercase() == "g") {
                            // Si es gramos, usar la cantidad directamente (puede tener decimal)
                            cantidadEnGramosStr
                        } else {
                            // Si es otra unidad (porción, unidad, etc.), calcular cuántas unidades equivalen
                            // REGLA ESTRICTA: Nunca mostrar decimales para unidades no-gramos, redondear a entero
                            val baseQuantity = alimento.cantidadBase
                            val cantidadEnUnidades = (cantidadEnGramos / baseQuantity).coerceIn(0.5f, 10f)
                            // Redondear al entero más cercano (mínimo 1)
                            val cantidadEntera = cantidadEnUnidades.roundToInt().coerceAtLeast(1)
                            cantidadEntera.toString()
                        }
                        
                        // Guardar con la unidad válida del alimento
                        val cantidadUnidadString = "$cantidadFinal $unidadValida"
                        println("💾 Guardando rutina: ${alimento.nombreAlimento} - $cantidadUnidadString (${cantidadEnGramos}g en gramos)")
                        routineItems.add(Pair(momento, Triple(alimento.nombreAlimento, cantidadUnidadString, alimento.idAlimento.toString())))
                    }
                }
            }
            
            if (routineItems.isEmpty()) {
                return@withContext RoutineGenerationResult(
                    success = false,
                    routineItems = emptyList(),
                    formattedRoutine = "",
                    errorMessage = "No se pudieron seleccionar alimentos adecuados para su perfil."
                )
            }
            
            println("✅ Rutina generada con ${routineItems.size} alimentos")
            
            // NOTA: La rutina NO se guarda automáticamente aquí
            // Solo se guardará cuando el usuario confirme con "finalizar"
            
            // Formatear rutina para mostrar
            val formattedRoutine = formatRoutineForDisplay(routineItems)
            
            return@withContext RoutineGenerationResult(
                success = true,
                routineItems = routineItems,
                formattedRoutine = formattedRoutine
            )
            
        } catch (e: Exception) {
            println("Error en generatePersonalizedRoutineFromDatabase: ${e.message}")
            e.printStackTrace()
            return@withContext RoutineGenerationResult(
                success = false,
                routineItems = emptyList(),
                formattedRoutine = "",
                errorMessage = "Ocurrió un error al generar la rutina: ${e.message}"
            )
        }
    }
    
    // Filtrar alimentos según restricciones dietéticas y tipo de dieta
    private fun filterByDietaryRestrictions(
        alimentos: List<Alimento>,
        restricciones: String
    ): List<Alimento> {
        val restriccionesLower = restricciones.lowercase()
        var filtered = alimentos
        
        // Filtros por restricciones alimentarias
        when {
            restriccionesLower.contains("vegetariano") || restriccionesLower.contains("vegano") -> {
                filtered = filtered.filter { 
                    !it.nombreAlimento.lowercase().contains("pollo") &&
                    !it.nombreAlimento.lowercase().contains("carne") &&
                    !it.nombreAlimento.lowercase().contains("cerdo") &&
                    !it.nombreAlimento.lowercase().contains("pescado") &&
                    !it.nombreAlimento.lowercase().contains("atún") &&
                    !it.nombreAlimento.lowercase().contains("salmón") &&
                    !it.nombreAlimento.lowercase().contains("salmon")
                }
            }
            restriccionesLower.contains("sin gluten") || restriccionesLower.contains("celiaco") -> {
                filtered = filtered.filter {
                    !it.nombreAlimento.lowercase().contains("trigo") &&
                    !it.nombreAlimento.lowercase().contains("pan") &&
                    !it.nombreAlimento.lowercase().contains("harina")
                }
            }
            restriccionesLower.contains("lactosa") || restriccionesLower.contains("lácteo") -> {
                filtered = filtered.filter {
                    !it.nombreAlimento.lowercase().contains("leche") &&
                    !it.nombreAlimento.lowercase().contains("queso") &&
                    !it.nombreAlimento.lowercase().contains("yogur") &&
                    !it.nombreAlimento.lowercase().contains("yogurt")
                }
            }
        }
        
        // Filtros por tipo de dieta
        when {
            // Baja en carbohidratos: minimizar arroz, pasta, plátano, avena, pan
            restriccionesLower.contains("baja en carbohidratos") || 
            restriccionesLower.contains("baja carbohidratos") || 
            restriccionesLower.contains("low carb") -> {
                filtered = filtered.filter {
                    val nombreLower = it.nombreAlimento.lowercase()
                    !nombreLower.contains("arroz") &&
                    !nombreLower.contains("pasta") &&
                    !nombreLower.contains("plátano") &&
                    !nombreLower.contains("platano") &&
                    !nombreLower.contains("avena") &&
                    !nombreLower.contains("pan") &&
                    it.carbohidratos < 30f // Máximo 30g de carbohidratos por 100g
                }
            }
            // Keto: carbohidratos muy bajos, priorizar grasas saludables
            restriccionesLower.contains("keto") || restriccionesLower.contains("cetogénica") -> {
                filtered = filtered.filter {
                    val nombreLower = it.nombreAlimento.lowercase()
                    !nombreLower.contains("arroz") &&
                    !nombreLower.contains("pasta") &&
                    !nombreLower.contains("plátano") &&
                    !nombreLower.contains("platano") &&
                    !nombreLower.contains("avena") &&
                    !nombreLower.contains("pan") &&
                    it.carbohidratos < 10f // Máximo 10g de carbohidratos por 100g
                }
            }
            // Baja en grasas: evitar quesos altos en grasa, fritos, yemas múltiples
            restriccionesLower.contains("baja en grasas") || 
            restriccionesLower.contains("baja grasas") || 
            restriccionesLower.contains("low fat") -> {
                filtered = filtered.filter {
                    val nombreLower = it.nombreAlimento.lowercase()
                    !nombreLower.contains("frito") &&
                    !(nombreLower.contains("queso") && it.grasas > 15f) &&
                    !(nombreLower.contains("yema") && it.grasas > 20f) &&
                    it.grasas < 15f // Máximo 15g de grasa por 100g
                }
            }
            // Alta en proteínas: no filtrar, pero se priorizará en la selección
            restriccionesLower.contains("alta en proteínas") || 
            restriccionesLower.contains("alta proteinas") || 
            restriccionesLower.contains("high protein") -> {
                // No filtrar, solo priorizar en la selección
            }
            // Recomendada: equilibrio general, no filtrar
            restriccionesLower.contains("recomendada") || 
            restriccionesLower.contains("balanceada") || 
            restriccionesLower.isBlank() -> {
                // No filtrar, mantener equilibrio
            }
        }
        
        return filtered
    }
    
    // Seleccionar alimentos para un momento específico del día con coherencia nutricional
    private fun selectFoodsForMeal(
        availableFoods: List<Alimento>,
        momento: String,
        userProfile: Usuario,
        alimentosYaSeleccionados: Set<String>,
        cantidadNecesaria: Int
    ): List<Alimento> {
        // Filtrar alimentos ya seleccionados
        var candidatos = availableFoods.filter { 
            !alimentosYaSeleccionados.contains(it.nombreAlimento.lowercase())
        }
        
        // Filtrar según el momento del día (pero ser más flexible si no hay suficientes opciones)
        val candidatosPorMomento = when (momento) {
            "Desayuno" -> {
                val filtrados = candidatos.filter { isAppropriateForBreakfast(it) }
                println("🌅 Candidatos para Desayuno después de filtro: ${filtrados.size} de ${candidatos.size} totales")
                filtrados
            }
            "Almuerzo" -> candidatos.filter { isAppropriateForLunch(it) }
            "Cena" -> candidatos.filter { isAppropriateForDinner(it) }
            "Snack" -> candidatos.filter { isAppropriateForSnack(it) }
            else -> candidatos
        }
        
        // Si no hay suficientes candidatos para el momento, usar todos los candidatos disponibles
        candidatos = if (candidatosPorMomento.size >= cantidadNecesaria) {
            println("✅ Usando ${candidatosPorMomento.size} candidatos específicos para $momento")
            candidatosPorMomento
        } else {
            println("⚠️ Solo ${candidatosPorMomento.size} candidatos para $momento (necesita $cantidadNecesaria), usando todos los disponibles (${candidatos.size})")
            candidatos // Usar todos los candidatos si no hay suficientes para el momento específico
        }
        
        if (candidatos.isEmpty()) {
            println("❌ No hay candidatos disponibles para $momento después de todos los filtros")
            return emptyList()
        }
        
        val objetivoLower = userProfile.objetivosSalud.lowercase()
        val dietaLower = userProfile.restriccionesDieta.lowercase()
        val actividadLower = userProfile.nivelActividad.lowercase()
        
        // REGLA ESTRICTA: Excluir frutas altas en azúcar si el objetivo es pérdida de peso o dieta es baja en carbohidratos
        val esPerdidaPeso = objetivoLower.contains("perder peso") || 
                           objetivoLower.contains("bajar peso") || 
                           objetivoLower.contains("pérdida de peso")
        val esBajaCarbohidratos = dietaLower.contains("baja en carbohidratos") || 
                                 dietaLower.contains("baja carbohidratos") || 
                                 dietaLower.contains("low carb") ||
                                 dietaLower.contains("keto") ||
                                 dietaLower.contains("cetogénica")
        
        if (esPerdidaPeso || esBajaCarbohidratos) {
            candidatos = candidatos.filter {
                val nombreLower = it.nombreAlimento.lowercase()
                !nombreLower.contains("banana") &&
                !nombreLower.contains("mango") &&
                !nombreLower.contains("uva") &&
                !nombreLower.contains("piña") &&
                !nombreLower.contains("pina") &&
                !nombreLower.contains("plátano") &&
                !nombreLower.contains("platano")
            }
        }
        
        // Aplicar filtros según objetivo (ser menos restrictivo si no hay suficientes candidatos)
        val candidatosFiltradosPorObjetivo = when {
            // Pérdida de peso: controlar calorías, evitar alimentos altos en grasa/carbohidratos simples
            objetivoLower.contains("perder peso") || 
            objetivoLower.contains("bajar peso") || 
            objetivoLower.contains("pérdida de peso") -> {
                val filtrados = candidatos.filter {
                    it.calorias < 400f && // Controlar calorías
                    !(it.grasas > 20f && it.carbohidratos > 30f) && // Evitar altos en grasa y carbohidratos
                    (it.fibra > 2f || it.proteinas > 10f) // Priorizar fibra y proteína
                }
                // Si no hay suficientes, ser menos restrictivo
                if (filtrados.size >= cantidadNecesaria) filtrados else {
                    println("⚠️ Solo ${filtrados.size} candidatos después de filtro de pérdida de peso, relajando criterios")
                    candidatos.filter {
                        it.calorias < 500f && // Relajar límite de calorías
                        (it.fibra > 1f || it.proteinas > 5f) // Relajar requisitos de fibra/proteína
                    }
                }
            }
            // Ganancia de masa muscular: priorizar proteína y carbohidratos complejos
            objetivoLower.contains("ganar músculo") || 
            objetivoLower.contains("ganar musculo") || 
            objetivoLower.contains("masa muscular") -> {
                val filtrados = candidatos.filter {
                    (it.proteinas > 15f || it.carbohidratos > 20f) && // Proteína o carbohidratos
                    it.calorias > 100f // Evitar déficit calórico
                }
                // Si no hay suficientes, ser menos restrictivo
                if (filtrados.size >= cantidadNecesaria) filtrados else {
                    println("⚠️ Solo ${filtrados.size} candidatos después de filtro de ganancia muscular, relajando criterios")
                    candidatos.filter {
                        (it.proteinas > 10f || it.carbohidratos > 15f) && // Relajar requisitos
                        it.calorias > 50f // Relajar límite de calorías
                    }
                }
            }
            // Mantener peso: equilibrio
            objetivoLower.contains("mantener peso") || 
            objetivoLower.contains("mantener") -> {
                candidatos // Sin filtros adicionales, mantener equilibrio
            }
            // Hábitos saludables: balanceado sin extremos
            objetivoLower.contains("hábitos") || 
            objetivoLower.contains("habitos") || 
            objetivoLower.contains("saludable") -> {
                val filtrados = candidatos.filter {
                    it.calorias < 500f && // Sin extremos calóricos
                    (it.proteinas > 5f || it.carbohidratos > 10f || it.fibra > 2f) // Balanceado
                }
                // Si no hay suficientes, ser menos restrictivo
                if (filtrados.size >= cantidadNecesaria) filtrados else candidatos
            }
            else -> candidatos
        }
        
        candidatos = candidatosFiltradosPorObjetivo
        
        // Aplicar filtros según tipo de dieta
        candidatos = when {
            // Alta en proteínas: priorizar alimentos proteicos
            dietaLower.contains("alta en proteínas") || 
            dietaLower.contains("alta proteinas") -> {
                candidatos.sortedByDescending { it.proteinas }
            }
            // Keto: priorizar grasas saludables, minimizar carbohidratos
            dietaLower.contains("keto") || dietaLower.contains("cetogénica") -> {
                candidatos.filter { it.carbohidratos < 10f }
                    .sortedByDescending { it.grasas }
            }
            // Baja en grasas: priorizar alimentos bajos en grasa
            dietaLower.contains("baja en grasas") || dietaLower.contains("baja grasas") -> {
                candidatos.filter { it.grasas < 15f }
                    .sortedBy { it.grasas }
            }
            else -> candidatos
        }
        
        // Seleccionar alimentos balanceados con coherencia nutricional
        val seleccionados = mutableListOf<Alimento>()
        
        // Mezclar candidatos para asegurar variedad en cada generación
        val candidatosMezclados = candidatos.shuffled()
        
        println("📊 Seleccionando alimentos para $momento: ${candidatosMezclados.size} candidatos disponibles")
        
        // Para comidas principales (Desayuno, Almuerzo, Cena): asegurar balance nutricional
        if (momento in listOf("Desayuno", "Almuerzo", "Cena")) {
            // 1. Priorizar una proteína de calidad (especialmente para alta en proteínas)
            val proteinas = if (dietaLower.contains("alta en proteínas") || dietaLower.contains("alta proteinas")) {
                candidatosMezclados.filter { it.proteinas > 15f }
                    .sortedByDescending { it.proteinas }
                    .take(10)
                    .shuffled()
            } else {
                candidatosMezclados.filter { 
                    it.proteinas > 10f && 
                    (it.proteinas > it.grasas || it.calorias < 300f)
                }.sortedByDescending { it.proteinas }.take(5).shuffled()
            }
            
            if (proteinas.isNotEmpty() && seleccionados.size < cantidadNecesaria) {
                seleccionados.add(proteinas.first())
            }
            
            // 2. Agregar un carbohidrato complejo (excepto para dietas bajas en carbohidratos/keto)
            if (!dietaLower.contains("baja en carbohidratos") && 
                !dietaLower.contains("baja carbohidratos") && 
                !dietaLower.contains("keto") && 
                !dietaLower.contains("low carb")) {
                val carbohidratos = candidatosMezclados.filter { 
                    it.carbohidratos > 15f && 
                    it.proteinas < 25f &&
                    it !in seleccionados &&
                    (it.fibra > 2f || it.nombreAlimento.lowercase().contains("integral"))
                }.sortedByDescending { it.carbohidratos }.take(5).shuffled()
                
                if (carbohidratos.isNotEmpty() && seleccionados.size < cantidadNecesaria) {
                    seleccionados.add(carbohidratos.first())
                }
            }
            
            // 3. Agregar vegetal/fruta para completar (fibra, vitaminas)
            val vegetalesFrutas = candidatosMezclados.filter { 
                (it.fibra > 3f || it.nombreAlimento.lowercase().contains("vegetal") ||
                 it.nombreAlimento.lowercase().contains("fruta") ||
                 it.nombreAlimento.lowercase().contains("ensalada") ||
                 it.nombreAlimento.lowercase().contains("brócoli") ||
                 it.nombreAlimento.lowercase().contains("repollo")) &&
                it !in seleccionados
            }.shuffled()
            
            if (vegetalesFrutas.isNotEmpty() && seleccionados.size < cantidadNecesaria) {
                seleccionados.add(vegetalesFrutas.first())
            }
        } else if (momento == "Snack") {
            // Para snacks: alimentos ligeros y nutritivos
            val snacksApropiados = candidatosMezclados.filter {
                it.calorias < 250f && 
                (it.fibra > 2f || it.proteinas > 5f)
            }.shuffled()
            
            seleccionados.addAll(snacksApropiados.take(cantidadNecesaria))
        }
        
        // Completar con alimentos variados si aún faltan
        val restantes = candidatosMezclados.filter { it !in seleccionados }.shuffled()
        for (alimento in restantes) {
            if (seleccionados.size >= cantidadNecesaria) break
            seleccionados.add(alimento)
        }
        
        val resultado = seleccionados.take(cantidadNecesaria)
        println("📊 Seleccionados ${resultado.size} alimentos para $momento (necesarios: $cantidadNecesaria)")
        if (resultado.size < cantidadNecesaria) {
            println("⚠️ ADVERTENCIA: Solo se seleccionaron ${resultado.size} de $cantidadNecesaria alimentos necesarios para $momento")
        }
        
        return resultado
    }
    
    // Verificar si un alimento es apropiado para desayuno
    private fun isAppropriateForBreakfast(alimento: Alimento): Boolean {
        val nombreLower = alimento.nombreAlimento.lowercase()
        val categoriaLower = alimento.categoria.lowercase()
        
        // Alimentos típicos de desayuno
        return nombreLower.contains("huevo") ||
               nombreLower.contains("avena") ||
               nombreLower.contains("cereal") ||
               nombreLower.contains("pan") ||
               nombreLower.contains("yogur") ||
               nombreLower.contains("yogurt") ||
               nombreLower.contains("leche") ||
               nombreLower.contains("queso") ||
               nombreLower.contains("fruta") ||
               nombreLower.contains("manzana") ||
               nombreLower.contains("banana") ||
               nombreLower.contains("plátano") ||
               nombreLower.contains("platano") ||
               nombreLower.contains("naranja") ||
               nombreLower.contains("fresa") ||
               nombreLower.contains("fresas") ||
               categoriaLower.contains("lácteo") ||
               categoriaLower.contains("lacteo") ||
               categoriaLower.contains("cereal") ||
               categoriaLower.contains("fruta") ||
               categoriaLower.contains("huevo")
    }
    
    // Verificar si un alimento es apropiado para almuerzo
    private fun isAppropriateForLunch(alimento: Alimento): Boolean {
        val nombreLower = alimento.nombreAlimento.lowercase()
        return nombreLower.contains("pollo") ||
               nombreLower.contains("carne") ||
               nombreLower.contains("pescado") ||
               nombreLower.contains("arroz") ||
               nombreLower.contains("pasta") ||
               nombreLower.contains("papa") ||
               nombreLower.contains("vegetal") ||
               nombreLower.contains("brócoli") ||
               nombreLower.contains("ensalada")
    }
    
    // Verificar si un alimento es apropiado para cena
    private fun isAppropriateForDinner(alimento: Alimento): Boolean {
        val nombreLower = alimento.nombreAlimento.lowercase()
        return nombreLower.contains("pollo") ||
               nombreLower.contains("pescado") ||
               nombreLower.contains("huevo") ||
               nombreLower.contains("vegetal") ||
               nombreLower.contains("ensalada") ||
               (nombreLower.contains("arroz") && alimento.calorias < 200f)
    }
    
    // Verificar si un alimento es apropiado para snack
    private fun isAppropriateForSnack(alimento: Alimento): Boolean {
        val nombreLower = alimento.nombreAlimento.lowercase()
        return nombreLower.contains("fruta") ||
               nombreLower.contains("manzana") ||
               nombreLower.contains("banana") ||
               nombreLower.contains("nuez") ||
               nombreLower.contains("almendra") ||
               nombreLower.contains("yogur") ||
               nombreLower.contains("queso")
    }
    
    // Seleccionar unidad apropiada (versión genérica)
    private fun selectAppropriateUnit(
        unidadesDisponibles: List<String>,
        unidadBase: String
    ): String {
        val unidadesPriorizadas = unidadesDisponibles.sortedBy { unidad ->
            val unidadLower = unidad.lowercase()
            when {
                unidadLower.contains("porción") || unidadLower.contains("porcion") -> 1
                unidadLower.contains("unidad") -> 2
                unidadLower.contains("gramo") || unidadLower.contains("g ") -> 3
                unidadLower.contains("mililitro") || unidadLower.contains("ml") -> 4
                else -> 5
            }
        }
        return unidadesPriorizadas.firstOrNull() ?: unidadBase
    }
    
    // Seleccionar unidad apropiada según el tipo de alimento (coherencia mejorada)
    private fun selectAppropriateUnitForFood(
        alimento: Alimento,
        unidadesDisponibles: List<String>
    ): String {
        val nombreLower = alimento.nombreAlimento.lowercase()
        val categoriaLower = alimento.categoria.lowercase()
        
        // Priorizar unidades según el tipo de alimento
        val unidadesPriorizadas = unidadesDisponibles.sortedBy { unidad ->
            val unidadLower = unidad.lowercase()
            
            // Líquidos (leche, jugos, agua)
            if (nombreLower.contains("leche") || nombreLower.contains("jugo") || 
                nombreLower.contains("agua") || nombreLower.contains("yogur") ||
                categoriaLower.contains("bebida") || categoriaLower.contains("líquido")) {
                when {
                    unidadLower.contains("vaso") -> 1
                    unidadLower.contains("ml") || unidadLower.contains("mililitro") -> 2
                    unidadLower.contains("taza") -> 3
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 4
                    unidadLower.contains("unidad") -> 5
                    else -> 6
                }
            }
            // Verduras y hortalizas
            else if (nombreLower.contains("brócoli") || nombreLower.contains("espinaca") ||
                     nombreLower.contains("lechuga") || nombreLower.contains("repollo") ||
                     categoriaLower.contains("verdura") || categoriaLower.contains("hortaliza")) {
                when {
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 1
                    unidadLower.contains("unidad") -> 2
                    unidadLower.contains("taza") -> 3
                    unidadLower.contains("gramo") || unidadLower.contains("g ") -> 4
                    else -> 5
                }
            }
            // Carnes y pescados
            else if (nombreLower.contains("pollo") || nombreLower.contains("res") ||
                     nombreLower.contains("salmón") || nombreLower.contains("pescado") ||
                     nombreLower.contains("carne") || categoriaLower.contains("proteína") ||
                     categoriaLower.contains("carne") || categoriaLower.contains("pescado")) {
                when {
                    unidadLower.contains("filete") -> 1
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 2
                    unidadLower.contains("unidad") -> 3
                    unidadLower.contains("gramo") || unidadLower.contains("g ") -> 4
                    else -> 5
                }
            }
            // Granos y cereales
            else if (nombreLower.contains("arroz") || nombreLower.contains("quinoa") ||
                     nombreLower.contains("avena") || nombreLower.contains("pasta") ||
                     categoriaLower.contains("cereal") || categoriaLower.contains("grano")) {
                when {
                    unidadLower.contains("taza") -> 1
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 2
                    unidadLower.contains("gramo") || unidadLower.contains("g ") -> 3
                    else -> 4
                }
            }
            // Frutas
            else if (categoriaLower.contains("fruta") || nombreLower.contains("manzana") ||
                     nombreLower.contains("banana") || nombreLower.contains("naranja")) {
                when {
                    unidadLower.contains("pieza") -> 1
                    unidadLower.contains("unidad") -> 2
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 3
                    unidadLower.contains("gramo") || unidadLower.contains("g ") -> 4
                    else -> 5
                }
            }
            // Frutos secos y semillas
            else if (nombreLower.contains("almendra") || nombreLower.contains("nuez") ||
                     nombreLower.contains("semilla") || categoriaLower.contains("fruto seco")) {
                when {
                    unidadLower.contains("puñado") -> 1
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 2
                    unidadLower.contains("unidad") -> 3
                    unidadLower.contains("gramo") || unidadLower.contains("g ") -> 4
                    else -> 5
                }
            }
            // Huevos
            else if (nombreLower.contains("huevo") || nombreLower.contains("clara") ||
                     nombreLower.contains("yema") || categoriaLower.contains("huevo")) {
                when {
                    unidadLower.contains("unidad") -> 1
                    unidadLower.contains("pieza") -> 2
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 3
                    unidadLower.contains("gramo") || unidadLower.contains("g ") -> 4
                    else -> 5
                }
            }
            // Lácteos sólidos
            else if (nombreLower.contains("queso") || nombreLower.contains("yogur") ||
                     (categoriaLower.contains("lácteo") && !nombreLower.contains("leche"))) {
                when {
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 1
                    unidadLower.contains("unidad") -> 2
                    unidadLower.contains("taza") -> 3
                    unidadLower.contains("gramo") || unidadLower.contains("g ") -> 4
                    else -> 5
                }
            }
            // Por defecto: priorizar unidades naturales
            else {
                when {
                    unidadLower.contains("porción") || unidadLower.contains("porcion") -> 1
                    unidadLower.contains("unidad") -> 2
                    unidadLower.contains("pieza") -> 3
                    unidadLower.contains("taza") -> 4
                    unidadLower.contains("vaso") -> 5
                    unidadLower.contains("gramo") || unidadLower.contains("g ") -> 6
                    else -> 7
                }
            }
        }
        
        return unidadesPriorizadas.firstOrNull() ?: alimento.unidadBase
    }
    
    // Calcular cantidad de alimento en gramos considerando objetivo, dieta y nivel de actividad
    private fun calculateFoodQuantity(
        alimento: Alimento,
        userProfile: Usuario,
        momento: String,
        unidad: String
    ): String {
        val baseQuantity = alimento.cantidadBase
        val actividadLower = userProfile.nivelActividad.lowercase()
        val objetivoLower = userProfile.objetivosSalud.lowercase()
        
        // Multiplicador según momento del día
        val momentoMultiplier = when (momento) {
            "Desayuno" -> 0.8f
            "Almuerzo" -> 1.2f
            "Cena" -> 0.9f
            "Snack" -> 0.5f
            else -> 1.0f
        }
        
        // Multiplicador según peso (normalizar a 70kg)
        val pesoMultiplier = (userProfile.peso / 70.0f).coerceIn(0.7f, 1.3f)
        
        // Multiplicador según objetivo
        val objetivoMultiplier = when {
            objetivoLower.contains("bajar peso") || objetivoLower.contains("perder peso") -> 0.85f
            objetivoLower.contains("ganar músculo") || objetivoLower.contains("ganar musculo") -> 1.15f
            objetivoLower.contains("mantener peso") || objetivoLower.contains("mantener") -> 1.0f
            objetivoLower.contains("hábitos") || objetivoLower.contains("habitos") || objetivoLower.contains("saludable") -> 1.0f
            else -> 1.0f
        }
        
        // Multiplicador según nivel de actividad física
        val actividadMultiplier = when {
            actividadLower.contains("sedentario") -> 0.9f // Porciones moderadas, carbohidratos controlados
            actividadLower.contains("ligera") -> 1.0f
            actividadLower.contains("moderada") || actividadLower.contains("moderado") -> 1.1f // Más carbohidratos complejos y proteína
            actividadLower.contains("alta") -> 1.2f // Más carbohidratos complejos y proteína
            actividadLower.contains("extrema") -> 1.3f // Porciones más altas de carbohidratos y proteína
            else -> 1.0f
        }
        
        // Ajuste adicional según tipo de alimento y nivel de actividad
        val alimentoMultiplier = when {
            // Para actividad alta/extrema, aumentar más los carbohidratos y proteínas
            (actividadLower.contains("alta") || actividadLower.contains("extrema")) && 
            (alimento.carbohidratos > 20f || alimento.proteinas > 15f) -> 1.1f
            // Para sedentario, reducir carbohidratos simples
            actividadLower.contains("sedentario") && 
            alimento.carbohidratos > 30f && alimento.fibra < 3f -> 0.9f
            else -> 1.0f
        }
        
        var cantidadEnGramos = baseQuantity * momentoMultiplier * pesoMultiplier * objetivoMultiplier * actividadMultiplier * alimentoMultiplier
        cantidadEnGramos = cantidadEnGramos.coerceIn(30f, 500f)
        
        // Siempre devolver en gramos con un decimal (usar punto decimal)
        return "%.1f".format(cantidadEnGramos).replace(",", ".")
    }
    
    // Formatear rutina para mostrar al usuario
    private fun formatRoutineForDisplay(
        routineItems: List<Pair<String, Triple<String, String, String>>>
    ): String {
        val grouped = routineItems.groupBy { it.first }
        val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
        
        return momentos.joinToString("\n\n") { momento ->
            val alimentos = grouped[momento] ?: emptyList()
            val emoji = when (momento) {
                "Desayuno" -> "🌅"
                "Almuerzo" -> "🌞"
                "Cena" -> "🌙"
                "Snack" -> "🍎"
                else -> "🍽️"
            }
            
            if (alimentos.isEmpty()) {
                "$emoji <b>$momento:</b>\n  - No hay alimentos asignados"
            } else {
                "$emoji <b>$momento:</b>\n" + alimentos.joinToString("\n") { 
                    // Formato: nombre alimento - cantidad unidad (un decimal)
                    val cantidadUnidad = it.second.second
                    println("🔍 Formateando: cantidadUnidad='$cantidadUnidad'")
                    
                    // Dividir cantidad y unidad (puede ser "104.0 gramos" o "104.0 g" o "104.0 porción")
                    val partes = cantidadUnidad.trim().split(" ", limit = 2)
                    val cantidad = partes[0].replace(",", ".") // Asegurar punto decimal
                    
                    // La unidad viene después del espacio, si no hay espacio, usar "gramos" como fallback
                    val unidad = if (partes.size > 1) {
                        partes[1]
                    } else {
                        // Si no hay unidad en el string, intentar extraerla del resto
                        val resto = cantidadUnidad.trim().substringAfter("$cantidad ").trim()
                        if (resto.isNotBlank()) resto else "gramos"
                    }
                    
                    println("🔍 Parseado: cantidad='$cantidad', unidad='$unidad'")
                    
                    // Formatear a un decimal (usar punto decimal)
                    val cantidadFloat = cantidad.toFloatOrNull() ?: 0f
                    val cantidadFormateada = "%.1f".format(cantidadFloat).replace(",", ".")
                    
                    "  - ${it.second.first} - $cantidadFormateada $unidad"
                }
            }
        }
    }
    
    /**
     * Parsea la respuesta de Gemini para extraer los alimentos de la rutina
     */
    private suspend fun parseGeminiRoutineResponse(
        geminiResponse: String,
        availableFoods: List<Alimento>
    ): RoutineGenerationResult = withContext(Dispatchers.IO) {
        val routineItems = mutableListOf<Pair<String, Triple<String, String, String>>>()
        
        try {
            println("=== PARSENDO RESPUESTA DE GEMINI ===")
            println("Respuesta completa: ${geminiResponse.take(500)}...")
            
            // Dividir por momentos del día
            val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
            val lines = geminiResponse.lines()
            
            var currentMomento: String? = null
            
            for (line in lines) {
                val trimmedLine = line.trim()
                
                // Detectar momento del día
                for (momento in momentos) {
                    if (trimmedLine.contains(momento, ignoreCase = true) && 
                        (trimmedLine.contains(":") || trimmedLine.contains("•") || trimmedLine.contains("-"))) {
                        currentMomento = momento
                        println("📋 Detectado momento: $momento")
                        break
                    }
                }
                
                // Si estamos en un momento, buscar alimentos
                if (currentMomento != null && (trimmedLine.startsWith("-") || trimmedLine.startsWith("•") || trimmedLine.startsWith("*"))) {
                    // Extraer alimento de la línea
                    // Formato esperado de Gemini: "- alimento – cantidad unidad" (con guión largo)
                    // O formato alternativo: "- cantidad unidad de alimento"
                    val alimentoLine = trimmedLine
                        .replaceFirst(Regex("^[-•*]\\s*"), "") // Remover el marcador inicial
                        .trim()
                    
                    // Patrón 1: "alimento – cantidad unidad" (formato actual de Gemini)
                    val pattern1 = Regex("""(.+?)\s*[–-]\s*(\d+(?:\.\d+)?)\s+(\w+(?:\s+\w+)?)""", RegexOption.IGNORE_CASE)
                    // Patrón 2: "cantidad unidad de alimento" (formato alternativo)
                    val pattern2 = Regex("""(\d+(?:\.\d+)?)\s+(\w+(?:\s+\w+)?)\s+de\s+(.+)""", RegexOption.IGNORE_CASE)
                    // Patrón 3: "cantidad unidad alimento" (sin "de")
                    val pattern3 = Regex("""(\d+(?:\.\d+)?)\s+(\w+(?:\s+\w+)?)\s+(.+)""", RegexOption.IGNORE_CASE)
                    
                    val match1 = pattern1.find(alimentoLine)
                    val match2 = if (match1 == null) pattern2.find(alimentoLine) else null
                    val match3 = if (match1 == null && match2 == null) pattern3.find(alimentoLine) else null
                    val match = match1 ?: match2 ?: match3
                    
                    if (match != null) {
                        val cantidad: String
                        val unidad: String
                        val nombreAlimento: String
                        
                        if (match1 != null) {
                            // Formato: "alimento – cantidad unidad"
                            nombreAlimento = match.groupValues[1].trim()
                            cantidad = match.groupValues[2]
                            unidad = match.groupValues[3].trim()
                        } else {
                            // Formato: "cantidad unidad de alimento" o "cantidad unidad alimento"
                            cantidad = match.groupValues[1]
                            unidad = match.groupValues[2].trim()
                            nombreAlimento = match.groupValues[3].trim()
                        }
                        
                        println("   🍎 Alimento encontrado: $nombreAlimento, Cantidad: $cantidad, Unidad: $unidad")
                        
                        // Buscar el alimento en la lista disponible (búsqueda flexible)
                        val alimentoEncontrado = availableFoods.find { alimento ->
                            alimento.nombreAlimento.equals(nombreAlimento, ignoreCase = true) ||
                            nombreAlimento.contains(alimento.nombreAlimento, ignoreCase = true) ||
                            alimento.nombreAlimento.contains(nombreAlimento, ignoreCase = true)
                        }
                        
                        if (alimentoEncontrado != null) {
                            // Obtener unidades válidas del alimento desde la base de datos
                            val unidadesDisponibles = try {
                                val unidades = repository.obtenerUnidadesPorId(alimentoEncontrado.idAlimento)
                                println("   📋 Unidades disponibles para ${alimentoEncontrado.nombreAlimento}: $unidades")
                                unidades
                            } catch (e: Exception) {
                                println("   ⚠️ Error obteniendo unidades, usando unidad base: ${alimentoEncontrado.unidadBase}")
                                listOf(alimentoEncontrado.unidadBase)
                            }
                            
                            // Validar que la unidad sea válida, si no, usar la primera disponible
                            // IMPORTANTE: Usar la unidad EXACTA de la base de datos
                            val unidadValida = if (unidadesDisponibles.any { it.equals(unidad, ignoreCase = true) }) {
                                // Usar la unidad exacta de la BD (no la que viene de Gemini)
                                unidadesDisponibles.first { it.equals(unidad, ignoreCase = true) }
                            } else {
                                // Buscar unidad similar en la BD
                                val unidadSimilar = unidadesDisponibles.find { 
                                    it.lowercase().contains(unidad.lowercase()) || 
                                    unidad.lowercase().contains(it.lowercase())
                                }
                                unidadSimilar ?: unidadesDisponibles.firstOrNull() ?: alimentoEncontrado.unidadBase
                            }
                            
                            println("   🔍 Unidad de Gemini: '$unidad' -> Unidad válida de BD: '$unidadValida'")
                            
                            val cantidadUnidadString = "$cantidad $unidadValida"
                            routineItems.add(Pair(
                                currentMomento!!,
                                Triple(alimentoEncontrado.nombreAlimento, cantidadUnidadString, alimentoEncontrado.idAlimento.toString())
                            ))
                            println("   ✅ Agregado: ${alimentoEncontrado.nombreAlimento} - $cantidadUnidadString")
                        } else {
                            println("   ⚠️ Alimento no encontrado en BD: $nombreAlimento")
                        }
                    }
                }
            }
            
            println("✅ Parseados ${routineItems.size} alimentos de la respuesta de Gemini")
            
        } catch (e: Exception) {
            println("❌ Error parseando respuesta de Gemini: ${e.message}")
            e.printStackTrace()
        }
        
        return@withContext RoutineGenerationResult(
            success = routineItems.isNotEmpty(),
            routineItems = routineItems,
            formattedRoutine = if (routineItems.isNotEmpty()) formatRoutineForDisplay(routineItems) else geminiResponse,
            errorMessage = if (routineItems.isEmpty()) "No se pudieron parsear alimentos de la respuesta de Gemini" else null
        )
    }
    
    /**
     * Valida si una cantidad es válida
     */
    private fun isValidQuantity(quantity: String): Boolean {
        val cantidadFloat = quantity.replace(",", ".").trim().toFloatOrNull()
        return cantidadFloat != null && cantidadFloat > 0 && cantidadFloat <= 10000
    }
    
    /**
     * Valida si una unidad es válida para un alimento
     */
    private fun isValidUnitForFood(unit: String, validUnits: List<String>): Boolean {
        val unitLower = unit.lowercase().trim()
        return validUnits.any { it.lowercase() == unitLower }
    }
    
}


package com.example.frontendproyectoapp.service

import com.example.frontendproyectoapp.config.GeminiConfig
import com.example.frontendproyectoapp.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class GeminiNutriAIService {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val systemPrompt = """
        🧠 Eres NutriAI, un asistente virtual inteligente de nutrición diseñado para ofrecer orientación alimentaria personalizada y ayudar al usuario a gestionar su rutina nutricional diaria.

        🎯 OBJETIVO GENERAL:
        Tu misión es ayudar al usuario a mejorar su alimentación y hábitos saludables mediante respuestas claras, personalizadas y útiles. Debes manejar toda la lógica conversacional internamente, sin depender de instrucciones externas.

        🎭 TONO Y PERSONALIDAD:
        - Amigable, respetuoso, empático y motivacional
        - Transmite confianza y acompañamiento en cada interacción
        - Usa emojis apropiados para hacer la conversación más amigable
        - Mantén un tono positivo y de apoyo constante

        🧩 FUNCIONES PRINCIPALES:

        1. 📚 RESPONDER PREGUNTAS NUTRICIONALES:
        - Explica conceptos de forma sencilla y práctica
        - Responde dudas sobre calorías, macronutrientes, vitaminas, minerales, dietas y hábitos alimenticios
        - Acepta preguntas con errores ortográficos, lenguaje informal o incompleto
        - Proporciona ejemplos prácticos cuando sea útil

        2. 🍎 SUGERIR ALIMENTOS ADECUADOS:
        - Analiza la información del perfil (edad, género, objetivos, alergias, gustos o restricciones)
        - Recomienda alimentos o combinaciones saludables adaptadas al usuario
        - Ofrece alternativas según estilo de vida (vegano, sin gluten, deportivo, etc.)

        3. 📅 MOSTRAR RUTINA NUTRICIONAL:
        - Cuando el usuario pida ver su rutina de un día, muestra las comidas (desayuno, almuerzo, cena, snacks) con sus alimentos y cantidades estimadas
        - Si el usuario no ha registrado alimentos, indica claramente que no hay datos y explica cómo registrar alimentos
        - Si el día no existe, responde amablemente y ofrece opciones válidas
        - Estructura la información de manera clara y organizada
        - Para rutinas de fechas específicas, acepta formatos como: "15 de enero", "20/01/2024", "lunes pasado", "2024-01-15"
        - Si no se especifica fecha, muestra la rutina del día actual
        - Proporciona ejemplos de cómo especificar fechas cuando el usuario no lo haga correctamente
        - SIEMPRE usa los datos reales del usuario, nunca inventes alimentos que no haya registrado

        4. ➕ AGREGAR ALIMENTOS A LA RUTINA:
        - Permite al usuario añadir nuevos alimentos a una comida específica (ej: "agregar manzana al desayuno")
        - Actualiza internamente la rutina del día actual y confirma el cambio
        - Proporciona confirmación clara de lo que se agregó

        5. ➖ ELIMINAR ALIMENTOS DE LA RUTINA:
        - Permite eliminar alimentos de la rutina del día según lo que el usuario solicite
        - Informa al usuario qué alimento se eliminó y en qué comida
        - Confirma la eliminación de manera clara

        6. 🔄 CAMBIAR ALIMENTOS EQUIVALENTES:
        - Permite intercambiar alimentos de manera equivalente (ej: "cambiar arroz por quinua")
        - Aclara que las modificaciones solo aplican para el día actual
        - No se deben modificar rutinas de otros días

        7. 📝 REGISTRAR MODIFICACIONES:
        - Lleva un registro interno de todas las acciones realizadas (agregar, eliminar o cambiar alimentos)
        - Si el usuario lo solicita, muestra un resumen de los cambios realizados
        - Mantén un historial claro de las modificaciones

        🔄 MANEJO DE CONVERSACIÓN:
        - Analiza el contexto completo de cada conversación
        - Mantén coherencia en respuestas largas y complejas
        - Recuerda información previa de la conversación
        - Adapta tu tono según el estado emocional del usuario
        - Haz preguntas de seguimiento relevantes
        - Ofrece información adicional cuando sea útil
        - Mantén conversaciones fluidas y naturales

        🚨 MANEJO DE ERRORES:
        - Si no entiendes algo, pide aclaración amablemente
        - Ofrece alternativas cuando no puedas cumplir una solicitud
        - Mantén siempre un tono positivo y de ayuda
        - Redirige suavemente temas no relacionados con nutrición

        📋 FORMATO DE RESPUESTAS:
        - Estructura la información de manera clara
        - Usa listas y viñetas cuando sea apropiado
        - Proporciona ejemplos prácticos
        - Mantén un tono motivacional y de apoyo

        IMPORTANTE:
        - Maneja toda la lógica conversacional internamente
        - No dependas de instrucciones externas para mantener la conversación
        - Mantén el enfoque en la nutrición y salud
        - Siempre transmite confianza y acompañamiento
        - NUNCA inventes alimentos que el usuario no haya registrado
        - SIEMPRE usa únicamente los datos reales proporcionados en el contexto
        - Si no hay datos reales, indica claramente que no hay información registrada
    """.trimIndent()
    
    suspend fun generateResponse(
        userMessage: String,
        userProfile: Usuario? = null,
        currentRoutine: List<RegistroAlimentoSalida>? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            println("=== INICIANDO GENERACIÓN CON GEMINI ===")
            println("Mensaje del usuario: $userMessage")
            println("API Key: ${GeminiConfig.API_KEY.take(10)}...")
            println("Modelo: ${GeminiConfig.MODEL_NAME}")
            println("Base URL: ${GeminiConfig.BASE_URL}")
            println("¿API Key válida? ${GeminiConfig.isValid()}")
            
            val contextPrompt = buildContextPrompt(userProfile, currentRoutine)
            println("=== CONTEXTO DEL USUARIO ===")
            println("Perfil del usuario: ${userProfile?.let { "Nombre: ${it.nombre}, Peso: ${it.peso}kg, Altura: ${it.altura}cm, Objetivo: ${it.pesoObjetivo}kg" } ?: "No disponible"}")
            println("Rutina actual: ${currentRoutine?.size ?: 0} elementos")
            println("Contexto generado: ${contextPrompt.take(200)}...")
            
            // Prompt personalizado con datos del usuario
            println("=== DATOS DEL USUARIO RECIBIDOS ===")
            println("userProfile recibido: $userProfile")
            println("userProfile.nombre: ${userProfile?.nombre}")
            println("userProfile.peso: ${userProfile?.peso}")
            println("userProfile.altura: ${userProfile?.altura}")
            
            val currentUserProfile = userProfile ?: Usuario(
                idUsuario = 1L,
                nombre = "Usuario",
                correo = "usuario@ejemplo.com",
                contrasena = "",
                peso = 70.0f,
                altura = 170f,
                fechaNacimiento = "1990-01-01",
                sexo = "No especificado",
                pesoObjetivo = 70.0f,
                restriccionesDieta = "Ninguna",
                nivelActividad = "Moderado",
                objetivosSalud = "Mantener peso"
            )
            
            println("currentUserProfile.nombre: ${currentUserProfile.nombre}")
            println("currentUserProfile.peso: ${currentUserProfile.peso}")
            val edad = currentUserProfile.fechaNacimiento?.let { 
                val birthYear = it.split("-")[0].toIntOrNull() ?: 1990
                2024 - birthYear
            } ?: 30
            
            // Verificar si es el saludo inicial (solo para el primer mensaje)
            val isInitialGreeting = (userMessage.lowercase().contains("responder preguntas de nutrición") || 
                                  userMessage.lowercase().contains("responder preguntas sobre nutrición")) &&
                                  userMessage.lowercase().contains("responder preguntas")
            
            val personalizedPrompt = if (isInitialGreeting) {
                """
                Eres un asistente virtual en nutrición y bienestar. 
                Tu tarea es responder preguntas y dar orientación personalizada sobre alimentación, hábitos saludables y nutrición, 
                basándote en el perfil del usuario.

                Cuando inicies la conversación o el usuario seleccione la opción "Responder preguntas sobre nutrición", 
                salúdalo de forma amigable y menciona su nombre. 
                Ejemplo: "¡Hola, Ana! 😊 ¿Cómo te gustaría que te ayudara hoy con tus hábitos alimenticios?"

                Usa la siguiente información del perfil del usuario para adaptar tus respuestas:
                - Nombre: ${currentUserProfile.nombre ?: "Usuario"}
                - Edad: $edad años
                - Sexo: ${currentUserProfile.sexo ?: "No especificado"}
                - Peso: ${currentUserProfile.peso ?: 70} kg
                - Altura: ${currentUserProfile.altura ?: 170} cm
                - Objetivo: ${currentUserProfile.objetivosSalud ?: "Mantener peso"}
                - Restricciones o alergias: ${currentUserProfile.restriccionesDieta ?: "Ninguna"}
                - Nivel de actividad física: ${currentUserProfile.nivelActividad ?: "Moderado"}

                Reglas:
                1. Ofrece información general y educativa, nunca diagnósticos médicos.
                2. Adapta tus recomendaciones a los datos del perfil (por ejemplo, si el usuario busca bajar de peso, da consejos enfocados en déficit calórico saludable).
                3. Usa un tono muy cercano, amigable, cálido y motivador. Sé como un amigo nutricionista.
                4. Si el usuario pregunta algo fuera del tema de nutrición, redirígelo educadamente al tema principal.
                5. Mantén las respuestas breves, claras y fáciles de entender.
                6. Muestra entusiasmo y apoyo en tus respuestas.
                7. NO uses género (masculino/femenino) en tus respuestas. Usa lenguaje neutro como "estoy preparado", "puedo ayudarte", etc.

                Comienza siempre con un saludo amigable y personalizado como:
                "¡Hola, ${currentUserProfile.nombre ?: "Usuario"}! 😊 Es un placer conocerte. Soy NutriAI, tu asistente personal de nutrición y estoy aquí para ayudarte a alcanzar tus objetivos de salud.
                
                Veo que tu objetivo es ${currentUserProfile.objetivosSalud ?: "mantener un estilo de vida saludable"}.
                
                ¿En qué puedo ayudarte hoy?"
                
                Responde en español de manera clara, concisa y personalizada.
                """.trimIndent()
            } else {
                """
                Eres un asistente virtual en nutrición y bienestar. 
                Tu tarea es responder preguntas y dar orientación personalizada sobre alimentación, hábitos saludables y nutrición, 
                basándote en el perfil del usuario.

                Usa la siguiente información del perfil del usuario para adaptar tus respuestas:
                - Nombre: ${currentUserProfile.nombre ?: "Usuario"}
                - Edad: $edad años
                - Sexo: ${currentUserProfile.sexo ?: "No especificado"}
                - Peso: ${currentUserProfile.peso ?: 70} kg
                - Altura: ${currentUserProfile.altura ?: 170} cm
                - Objetivo: ${currentUserProfile.objetivosSalud ?: "Mantener peso"}
                - Restricciones o alergias: ${currentUserProfile.restriccionesDieta ?: "Ninguna"}
                - Nivel de actividad física: ${currentUserProfile.nivelActividad ?: "Moderado"}

                Reglas:
                1. Ofrece información general y educativa, nunca diagnósticos médicos.
                2. Adapta tus recomendaciones a los datos del perfil (por ejemplo, si el usuario busca bajar de peso, da consejos enfocados en déficit calórico saludable).
                3. Usa un tono cercano, amable y motivador.
                4. Si el usuario pregunta algo fuera del tema de nutrición, redirígelo educadamente al tema principal.
                5. Mantén las respuestas breves, claras y fáciles de entender.
                6. NO repitas saludos como "Hola" o "¡Hola!" en respuestas posteriores - solo responde directamente a la pregunta.
                7. Recuerda que ya te presentaste como NutriAI, así que no necesitas volver a saludar.

                Usuario pregunta: $userMessage
                
                Responde en español de manera clara, concisa y personalizada, sin repetir saludos.
                """.trimIndent()
            }
            
            val requestBody = JSONObject().apply {
                // Formato correcto para contents
                val contentsArray = JSONArray()
                val contentObject = JSONObject()
                val partsArray = JSONArray()
                val partObject = JSONObject()
                partObject.put("text", personalizedPrompt)
                partsArray.put(partObject)
                contentObject.put("parts", partsArray)
                contentsArray.put(contentObject)
                put("contents", contentsArray)
                
                // Formato correcto para generationConfig
            val generationConfig = JSONObject()
            generationConfig.put("temperature", 0.7)
            generationConfig.put("maxOutputTokens", 2000) // Aumentar límite de tokens
                put("generationConfig", generationConfig)
            }.toString()
            
            println("Request body: $requestBody")
            
            val request = Request.Builder()
                .url("${GeminiConfig.BASE_URL}/models/${GeminiConfig.MODEL_NAME}:generateContent?key=${GeminiConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "NutriAI-Android/1.0")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            println("=== ENVIANDO REQUEST A GEMINI ===")
            println("Request URL: ${request.url}")
            println("Headers: ${request.headers}")
            println("Request body size: ${requestBody.length} bytes")
            
            val response = client.newCall(request).execute()
            println("=== RESPUESTA DE GEMINI ===")
            println("Response code: ${response.code}")
            println("Response message: ${response.message}")
            println("Response headers: ${response.headers}")
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                println("✅ Respuesta de Gemini recibida: ${responseBody?.take(200)}...")
                
                if (responseBody != null && responseBody.isNotEmpty()) {
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        
                        // Verificar si hay error en la respuesta
                        if (jsonResponse.has("error")) {
                            val error = jsonResponse.getJSONObject("error")
                            val errorMessage = error.getString("message")
                            println("❌ Error en respuesta de Gemini: $errorMessage")
                            throw Exception("Error de Gemini API: $errorMessage")
                        }
                        
                        val candidates = jsonResponse.getJSONArray("candidates")
                        if (candidates.length() == 0) {
                            throw Exception("No hay candidatos en la respuesta de Gemini")
                        }
                        
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.getJSONObject("content")
                        
                        // Verificar si hay finishReason que indique problema
                        if (firstCandidate.has("finishReason")) {
                            val finishReason = firstCandidate.getString("finishReason")
                            if (finishReason == "MAX_TOKENS") {
                                println("⚠️ Respuesta cortada por límite de tokens")
                            }
                        }
                        
                        // Verificar si hay parts en el contenido
                        if (!content.has("parts")) {
                            throw Exception("No hay 'parts' en la respuesta de Gemini")
                        }
                        
                        val parts = content.getJSONArray("parts")
                        if (parts.length() == 0) {
                            throw Exception("Array 'parts' está vacío")
                        }
                        
                        val firstPart = parts.getJSONObject(0)
                        if (!firstPart.has("text")) {
                            throw Exception("No hay 'text' en la primera parte")
                        }
                        
                        val text = firstPart.getString("text")
                        
                        println("✅ Contenido extraído: ${text.take(100)}...")
                        text
                    } catch (e: Exception) {
                        println("❌ Error parseando JSON: ${e.message}")
                        println("Response body: $responseBody")
                        throw Exception("Error parseando respuesta de Gemini: ${e.message}")
                    }
                } else {
                    println("❌ Response body vacío")
                    throw Exception("Respuesta vacía de Gemini")
                }
            } else {
                val errorBody = response.body?.string()
                println("❌ Error en Gemini: ${response.code} - ${response.message}")
                println("Error body: $errorBody")
                
                // Intentar parsear el error para más detalles
                try {
                    if (errorBody != null && errorBody.isNotEmpty()) {
                        val errorJson = JSONObject(errorBody)
                        if (errorJson.has("error")) {
                            val error = errorJson.getJSONObject("error")
                            val errorMessage = error.optString("message", "Error desconocido")
                            val errorCode = error.optInt("code", response.code)
                            println("❌ Error detallado: $errorMessage (Código: $errorCode)")
                            throw Exception("Gemini API Error: $errorMessage")
                        }
                    }
                } catch (e: Exception) {
                    println("No se pudo parsear el error: ${e.message}")
                }
                
                throw Exception("Error en Gemini API: ${response.code} - ${response.message}")
            }
            
        } catch (e: Exception) {
            println("=== ERROR EN GEMINI API ===")
            println("Tipo de error: ${e.javaClass.simpleName}")
            println("Mensaje: ${e.message}")
            e.printStackTrace()
            
            // Diagnóstico específico del error
            val errorMessage = when {
                e.message?.contains("401") == true -> "API Key inválida o expirada"
                e.message?.contains("403") == true -> "Acceso denegado - verifica tu API key"
                e.message?.contains("429") == true -> "Límite de solicitudes excedido"
                e.message?.contains("500") == true -> "Error interno del servidor de Gemini"
                e.message?.contains("timeout") == true -> "Tiempo de espera agotado"
                e.message?.contains("network") == true -> "Error de conexión a internet"
                else -> "Error desconocido: ${e.message}"
            }
            
            throw Exception("Gemini API Error: $errorMessage")
        }
    }
    
    private fun buildContextPrompt(userProfile: Usuario?, currentRoutine: List<RegistroAlimentoSalida>?): String {
        val profileInfo = userProfile?.let { user ->
            val edad = calcularEdad(user.fechaNacimiento)
            val imc = if (user.altura > 0) user.peso / ((user.altura / 100.0) * (user.altura / 100.0)) else 0.0
            val diferenciaPeso = user.pesoObjetivo - user.peso
            
            """
            PERFIL COMPLETO DEL USUARIO:
            - Nombre: ${user.nombre}
            - Género: ${user.sexo}
            - Edad: ${edad} años
            - Altura: ${user.altura} cm
            - Peso actual: ${user.peso} kg
            - Peso objetivo: ${user.pesoObjetivo} kg
            - IMC actual: ${String.format("%.1f", imc)}
            - Diferencia de peso: ${if (diferenciaPeso > 0) "+${String.format("%.1f", diferenciaPeso)} kg" else "${String.format("%.1f", diferenciaPeso)} kg"}
            - Tipo de dieta: ${user.restriccionesDieta}
            - Nivel de actividad física: ${user.nivelActividad}
            - Objetivos de salud: ${user.objetivosSalud}
            
            INSTRUCCIONES ESPECÍFICAS PARA PERSONALIZACIÓN:
            1. SIEMPRE usa el nombre del usuario (${user.nombre}) en tus respuestas cuando sea apropiado
            2. Calcula las calorías y macronutrientes basándote en su peso (${user.peso}kg), altura (${user.altura}cm) y nivel de actividad (${user.nivelActividad})
            3. Considera su objetivo de peso: ${if (diferenciaPeso > 0) "necesita ganar ${String.format("%.1f", diferenciaPeso)} kg" else if (diferenciaPeso < 0) "necesita perder ${String.format("%.1f", -diferenciaPeso)} kg" else "mantener su peso actual"}
            4. Adapta las recomendaciones a su género (${user.sexo}) y edad (${edad} años)
            5. Respeta sus restricciones dietéticas: ${user.restriccionesDieta}
            6. Considera su nivel de actividad física: ${user.nivelActividad}
            7. Enfócate en sus objetivos de salud: ${user.objetivosSalud}
            
            IMPORTANTE: Personaliza TODAS las respuestas nutricionales basándote en estos datos específicos del usuario. No des consejos genéricos, sino recomendaciones específicas para ${user.nombre}.
            """
        } ?: "No hay información del perfil del usuario disponible. Pide al usuario que complete su perfil para poder dar recomendaciones personalizadas."
        
        val routineInfo = currentRoutine?.let { routine ->
            if (routine.isNotEmpty()) {
                """
                RUTINA REAL DEL USUARIO (DATOS REGISTRADOS):
                ${routine.groupBy { it.momentoDelDia }.entries.joinToString("\n") { (momento, alimentos) ->
                    "$momento:\n${alimentos.joinToString("\n") { "- ${it.alimento.nombreAlimento}" }}"
                }}
                
                IMPORTANTE: Estos son los ÚNICOS alimentos que el usuario ha registrado. NO inventes ni agregues alimentos que no estén en esta lista.
                """
            } else {
                "IMPORTANTE: El usuario NO ha registrado ningún alimento para hoy. NO inventes alimentos. Indica claramente que no hay datos registrados."
            }
        } ?: "IMPORTANTE: No hay datos de rutina disponibles. NO inventes alimentos. Indica claramente que no hay información registrada."
        
        return """
            $profileInfo
            
            $routineInfo
        """.trimIndent()
    }
    
    private fun calcularEdad(fechaNacimiento: String): Int {
        return try {
            val formato = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val fechaNac = formato.parse(fechaNacimiento)
            val hoy = Date()
            val diffInMillies = hoy.time - fechaNac.time
            val diffInDays = diffInMillies / (24 * 60 * 60 * 1000)
            (diffInDays / 365.25).toInt()
        } catch (e: Exception) {
            25 // Edad por defecto
        }
    }
    
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            println("=== TESTING GEMINI CONNECTION ===")
            val testResponse = generateResponse("Hola")
            println("✅ Test de conexión exitoso: ${testResponse.take(50)}...")
            true
        } catch (e: Exception) {
            println("❌ Test de conexión falló: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    suspend fun testSimpleConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            println("=== TESTING SIMPLE GEMINI CONNECTION ===")
            
            val simpleRequest = JSONObject().apply {
                // Formato correcto para contents
                val contentsArray = JSONArray()
                val contentObject = JSONObject()
                val partsArray = JSONArray()
                val partObject = JSONObject()
                partObject.put("text", "Responde solo: OK")
                partsArray.put(partObject)
                contentObject.put("parts", partsArray)
                contentsArray.put(contentObject)
                put("contents", contentsArray)
                
                // Formato correcto para generationConfig
                val generationConfig = JSONObject()
                generationConfig.put("temperature", 0.1)
                generationConfig.put("maxOutputTokens", 10)
                put("generationConfig", generationConfig)
            }.toString()
            
            val request = Request.Builder()
                .url("${GeminiConfig.BASE_URL}/models/${GeminiConfig.MODEL_NAME}:generateContent?key=${GeminiConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(simpleRequest.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            println("Status Code: ${response.code}")
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                println("✅ Respuesta simple exitosa: $responseBody")
                true
            } else {
                val errorBody = response.body?.string()
                println("❌ Error en test simple: $errorBody")
                false
            }
        } catch (e: Exception) {
            println("❌ Test simple falló: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    suspend fun diagnoseApiIssue(): String = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        
        try {
            // 1. Verificar configuración
            diagnostics.add("🔧 CONFIGURACIÓN:")
            diagnostics.add("API Key: ${GeminiConfig.API_KEY.take(10)}...")
            diagnostics.add("Base URL: ${GeminiConfig.BASE_URL}")
            diagnostics.add("Modelo: ${GeminiConfig.MODEL_NAME}")
            diagnostics.add("¿API Key válida? ${GeminiConfig.isValid()}")
            
            // 2. Test de conectividad básica y listar modelos
            diagnostics.add("\n🌐 CONECTIVIDAD Y MODELOS:")
            try {
                val testRequest = Request.Builder()
                    .url("${GeminiConfig.BASE_URL}/models?key=${GeminiConfig.API_KEY}")
                    .get()
                    .build()
                
                val testResponse = client.newCall(testRequest).execute()
                diagnostics.add("Status Code: ${testResponse.code}")
                diagnostics.add("Response Message: ${testResponse.message}")
                
                if (testResponse.isSuccessful) {
                    diagnostics.add("✅ Conexión básica exitosa")
                    val responseBody = testResponse.body?.string()
                    diagnostics.add("📋 Modelos disponibles:")
                    try {
                        val modelsJson = JSONObject(responseBody)
                        val modelsArray = modelsJson.getJSONArray("models")
                        var foundCompatibleModel = false
                        
                        for (i in 0 until modelsArray.length()) {
                            val model = modelsJson.getJSONArray("models").getJSONObject(i)
                            val name = model.getString("name")
                            val supportedMethods = model.getJSONArray("supportedGenerationMethods")
                            
                            // Verificar si soporta generateContent
                            var supportsGenerateContent = false
                            for (j in 0 until supportedMethods.length()) {
                                if (supportedMethods.getString(j) == "generateContent") {
                                    supportsGenerateContent = true
                                    break
                                }
                            }
                            
                            diagnostics.add("  - $name (generateContent: $supportsGenerateContent)")
                            
                            // Sugerir modelo compatible
                            if (supportsGenerateContent && !foundCompatibleModel) {
                                diagnostics.add("  ✅ SUGERENCIA: Usar $name")
                                foundCompatibleModel = true
                            }
                        }
                        
                        if (!foundCompatibleModel) {
                            diagnostics.add("❌ No se encontraron modelos compatibles con generateContent")
                        }
                        
                    } catch (e: Exception) {
                        diagnostics.add("❌ No se pudo parsear la lista de modelos: ${e.message}")
                        diagnostics.add("Response body: ${testResponse.body?.string()}")
                    }
                } else {
                    val errorBody = testResponse.body?.string()
                    diagnostics.add("❌ Error en conexión básica: $errorBody")
                }
                testResponse.close()
            } catch (e: Exception) {
                diagnostics.add("❌ Error de conectividad: ${e.message}")
            }
            
            // 3. Test de generación simple
            diagnostics.add("\n🤖 TEST DE GENERACIÓN:")
            try {
                val simpleRequest = JSONObject().apply {
                    // Formato correcto para contents
                    val contentsArray = JSONArray()
                    val contentObject = JSONObject()
                    val partsArray = JSONArray()
                    val partObject = JSONObject()
                    partObject.put("text", "Responde solo: OK")
                    partsArray.put(partObject)
                    contentObject.put("parts", partsArray)
                    contentsArray.put(contentObject)
                    put("contents", contentsArray)
                    
                    // Formato correcto para generationConfig
                    val generationConfig = JSONObject()
                    generationConfig.put("temperature", 0.1)
                    generationConfig.put("maxOutputTokens", 10)
                    put("generationConfig", generationConfig)
                }.toString()
                
                val request = Request.Builder()
                    .url("${GeminiConfig.BASE_URL}/models/${GeminiConfig.MODEL_NAME}:generateContent?key=${GeminiConfig.API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(simpleRequest.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                diagnostics.add("Status Code: ${response.code}")
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    diagnostics.add("✅ Generación exitosa: ${responseBody?.take(100)}...")
                } else {
                    val errorBody = response.body?.string()
                    diagnostics.add("❌ Error en generación: $errorBody")
                    
                    // Intentar parsear el error
                    try {
                        if (errorBody != null && errorBody.isNotEmpty()) {
                            val errorJson = JSONObject(errorBody)
                            if (errorJson.has("error")) {
                                val error = errorJson.getJSONObject("error")
                                val errorMessage = error.optString("message", "Error desconocido")
                                diagnostics.add("❌ Error detallado: $errorMessage")
                            }
                        }
                    } catch (e: Exception) {
                        diagnostics.add("❌ No se pudo parsear el error")
                    }
                }
                response.close()
            } catch (e: Exception) {
                diagnostics.add("❌ Error en test de generación: ${e.message}")
            }
            
        } catch (e: Exception) {
            diagnostics.add("❌ Error general en diagnóstico: ${e.message}")
        }
        
        diagnostics.joinToString("\n")
    }
}

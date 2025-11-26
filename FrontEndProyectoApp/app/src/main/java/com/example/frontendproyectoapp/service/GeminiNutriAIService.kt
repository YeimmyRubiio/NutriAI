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
    
    // SYSTEM PROMPT - Cargado una sola vez, siempre fijo
    private val SYSTEM_PROMPT = """
        Usted es NutriAI, un asistente nutricional profesional. 
        Su función es generar, modificar y validar rutinas alimenticias PERSONALIZADAS basadas únicamente en la base de datos de alimentos proporcionada por la aplicación DietaSmart.

        REGLAS GENERALES:
        1. Utilice EXCLUSIVAMENTE los alimentos enviados en el listado.
        2. Nunca invente alimentos, unidades ni cantidades.
        3. Las cantidades SIEMPRE deben ser enteras. Nunca devolver decimales.
        4. No repita alimentos en el día.
        5. Las comidas deben generarse SIEMPRE en este orden:
           DESAYUNO (3 alimentos)
           ALMUERZO (3 alimentos)
           CENA (3 alimentos)
           SNACK (2 alimentos)
        6. ⚠️ IMPORTANTE: Cada vez que se genera una rutina, debe ser COMPLETAMENTE NUEVA y DIFERENTE:
           - Varíe los alimentos seleccionados en cada generación.
           - NO repita la misma combinación de alimentos.
           - Explore diferentes opciones de la base de datos.
           - Cada rutina generada debe ser única y diferente a las anteriores.

        UNIDADES DE MEDIDA (CRÍTICO - COHERENCIA Y NATURALIDAD):
        - Cada alimento tiene unidades disponibles (porción, taza, ml, gramos, vaso, unidad, etc.) que se indican en el listado.
        - SIEMPRE use las unidades ORIGINALES del alimento cuando sea posible, pero priorice unidades NATURALES y COHERENTES según el tipo de alimento:
        
        REGLAS DE COHERENCIA POR TIPO DE ALIMENTO (OBLIGATORIO):
        - Líquidos (leche, jugos, agua, yogur líquido): Use "vaso" o "ml", NUNCA "taza"
        - Verduras y hortalizas (brócoli, espinaca, lechuga, repollo): Use "porción" o "unidad", NO "taza" a menos que sea la única opción
        - Carnes y pescados (pollo, salmón, res, atún): Use "filete", "porción" o "unidad", NUNCA "taza"
        - Granos y cereales (arroz, quinoa, avena, pasta): Use "taza" o "porción"
        - Frutas (manzana, banana, naranja): Use "pieza" o "unidad", NO "taza"
        - Frutos secos y semillas (almendras, nueces): Use "puñado" o "porción", NO "taza"
        - Huevos (huevo, clara, yema): Use "unidad" o "pieza", NUNCA "taza"
        - Lácteos sólidos (queso, yogur sólido): Use "porción" o "unidad", "taza" solo si no hay otras opciones
        - Legumbres (lenteja, frijol, garbanzo): Use "taza" o "porción"
        
        EJEMPLOS CORRECTOS:
        - Leche: "1 vaso" (NO "1 taza")
        - Brócoli: "1 porción" o "1 unidad" (NO "1 taza")
        - Pollo: "1 filete" o "1 porción" (NO "1 taza")
        - Huevo: "2 unidades" (NO "2 tazas")
        - Arroz: "1 taza" (correcto)
        - Almendras: "1 puñado" (NO "1 taza")
        
        - Priorice siempre la unidad más natural y coherente para cada tipo de alimento.
        - Solo use gramos si es la única unidad disponible o si es la más apropiada para ese alimento específico.
        - Las unidades deben ser las que aparecen en el listado de alimentos disponibles.

        COHERENCIA OBLIGATORIA:
        - El plan debe respetar: género, edad, peso actual, peso objetivo, tipo de dieta, nivel de actividad, objetivo.
        - Para pérdida de peso → pocas calorías, proteínas magras, baja carga glucémica.
        - Para dieta baja en carbohidratos → evitar arroz, pasta, harina, dulces, frutas altas en azúcar.
        - Para sedentarismo → porciones moderadas.
        - Para cada momento del día utilice únicamente alimentos apropiados (ej. nada pesado en snack, no carnes grasas en desayuno).

        FORMATO DE RESPUESTA OBLIGATORIO (sin texto adicional):

        DESAYUNO:
        - alimento – cantidad unidad
        - alimento – cantidad unidad
        - alimento – cantidad unidad

        ALMUERZO:
        - alimento – cantidad unidad
        - alimento – cantidad unidad
        - alimento – cantidad unidad

        CENA:
        - alimento – cantidad unidad
        - alimento – cantidad unidad
        - alimento – cantidad unidad

        SNACK:
        - alimento – cantidad unidad
        - alimento – cantidad unidad

        Después de generar la rutina, muestre siempre EXACTAMENTE:
        "Escriba `cambiar rutina` si desea generar una nueva rutina."
        "Escriba `cambiar alimento` si desea reemplazar un alimento de la rutina."
        "Escriba `finalizar` para guardar la rutina."
    """.trimIndent()
    
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
        - LENGUAJE FORMAL: SIEMPRE usa "usted", "su", "para usted", "le recomiendo", "¿en qué puedo asistirle?" en lugar de tuteo ("tú", "te", "contigo", etc.)
        - Mantén un tono amable, empático y claro, pero siempre formal y profesional

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
                salúdelo de forma amigable y menciona su nombre usando lenguaje formal. 
                Ejemplo: "¡Hola, Ana! 😊 ¿En qué puedo asistirle hoy con sus hábitos alimenticios?"

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
                2. Adapta sus recomendaciones a los datos del perfil (por ejemplo, si el usuario busca bajar de peso, ofrezca consejos enfocados en déficit calórico saludable).
                3. Usa un tono amigable, cálido y motivador, pero SIEMPRE con lenguaje formal. Diríjase al usuario con "usted", "su", "para usted", "le recomiendo", "¿en qué puedo asistirle?".
                4. Si el usuario pregunta algo fuera del tema de nutrición, rediríjalo educadamente al tema principal.
                5. Mantenga las respuestas breves, claras y fáciles de entender.
                6. Muestre entusiasmo y apoyo en sus respuestas.
                7. NO use género (masculino/femenino) en sus respuestas. Use lenguaje neutro como "estoy preparado", "puedo ayudarle", etc.
                8. NUNCA use tuteo ("tú", "te", "contigo", "tu", "tus", etc.), incluso si el usuario escribe en lenguaje informal.

                Comience siempre con un saludo amigable y personalizado usando lenguaje formal como:
                "¡Hola, ${currentUserProfile.nombre ?: "Usuario"}! 😊 Es un placer conocerle. Soy NutriAI, su asistente personal de nutrición y estoy aquí para ayudarle a alcanzar sus objetivos de salud.
                
                Veo que su objetivo es ${currentUserProfile.objetivosSalud ?: "mantener un estilo de vida saludable"}.
                
                ¿En qué puedo asistirle hoy?"
                
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
                1. Ofrezca información general y educativa, nunca diagnósticos médicos.
                2. Adapte sus recomendaciones a los datos del perfil (por ejemplo, si el usuario busca bajar de peso, ofrezca consejos enfocados en déficit calórico saludable).
                3. Use un tono amable y motivador, pero SIEMPRE con lenguaje formal. Diríjase al usuario con "usted", "su", "para usted", "le recomiendo", "¿en qué puedo asistirle?".
                4. Si el usuario pregunta algo fuera del tema de nutrición, rediríjalo educadamente al tema principal.
                5. Mantenga las respuestas breves, claras y fáciles de entender.
                6. NO repita saludos como "Hola" o "¡Hola!" en respuestas posteriores - solo responda directamente a la pregunta.
                7. Recuerde que ya se presentó como NutriAI, así que no necesita volver a saludar.
                8. NUNCA use tuteo ("tú", "te", "contigo", "tu", "tus", etc.), incluso si el usuario escribe en lenguaje informal.

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
            1. SIEMPRE use el nombre del usuario (${user.nombre}) en sus respuestas cuando sea apropiado
            2. Calcule las calorías y macronutrientes basándose en su peso (${user.peso}kg), altura (${user.altura}cm) y nivel de actividad (${user.nivelActividad})
            3. Considere su objetivo de peso: ${if (diferenciaPeso > 0) "necesita ganar ${String.format("%.1f", diferenciaPeso)} kg" else if (diferenciaPeso < 0) "necesita perder ${String.format("%.1f", -diferenciaPeso)} kg" else "mantener su peso actual"}
            4. Adapte las recomendaciones a su género (${user.sexo}) y edad (${edad} años)
            5. Respete sus restricciones dietéticas: ${user.restriccionesDieta}
            6. Considere su nivel de actividad física: ${user.nivelActividad}
            7. Enfóquese en sus objetivos de salud: ${user.objetivosSalud}
            
            IMPORTANTE: Personalice TODAS las respuestas nutricionales basándose en estos datos específicos del usuario. No ofrezca consejos genéricos, sino recomendaciones específicas para ${user.nombre}. Use SIEMPRE lenguaje formal con "usted", "su", "para usted", "le recomiendo", etc.
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
    
    /**
     * Construye el prompt del usuario con los datos necesarios
     */
    private suspend fun buildUserPrompt(
        genero: String,
        edad: Int,
        altura: Float,
        pesoActual: Float,
        pesoObjetivo: Float,
        objetivo: String,
        tipoDieta: String,
        actividad: String,
        alimentos: List<Alimento>,
        obtenerUnidades: suspend (Long) -> List<String>
    ): String = withContext(Dispatchers.IO) {
        // Formatear alimentos como JSON con nombre, unidad base, cantidad, categoría y unidades disponibles
        // Procesar cada alimento de forma asíncrona para obtener sus unidades
        val alimentosJsonList = alimentos.take(100).map { alimento ->
            // Obtener unidades disponibles para este alimento
            val unidadesDisponibles = try {
                obtenerUnidades(alimento.idAlimento)
            } catch (e: Exception) {
                listOf(alimento.unidadBase)
            }
            // Formatear unidades como array JSON
            val unidadesJson = unidadesDisponibles.joinToString(", ") { "\"$it\"" }
            
            """{"nombre": "${alimento.nombreAlimento}", "unidadBase": "${alimento.unidadBase}", "cantidadBase": ${alimento.cantidadBase}, "categoria": "${alimento.categoria}", "unidadesDisponibles": [$unidadesJson]}"""
        }
        
        val alimentosJson = alimentosJsonList.joinToString(prefix = "[", postfix = "]")
        
        return@withContext """
            Generar una rutina nutricional personalizada COMPLETAMENTE NUEVA utilizando EXCLUSIVAMENTE los alimentos proporcionados.

            ⚠️ IMPORTANTE: Esta es una NUEVA generación de rutina. Debe ser COMPLETAMENTE DIFERENTE a cualquier rutina anterior.
            - Varíe los alimentos seleccionados en cada generación.
            - NO repita la misma combinación de alimentos.
            - Explore diferentes opciones de la base de datos.
            - Cada vez que se genera una rutina, debe ser única y diferente.

            DATOS DEL USUARIO:
            Género: $genero
            Edad: $edad
            Altura: $altura cm
            Peso actual: $pesoActual kg
            Peso objetivo: $pesoObjetivo kg
            Objetivo: $objetivo
            Tipo de dieta: $tipoDieta
            Nivel de actividad física: $actividad

            ALIMENTOS DISPONIBLES (NO INVENTAR):
            Cada alimento tiene unidades disponibles. Use las unidades ORIGINALES cuando sea posible (porción, taza, ml, etc.), NO siempre gramos.
            $alimentosJson

            IMPORTANTE SOBRE UNIDADES (COHERENCIA Y NATURALIDAD - OBLIGATORIO):
            - Use las unidades disponibles de cada alimento, pero priorice unidades NATURALES y COHERENTES:
            * Líquidos (leche, jugos, agua): "vaso" o "ml", NUNCA "taza"
            * Verduras (brócoli, espinaca): "porción" o "unidad", NO "taza"
            * Carnes/pescados (pollo, salmón): "filete", "porción" o "unidad", NUNCA "taza"
            * Granos (arroz, quinoa, avena): "taza" o "porción"
            * Frutas: "pieza" o "unidad", NO "taza"
            * Frutos secos: "puñado" o "porción", NO "taza"
            * Huevos: "unidad" o "pieza", NUNCA "taza"
            * Legumbres: "taza" o "porción"
            - Elija SIEMPRE la unidad más natural y coherente para cada tipo de alimento.
            - Solo use gramos si es la única unidad disponible o si es la más apropiada.
            - Revise el listado de unidades disponibles para cada alimento y elija la más coherente.

            Genere la rutina cumpliendo TODAS las reglas del sistema y asegurándose de que sea COMPLETAMENTE NUEVA y DIFERENTE.
        """.trimIndent()
    }
    
    /**
     * Genera una rutina nutricional personalizada usando Gemini AI
     * basándose en el perfil del usuario y los alimentos disponibles en la base de datos
     */
    suspend fun generatePersonalizedRoutine(
        userProfile: Usuario,
        availableFoods: List<Alimento>,
        obtenerUnidades: suspend (Long) -> List<String>
    ): String = withContext(Dispatchers.IO) {
        try {
            println("=== GENERANDO RUTINA CON GEMINI ===")
            println("Usuario: ${userProfile.nombre}")
            println("Alimentos disponibles: ${availableFoods.size}")
            
            // Calcular edad
            val edad = calcularEdad(userProfile.fechaNacimiento)
            
            // Construir prompt del usuario
            val userPrompt = buildUserPrompt(
                genero = userProfile.sexo ?: "No especificado",
                edad = edad,
                altura = userProfile.altura,
                pesoActual = userProfile.peso,
                pesoObjetivo = userProfile.pesoObjetivo,
                objetivo = userProfile.objetivosSalud ?: "Mantener peso",
                tipoDieta = userProfile.restriccionesDieta ?: "Recomendada",
                actividad = userProfile.nivelActividad ?: "Moderada",
                alimentos = availableFoods.take(100), // Limitar a 100 alimentos para reducir tokens
                obtenerUnidades = obtenerUnidades
            )
            
            
            // Construir el request para Gemini
            // Incluir SYSTEM_PROMPT al inicio del contenido del usuario ya que systemInstruction puede no estar soportado
            val fullPrompt = "$SYSTEM_PROMPT\n\n$userPrompt"
            
            val requestBody = JSONObject().apply {
                // Contents (prompt completo: system + user)
                val contentsArray = JSONArray()
                val contentObject = JSONObject()
                val partsArray = JSONArray()
                val partObject = JSONObject()
                partObject.put("text", fullPrompt)
                partsArray.put(partObject)
                contentObject.put("parts", partsArray)
                contentsArray.put(contentObject)
                put("contents", contentsArray)
                
                // Generation config
                // Aumentar temperatura para mayor variación en las rutinas generadas
                val generationConfig = JSONObject()
                generationConfig.put("temperature", 0.9) // Aumentado de 0.7 a 0.9 para mayor variación
                generationConfig.put("maxOutputTokens", 8000)
                put("generationConfig", generationConfig)
            }.toString()
            
            println("📝 Request body size: ${requestBody.length} caracteres")
            
            val request = Request.Builder()
                .url("${GeminiConfig.BASE_URL}/models/${GeminiConfig.MODEL_NAME}:generateContent?key=${GeminiConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "NutriAI-Android/1.0")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            println("=== ENVIANDO REQUEST A GEMINI PARA GENERAR RUTINA ===")
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                println("✅ Respuesta de Gemini recibida para rutina")
                
                if (responseBody != null && responseBody.isNotEmpty()) {
                    val jsonResponse = JSONObject(responseBody)
                    
                    // Verificar si hay error
                    if (jsonResponse.has("error")) {
                        val error = jsonResponse.getJSONObject("error")
                        val errorMessage = error.getString("message")
                        throw Exception("Error de Gemini API: $errorMessage")
                    }
                    
                    // Verificar candidates
                    if (!jsonResponse.has("candidates")) {
                        throw Exception("Respuesta de Gemini no tiene el formato esperado: falta 'candidates'")
                    }
                    
                    val candidates = jsonResponse.getJSONArray("candidates")
                    if (candidates.length() == 0) {
                        throw Exception("No se recibió respuesta de Gemini")
                    }
                    
                    val candidate = candidates.getJSONObject(0)
                    
                    // Detectar si Gemini cortó la respuesta
                    if (candidate.has("finishReason")) {
                        val finishReason = candidate.getString("finishReason")
                        if (finishReason == "MAX_TOKENS") {
                            throw Exception("La respuesta excedió el límite de tokens. Intente con menos datos.")
                        }
                        if (finishReason == "SAFETY") {
                            throw Exception("La respuesta fue bloqueada por filtros de seguridad de Gemini")
                        }
                    }
                    
                    // Verificar content
                    if (!candidate.has("content")) {
                        throw Exception("Gemini respondió vacío.")
                    }
                    
                    val content = candidate.getJSONObject("content")
                    
                    // Verificar parts
                    if (!content.has("parts")) {
                        throw Exception("La respuesta de Gemini no contiene partes válidas.")
                    }
                    
                    val parts = content.getJSONArray("parts")
                    if (parts.length() == 0) {
                        throw Exception("Array 'parts' está vacío")
                    }
                    
                    // Extraer el texto limpio
                    val text = (0 until parts.length()).joinToString("\n") { index ->
                        val part = parts.getJSONObject(index)
                        part.optString("text", "")
                    }.trim()
                    
                    if (text.isBlank()) {
                        throw Exception("Gemini devolvió texto vacío.")
                    }
                    
                    println("✅ Rutina generada por Gemini: ${text.take(200)}...")
                    return@withContext text
                } else {
                    throw Exception("Respuesta vacía de Gemini")
                }
            } else {
                val errorBody = response.body?.string()
                println("❌ Error en Gemini: ${response.code} - ${response.message}")
                println("📄 Error body: $errorBody")
                
                // Intentar parsear el error para más detalles
                try {
                    if (errorBody != null && errorBody.isNotEmpty()) {
                        val errorJson = JSONObject(errorBody)
                        if (errorJson.has("error")) {
                            val error = errorJson.getJSONObject("error")
                            val errorMessage = error.optString("message", "Error desconocido")
                            val errorCode = error.optInt("code", response.code)
                            println("❌ Error detallado: $errorMessage (Código: $errorCode)")
                            throw Exception("Error de Gemini API: $errorMessage")
                        }
                    }
                } catch (e: Exception) {
                    println("No se pudo parsear el error: ${e.message}")
                }
                
                throw Exception("Error en Gemini API: ${response.code} - ${response.message}")
            }
            
        } catch (e: Exception) {
            println("=== ERROR GENERANDO RUTINA CON GEMINI ===")
            println("Tipo de error: ${e.javaClass.simpleName}")
            println("Mensaje: ${e.message}")
            e.printStackTrace()
            throw Exception("Error generando rutina: ${e.message}")
        }
    }
    
    /**
     * Construye el prompt para cambiar un alimento específico
     */
    private fun buildChangeFoodPrompt(
        genero: String,
        edad: Int,
        altura: Float,
        pesoActual: Float,
        pesoObjetivo: Float,
        objetivo: String,
        tipoDieta: String,
        actividad: String,
        alimentoACambiar: String,
        momento: String,
        alimentos: List<Alimento>,
        alimentosYaEnRutina: List<String>
    ): String {
        val alimentosJson = alimentos.joinToString(prefix = "[", postfix = "]") { alimento ->
            """{"nombre": "${alimento.nombreAlimento}", "unidad": "${alimento.unidadBase}", "cantidad": ${alimento.cantidadBase}, "categoria": "${alimento.categoria}"}"""
        }
        
        val alimentosExcluidos = alimentosYaEnRutina.joinToString(", ")
        
        return """
            El usuario desea cambiar UN alimento de su rutina.

            DATOS DEL USUARIO:
            Género: $genero
            Edad: $edad
            Altura: $altura cm
            Peso actual: $pesoActual kg
            Peso objetivo: $pesoObjetivo kg
            Objetivo: $objetivo
            Tipo de dieta: $tipoDieta
            Nivel de actividad física: $actividad

            ALIMENTO A REEMPLAZAR: $alimentoACambiar
            MOMENTO DEL DÍA: $momento
            ALIMENTOS YA EN LA RUTINA (NO USAR): $alimentosExcluidos

            ALIMENTOS DISPONIBLES:
            $alimentosJson

            Antes de modificar, pregunte:
            "¿Confirma que desea reemplazar este alimento?"

            Cuando el usuario confirme:
            - Sustituya el alimento seleccionando otro de la misma categoría.
            - Mantenga coherencia con objetivo, tipo de dieta y momento del día.
            - No repita alimentos.
            - Mantenga el formato y las cantidades enteras.

            Después del cambio, NO actualice aún la rutina en la base de datos.
            Debe mostrar las mismas opciones:
            "Escriba `cambiar rutina` si desea generar una nueva rutina."
            "Escriba `cambiar alimento` si desea reemplazar un alimento de la rutina."
            "Escriba `finalizar` para guardar la rutina."
        """.trimIndent()
    }
    
    /**
     * Construye el prompt para cambiar toda la rutina
     */
    private fun buildChangeCompleteRoutinePrompt(
        genero: String,
        edad: Int,
        altura: Float,
        pesoActual: Float,
        pesoObjetivo: Float,
        objetivo: String,
        tipoDieta: String,
        actividad: String,
        alimentos: List<Alimento>
    ): String {
        val alimentosJson = alimentos.joinToString(prefix = "[", postfix = "]") { alimento ->
            """{"nombre": "${alimento.nombreAlimento}", "unidad": "${alimento.unidadBase}", "cantidad": ${alimento.cantidadBase}, "categoria": "${alimento.categoria}"}"""
        }
        
        return """
            El usuario desea cambiar toda la rutina.

            DATOS DEL USUARIO:
            Género: $genero
            Edad: $edad
            Altura: $altura cm
            Peso actual: $pesoActual kg
            Peso objetivo: $pesoObjetivo kg
            Objetivo: $objetivo
            Tipo de dieta: $tipoDieta
            Nivel de actividad física: $actividad

            ALIMENTOS DISPONIBLES:
            $alimentosJson

            Antes de realizarlo, pregunte:
            "¿Confirma que desea generar una rutina completamente nueva?"

            Si el usuario confirma:
            - Genere una nueva rutina desde cero siguiendo exactamente las reglas del sistema.
            - No repita alimentos.
            - Mantenga coherencia total con dieta, objetivo y actividad.

            Después de generar la nueva rutina, NO la guarde todavía.
            Debe mostrar las mismas opciones:

            "Escriba `cambiar rutina` si desea generar una nueva rutina."
            "Escriba `cambiar alimento` si desea reemplazar un alimento de la rutina."
            "Escriba `finalizar` para guardar la rutina."
        """.trimIndent()
    }
    
    /**
     * Construye el prompt para validar una rutina
     */
    fun buildValidateRoutinePrompt(
        genero: String,
        edad: Int,
        altura: Float,
        pesoActual: Float,
        pesoObjetivo: Float,
        objetivo: String,
        tipoDieta: String,
        actividad: String,
        alimentos: List<Alimento>,
        rutina: String
    ): String {
        val alimentosJson = alimentos.joinToString(prefix = "[", postfix = "]") { alimento ->
            """{"nombre": "${alimento.nombreAlimento}", "unidad": "${alimento.unidadBase}", "cantidad": ${alimento.cantidadBase}, "categoria": "${alimento.categoria}"}"""
        }
        
        return """
            Actúe como un asistente nutricional.

            Su tarea es VALIDAR la rutina nutricional generada previamente.

            REGLAS:
            1. Verifique que la rutina respete, estrictamente, los datos del usuario.
            2. Verifique que las combinaciones sean coherentes con el momento del día.
            3. Verifique que no se repitan alimentos.
            4. Verifique que las cantidades sean enteras, razonables y saludables.
            5. Verifique que la rutina realmente cumple la dieta indicada (por ejemplo, baja en carbohidratos).
            6. Verifique que sea adecuada para pérdida de peso si ese es el objetivo.
            7. Verifique que NO incluya alimentos fuera de la base de datos.

            DATOS DEL USUARIO:
            Género: $genero
            Edad: $edad
            Altura: $altura cm
            Peso actual: $pesoActual kg
            Peso objetivo: $pesoObjetivo kg
            Objetivo: $objetivo
            Tipo de dieta: $tipoDieta
            Nivel de actividad física: $actividad

            ALIMENTOS DISPONIBLES:
            $alimentosJson

            RUTINA A VALIDAR:
            $rutina

            RESPONDA EN ESTE FORMATO:

            VALIDEZ: "Válida" o "No válida".

            DETALLES:
            - Explique qué sí es coherente.
            - Explique qué NO es coherente en caso de fallas.

            SI ES NO VÁLIDA:
            Genere una versión corregida cumpliendo TODAS las reglas original.
        """.trimIndent()
    }
    
    /**
     * Construye el prompt para autocorregir formato de rutina
     */
    fun buildAutoCorrectPrompt(
        alimentos: List<Alimento>,
        respuestaOriginal: String
    ): String {
        val alimentosJson = alimentos.joinToString(prefix = "[", postfix = "]") { alimento ->
            """{"nombre": "${alimento.nombreAlimento}", "unidad": "${alimento.unidadBase}", "cantidad": ${alimento.cantidadBase}, "categoria": "${alimento.categoria}"}"""
        }
        
        return """
            La siguiente respuesta generada por la IA NO está en el formato correcto.

            Debe corregirla cumpliendo estrictamente las siguientes reglas:

            FORMATO OBLIGATORIO:

            DESAYUNO:
            - alimento – cantidad unidad
            - alimento – cantidad unidad
            - alimento – cantidad unidad

            ALMUERZO:
            - alimento – cantidad unidad
            - alimento – cantidad unidad
            - alimento – cantidad unidad

            CENA:
            - alimento – cantidad unidad
            - alimento – cantidad unidad
            - alimento – cantidad unidad

            SNACK:
            - alimento – cantidad unidad
            - alimento – cantidad unidad

            REGLAS ESTRICTAS:
            1. Las cantidades deben ser SIEMPRE enteras.
            2. No puede haber decimales.
            3. No repita alimentos en todo el día.
            4. No invente alimentos.
            5. Solo utilice alimentos de la base de datos.
            6. Ajuste cantidades y alimentos si es necesario.
            7. No agregue explicaciones extras.
            8. No incluya texto fuera del formato.

            ALIMENTOS DISPONIBLES:
            $alimentosJson

            RESPUESTA ORIGINAL INCORRECTA:
            $respuestaOriginal

            Ahora genere una versión CORREGIDA en el formato exacto.
        """.trimIndent()
    }
    
    /**
     * Construye el prompt para validar alimentos repetidos
     */
    fun buildValidateDuplicatesPrompt(
        alimentos: List<Alimento>,
        rutina: String
    ): String {
        val alimentosJson = alimentos.joinToString(prefix = "[", postfix = "]") { alimento ->
            """{"nombre": "${alimento.nombreAlimento}", "unidad": "${alimento.unidadBase}", "cantidad": ${alimento.cantidadBase}, "categoria": "${alimento.categoria}"}"""
        }
        
        return """
            Actúa como un asistente nutricional.

            REGLAS:
            1. Revisa la rutina generada y DETECTA alimentos repetidos en el mismo día.
            2. Si NO hay duplicados → responde: "Rutina válida: no hay alimentos repetidos".
            3. Si SÍ hay duplicados → genera una NUEVA rutina:
               - Mantén los macronutrientes similares.
               - Reemplaza los duplicados por alimentos del MISMO grupo (proteína por proteína, vegetal por vegetal, fruta por fruta).
               - No cambies la porción actual, solo reemplaza el alimento.
               - No inventes cantidades nuevas.
            4. Mantén siempre 1 opción por comida: desayuno, almuerzo, cena, snack.
            5. No uses alimentos que no estén en la base de datos del usuario.

            FORMATO DE RESPUESTA:
            - Si es válida → texto simple.
            - Si fue corregida → responde SOLO la rutina final en este formato:

            DESAYUNO:
            - alimento – cantidad unidad
            - alimento – cantidad unidad
            - alimento – cantidad unidad

            ALMUERZO:
            - alimento – cantidad unidad
            - alimento – cantidad unidad
            - alimento – cantidad unidad

            CENA:
            - alimento – cantidad unidad
            - alimento – cantidad unidad
            - alimento – cantidad unidad

            SNACK:
            - alimento – cantidad unidad
            - alimento – cantidad unidad

            ALIMENTOS DISPONIBLES:
            $alimentosJson

            TEXTO A VALIDAR:
            $rutina
        """.trimIndent()
    }
    
    /**
     * Función genérica para enviar un prompt a Gemini y obtener respuesta
     */
    private suspend fun sendPromptToGemini(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            // Incluir SYSTEM_PROMPT al inicio
            val fullPrompt = "$SYSTEM_PROMPT\n\n$prompt"
            
            val requestBody = JSONObject().apply {
                val contentsArray = JSONArray()
                val contentObject = JSONObject()
                val partsArray = JSONArray()
                val partObject = JSONObject()
                partObject.put("text", fullPrompt)
                partsArray.put(partObject)
                contentObject.put("parts", partsArray)
                contentsArray.put(contentObject)
                put("contents", contentsArray)
                
                val generationConfig = JSONObject()
                generationConfig.put("temperature", 0.7)
                generationConfig.put("maxOutputTokens", 8000)
                put("generationConfig", generationConfig)
            }.toString()
            
            val request = Request.Builder()
                .url("${GeminiConfig.BASE_URL}/models/${GeminiConfig.MODEL_NAME}:generateContent?key=${GeminiConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "NutriAI-Android/1.0")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                
                if (responseBody != null && responseBody.isNotEmpty()) {
                    val jsonResponse = JSONObject(responseBody)
                    
                    if (jsonResponse.has("error")) {
                        val error = jsonResponse.getJSONObject("error")
                        val errorMessage = error.getString("message")
                        throw Exception("Error de Gemini API: $errorMessage")
                    }
                    
                    if (!jsonResponse.has("candidates")) {
                        throw Exception("Respuesta de Gemini no tiene el formato esperado: falta 'candidates'")
                    }
                    
                    val candidates = jsonResponse.getJSONArray("candidates")
                    if (candidates.length() == 0) {
                        throw Exception("No se recibió respuesta de Gemini")
                    }
                    
                    val candidate = candidates.getJSONObject(0)
                    
                    if (candidate.has("finishReason")) {
                        val finishReason = candidate.getString("finishReason")
                        if (finishReason == "MAX_TOKENS") {
                            throw Exception("La respuesta excedió el límite de tokens. Intente con menos datos.")
                        }
                        if (finishReason == "SAFETY") {
                            throw Exception("La respuesta fue bloqueada por filtros de seguridad de Gemini")
                        }
                    }
                    
                    if (!candidate.has("content")) {
                        throw Exception("Gemini respondió vacío.")
                    }
                    
                    val content = candidate.getJSONObject("content")
                    
                    if (!content.has("parts")) {
                        throw Exception("La respuesta de Gemini no contiene partes válidas.")
                    }
                    
                    val parts = content.getJSONArray("parts")
                    if (parts.length() == 0) {
                        throw Exception("Array 'parts' está vacío")
                    }
                    
                    val text = (0 until parts.length()).joinToString("\n") { index ->
                        val part = parts.getJSONObject(index)
                        part.optString("text", "")
                    }.trim()
                    
                    if (text.isBlank()) {
                        throw Exception("Gemini devolvió texto vacío.")
                    }
                    
                    return@withContext text
                } else {
                    throw Exception("Respuesta vacía de Gemini")
                }
            } else {
                val errorBody = response.body?.string()
                println("❌ Error en Gemini: ${response.code} - ${response.message}")
                println("📄 Error body: $errorBody")
                throw Exception("Error en Gemini API: ${response.code} - ${response.message}")
            }
            
        } catch (e: Exception) {
            println("=== ERROR EN GEMINI ===")
            println("Tipo de error: ${e.javaClass.simpleName}")
            println("Mensaje: ${e.message}")
            e.printStackTrace()
            throw Exception("Error generando respuesta: ${e.message}")
        }
    }
    
    /**
     * Genera una rutina usando Gemini con el prompt de cambio completo
     */
    suspend fun generateChangedRoutine(
        userProfile: Usuario,
        availableFoods: List<Alimento>
    ): String = withContext(Dispatchers.IO) {
        val edad = calcularEdad(userProfile.fechaNacimiento)
        val prompt = buildChangeCompleteRoutinePrompt(
            genero = userProfile.sexo ?: "No especificado",
            edad = edad,
            altura = userProfile.altura,
            pesoActual = userProfile.peso,
            pesoObjetivo = userProfile.pesoObjetivo,
            objetivo = userProfile.objetivosSalud ?: "Mantener peso",
            tipoDieta = userProfile.restriccionesDieta ?: "Recomendada",
            actividad = userProfile.nivelActividad ?: "Moderada",
            alimentos = availableFoods.take(100)
        )
        sendPromptToGemini(prompt)
    }
    
    /**
     * Valida y corrige una rutina si tiene problemas
     */
    suspend fun validateAndCorrectRoutine(
        userProfile: Usuario,
        availableFoods: List<Alimento>,
        rutina: String
    ): String = withContext(Dispatchers.IO) {
        val edad = calcularEdad(userProfile.fechaNacimiento)
        
        // Primero validar duplicados
        val duplicatesPrompt = buildValidateDuplicatesPrompt(availableFoods.take(100), rutina)
        val duplicatesResponse = sendPromptToGemini(duplicatesPrompt)
        
        // Si no dice "válida", usar la respuesta corregida
        if (!duplicatesResponse.contains("válida", ignoreCase = true) && 
            !duplicatesResponse.contains("no hay alimentos repetidos", ignoreCase = true)) {
            return@withContext duplicatesResponse
        }
        
        // Luego validar coherencia general
        val validatePrompt = buildValidateRoutinePrompt(
            genero = userProfile.sexo ?: "No especificado",
            edad = edad,
            altura = userProfile.altura,
            pesoActual = userProfile.peso,
            pesoObjetivo = userProfile.pesoObjetivo,
            objetivo = userProfile.objetivosSalud ?: "Mantener peso",
            tipoDieta = userProfile.restriccionesDieta ?: "Recomendada",
            actividad = userProfile.nivelActividad ?: "Moderada",
            alimentos = availableFoods.take(100),
            rutina = rutina
        )
        val validateResponse = sendPromptToGemini(validatePrompt)
        
        // Si no es válida, usar autocorrección
        if (validateResponse.contains("No válida", ignoreCase = true)) {
            val correctPrompt = buildAutoCorrectPrompt(availableFoods.take(100), rutina)
            return@withContext sendPromptToGemini(correctPrompt)
        }
        
        return@withContext rutina
    }
}

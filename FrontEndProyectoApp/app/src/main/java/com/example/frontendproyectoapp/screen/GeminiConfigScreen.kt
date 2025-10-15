package com.example.frontendproyectoapp.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.frontendproyectoapp.config.GeminiConfig
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiConfigScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(GeminiConfig.API_KEY) }
    var showApiKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticsResult by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onBackClick) {
                Text("← Volver")
            }
            Text(
                text = "Configuración de Gemini API",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        
        Divider()
        
        // API Key Input
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "API Key de Gemini",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Text(
                    text = "Ingresa tu API key de Google Gemini para habilitar el chatbot inteligente.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("AIzaSy...") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    trailingIcon = {
                        TextButton(onClick = { showApiKey = !showApiKey }) {
                            Text(if (showApiKey) "Ocultar" else "Mostrar")
                        }
                    }
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            GeminiConfig.setApiKey(context, apiKey)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Guardar")
                    }
                    
                    Button(
                        onClick = {
                            isTesting = true
                            testResult = null
                            // Test de conexión en un coroutine
                            coroutineScope.launch {
                                try {
                                    val geminiService = com.example.frontendproyectoapp.service.GeminiNutriAIService()
                                    val success = geminiService.testConnection()
                                    testResult = if (success) "✅ Conexión exitosa" else "❌ Error de conexión"
                                } catch (e: Exception) {
                                    testResult = "❌ Error: ${e.message}"
                                } finally {
                                    isTesting = false
                                }
                            }
                        },
                        enabled = !isTesting && apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isTesting) "Probando..." else "Probar")
                    }
                }
                
                // Botones de diagnóstico
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showDiagnostics = true
                            diagnosticsResult = null
                            coroutineScope.launch {
                                try {
                                    val geminiService = com.example.frontendproyectoapp.service.GeminiNutriAIService()
                                    val diagnostics = geminiService.diagnoseApiIssue()
                                    diagnosticsResult = diagnostics
                                } catch (e: Exception) {
                                    diagnosticsResult = "❌ Error en diagnóstico: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("🔍 Diagnóstico")
                    }
                    
                    Button(
                        onClick = {
                            showDiagnostics = true
                            diagnosticsResult = null
                            coroutineScope.launch {
                                try {
                                    val chatbotService = com.example.frontendproyectoapp.service.ChatbotService()
                                    val testResult = chatbotService.testChatbotResponse()
                                    diagnosticsResult = "🧪 TEST DE CHATBOT:\n$testResult"
                                } catch (e: Exception) {
                                    diagnosticsResult = "❌ Error en test de chatbot: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("🧪 Test Chatbot")
                    }
                }
                
                // Test Result
                testResult?.let { result ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.startsWith("✅")) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = result,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                
                // Diagnóstico detallado
                if (showDiagnostics) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "🔍 Diagnóstico Detallado",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            diagnosticsResult?.let { result ->
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } ?: run {
                                Text(
                                    text = "Ejecutando diagnóstico...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Button(
                                onClick = { showDiagnostics = false },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Cerrar")
                            }
                        }
                    }
                }
            }
        }
        
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (GeminiConfig.isValid()) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Estado de la API",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Text(
                    text = if (GeminiConfig.isValid()) {
                        "✅ API Key configurada correctamente"
                    } else {
                        "❌ API Key no válida o no configurada"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "URL: ${GeminiConfig.BASE_URL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "Modelo: ${GeminiConfig.MODEL_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Instructions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Cómo obtener tu API Key",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Text(
                    text = "1. Ve a Google AI Studio (https://aistudio.google.com/)\n" +
                            "2. Inicia sesión con tu cuenta de Google\n" +
                            "3. Crea un nuevo proyecto o selecciona uno existente\n" +
                            "4. Ve a 'Get API Key' y crea una nueva clave\n" +
                            "5. Copia la clave y pégala aquí",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

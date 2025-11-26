# 🤖 Guía Completa: Funcionamiento e Implementación del Chatbot NutriAI

## 📋 Tabla de Contenidos

1. [Visión General](#visión-general)
2. [Arquitectura del Sistema](#arquitectura-del-sistema)
3. [Componentes Principales](#componentes-principales)
4. [Flujo de Datos](#flujo-de-datos)
5. [Implementación Técnica Detallada](#implementación-técnica-detallada)
6. [Modelos de Datos](#modelos-de-datos)
7. [Flujos de Conversación](#flujos-de-conversación)
8. [Integración con Gemini AI](#integración-con-gemini-ai)
9. [Gestión de Estado](#gestión-de-estado)
10. [Casos de Uso Específicos](#casos-de-uso-específicos)

---

## 🎯 Visión General

El **Chatbot NutriAI** es un asistente virtual inteligente integrado en una aplicación Android de nutrición. Su propósito es ayudar a los usuarios a:

- **Responder preguntas nutricionales** de forma personalizada
- **Gestionar su rutina alimentaria** (agregar, modificar, eliminar alimentos)
- **Generar rutinas nutricionales personalizadas** basadas en su perfil
- **Consultar rutinas** de días específicos
- **Obtener recomendaciones** adaptadas a sus objetivos y restricciones

### Tecnologías Utilizadas

- **Frontend (Android)**: Kotlin, Jetpack Compose, Coroutines, StateFlow
- **Backend (Spring Boot)**: Java, REST API, PostgreSQL
- **Inteligencia Artificial**: Google Gemini API (gemini-2.5-flash)
- **Comunicación**: HTTP/REST, JSON

---

## 🏗️ Arquitectura del Sistema

### Diagrama de Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│                    CAPA DE PRESENTACIÓN                      │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ChatbotScreen (UI - Jetpack Compose)                │   │
│  │  - Muestra conversación                              │   │
│  │  - Input de mensajes                                 │   │
│  │  - Botones de acción rápida                          │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    CAPA DE LÓGICA                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ChatbotViewModel (MVVM)                             │   │
│  │  - Gestiona estado de la UI                          │   │
│  │  - Coordina servicios                                │   │
│  │  - Maneja StateFlow para reactividad                 │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    CAPA DE SERVICIOS                         │
│  ┌──────────────────┐  ┌────────────────────────────────┐  │
│  │ ChatbotService   │  │ GeminiNutriAIService           │  │
│  │ - Lógica de      │  │ - Comunicación con Gemini API  │  │
│  │   conversación   │  │ - Generación de respuestas     │  │
│  │ - Flujos paso    │  │ - Personalización de prompts   │  │
│  │   a paso         │  │                                │  │
│  └──────────────────┘  └────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    CAPA DE RED                               │
│  ┌──────────────────┐  ┌────────────────────────────────┐  │
│  │ Backend API      │  │ Gemini API                     │  │
│  │ (Spring Boot)    │  │ (Google Cloud)                 │  │
│  │ - Sesiones       │  │ - Generación de texto          │  │
│  │ - Interacciones  │  │ - Procesamiento de lenguaje    │  │
│  │ - Modificaciones │  │                                │  │
│  └──────────────────┘  └────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────────┐
│                    CAPA DE DATOS                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  PostgreSQL Database                                 │   │
│  │  - Usuarios                                          │   │
│  │  - Rutinas nutricionales                             │   │
│  │  - Sesiones de chatbot                               │   │
│  │  - Interacciones                                     │   │
│  │  - Modificaciones de rutina                          │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 🧩 Componentes Principales

### 1. Frontend (Android - Kotlin)

#### 1.1. ChatbotScreen.kt
**Ubicación**: `FrontEndProyectoApp/app/src/main/java/com/example/frontendproyectoapp/screen/ChatbotScreen.kt`

**Responsabilidades**:
- Renderizar la interfaz de usuario del chatbot
- Mostrar mensajes en burbujas de chat
- Gestionar el input del usuario
- Mostrar indicadores de carga
- Botones de acción rápida

**Componentes principales**:
```kotlin
@Composable
fun ChatbotDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    isLoading: Boolean
)

@Composable
fun ChatBubble(message: ChatMessage) // Muestra un mensaje individual

@Composable
fun QuickActions(onSendMessage: (String) -> Unit) // Botones de acción rápida
```

#### 1.2. ChatbotViewModel.kt
**Ubicación**: `FrontEndProyectoApp/app/src/main/java/com/example/frontendproyectoapp/viewModel/ChatbotViewModel.kt`

**Responsabilidades**:
- Gestionar el estado de la conversación
- Coordinar entre la UI y los servicios
- Manejar el ciclo de vida de la sesión
- Gestionar el perfil del usuario y la rutina actual
- Detectar intenciones del usuario

**Estado gestionado**:
```kotlin
private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
private val _isLoading = MutableStateFlow(false)
private val _currentSession = MutableStateFlow<SesionChatbot?>(null)
private val _userProfile = MutableStateFlow<Usuario?>(null)
private val _currentRoutine = MutableStateFlow<List<RegistroAlimentoSalida>>(emptyList())
private val _apiStatus = MutableStateFlow<ApiStatus>(ApiStatus.UNKNOWN)
```

**Métodos principales**:
- `startNewSession(userId: Long)`: Inicia una nueva sesión de chat
- `sendMessage(message: String, userProfile: Usuario?, currentRoutine: List<RegistroAlimentoSalida>?)`: Envía un mensaje al chatbot
- `endSession()`: Cierra la sesión actual
- `determineIntent(message: String)`: Detecta la intención del usuario

#### 1.3. ChatbotService.kt
**Ubicación**: `FrontEndProyectoApp/app/src/main/java/com/example/frontendproyectoapp/service/ChatbotService.kt`

**Responsabilidades**:
- Implementar la lógica de conversación paso a paso
- Gestionar flujos de modificación de rutina
- Generar rutinas personalizadas
- Procesar comandos específicos (agregar, cambiar, eliminar)
- Coordinar con GeminiNutriAIService para respuestas generales

**Conceptos clave**:

1. **ConversationState**: Mantiene el estado de la conversación actual
```kotlin
data class ConversationState(
    val userId: Long,
    val currentStep: NutriAIStep,  // Paso actual en el flujo
    val foodName: String? = null,
    val quantity: String? = null,
    val mealTime: String? = null,
    // ... más campos
)
```

2. **NutriAIStep**: Enum que define los pasos posibles en un flujo
```kotlin
enum class NutriAIStep {
    IDLE,                    // Sin flujo activo
    ADD_FOOD_NAME,          // Esperando nombre del alimento
    ADD_FOOD_QUANTITY,      // Esperando cantidad
    ADD_FOOD_MEAL_TIME,     // Esperando momento del día
    CHANGE_ORIGINAL_FOOD,   // Esperando alimento a cambiar
    // ... más pasos
}
```

**Flujo de procesamiento**:
1. Recibe el mensaje del usuario
2. Verifica si hay un flujo activo (ConversationState)
3. Si hay flujo activo → procesa según el paso actual
4. Si no hay flujo → detecta comandos específicos o envía a Gemini
5. Retorna respuesta apropiada

#### 1.4. GeminiNutriAIService.kt
**Ubicación**: `FrontEndProyectoApp/app/src/main/java/com/example/frontendproyectoapp/service/GeminiNutriAIService.kt`

**Responsabilidades**:
- Comunicarse con la API de Google Gemini
- Construir prompts personalizados con el perfil del usuario
- Generar respuestas inteligentes
- Manejar errores de la API

**Configuración**:
```kotlin
// Configuración en GeminiConfig.kt
const val BASE_URL = "https://generativelanguage.googleapis.com/v1"
const val MODEL_NAME = "gemini-2.5-flash"
```

**Método principal**:
```kotlin
suspend fun generateResponse(
    userMessage: String,
    userProfile: Usuario? = null,
    currentRoutine: List<RegistroAlimentoSalida>? = null
): String
```

**Proceso de generación**:
1. Construye el contexto del usuario (perfil + rutina)
2. Crea un prompt personalizado con el system prompt
3. Envía request HTTP POST a Gemini API
4. Parsea la respuesta JSON
5. Extrae el texto generado
6. Retorna la respuesta

#### 1.5. GeminiConfig.kt
**Ubicación**: `FrontEndProyectoApp/app/src/main/java/com/example/frontendproyectoapp/config/GeminiConfig.kt`

**Responsabilidades**:
- Gestionar la API key de Gemini
- Validar la configuración
- Almacenar configuración en SharedPreferences

---

### 2. Backend (Spring Boot - Java)

#### 2.1. InteraccionChatbotController.java
**Ubicación**: `Proyecto Aplicación/Proyecto/src/main/java/com/example/Proyecto/Controller/InteraccionChatbotController.java`

**Responsabilidades**:
- Exponer endpoints REST para gestionar interacciones
- CRUD de interacciones del chatbot

**Endpoints**:
- `GET /api/InteraccionChatbot/listar`: Lista todas las interacciones
- `GET /api/InteraccionChatbot/buscar/{id}`: Busca una interacción por ID
- `POST /api/InteraccionChatbot/guardar`: Guarda una nueva interacción
- `PUT /api/InteraccionChatbot/actualizar/{id}`: Actualiza una interacción
- `DELETE /api/InteraccionChatbot/eliminar/{id}`: Elimina una interacción

#### 2.2. InteraccionChatbotService.java
**Ubicación**: `Proyecto Aplicación/Proyecto/src/main/java/com/example/Proyecto/Service/InteraccionChatbotService.java`

**Responsabilidades**:
- Lógica de negocio para interacciones
- Validaciones
- Consultas al repositorio

**Métodos especiales**:
- `HistorialInteracciones(id_sesion)`: Obtiene historial de una sesión
- `obtenerRespuestaPorTipo(id_sesion, tipoConsulta)`: Filtra por tipo
- `obtenerPorFechaYTipo(...)`: Filtra por fecha y tipo

#### 2.3. ModificacionRutinaChatbotController.java
**Ubicación**: `Proyecto Aplicación/Proyecto/src/main/java/com/example/Proyecto/Controller/ModificacionRutinaChatbotController.java`

**Responsabilidades**:
- Gestionar modificaciones de rutina realizadas desde el chatbot
- Registrar cambios (agregar, eliminar, modificar)

---

## 🔄 Flujo de Datos

### Flujo Completo: Usuario envía mensaje

```
1. Usuario escribe mensaje en ChatbotScreen
   ↓
2. ChatbotScreen llama a ChatbotViewModel.sendMessage()
   ↓
3. ChatbotViewModel:
   - Agrega mensaje del usuario a _messages (feedback inmediato)
   - Valida API key
   - Detecta intención (determineIntent)
   - Crea ChatbotRequest
   ↓
4. ChatbotViewModel llama a ChatbotService.sendMessage()
   ↓
5. ChatbotService:
   - Verifica si hay flujo activo (ConversationState)
   - Si hay flujo → procesa según paso actual
   - Si no hay flujo:
     a) Detecta comandos específicos (agregar, cambiar, ver rutina, etc.)
     b) Si es comando → procesa localmente
     c) Si no es comando → llama a GeminiNutriAIService
   ↓
6. GeminiNutriAIService (si aplica):
   - Construye contexto (perfil + rutina)
   - Crea prompt personalizado
   - Envía HTTP POST a Gemini API
   - Recibe y parsea respuesta
   ↓
7. ChatbotService retorna ChatbotResponse
   ↓
8. ChatbotViewModel:
   - Agrega respuesta a _messages
   - Actualiza _isLoading = false
   ↓
9. ChatbotScreen se actualiza automáticamente (StateFlow)
   ↓
10. Usuario ve la respuesta
```

### Flujo Específico: Agregar alimento

```
1. Usuario: "agregar alimento"
   ↓
2. ChatbotService detecta comando "agregar"
   ↓
3. Cambia estado a NutriAIStep.ADD_SELECT_CATEGORY
   ↓
4. Obtiene categorías de alimentos del repositorio
   ↓
5. Responde: "Selecciona una categoría: [lista]"
   ↓
6. Usuario: "Frutas"
   ↓
7. ChatbotService:
   - Cambia a NutriAIStep.ADD_SELECT_CATEGORY
   - Obtiene alimentos de la categoría "Frutas"
   - Cambia a NutriAIStep.ADD_SHOW_FOODS
   ↓
8. Responde: "Alimentos disponibles: [lista]"
   ↓
9. Usuario: "Manzana"
   ↓
10. ChatbotService:
    - Cambia a NutriAIStep.ADD_SELECT_FOOD
    - Obtiene unidades válidas para "Manzana"
    - Cambia a NutriAIStep.ADD_SELECT_UNIT
    ↓
11. Responde: "¿Qué cantidad? Unidades: [lista]"
    ↓
12. Usuario: "2 unidades"
    ↓
13. ChatbotService:
    - Cambia a NutriAIStep.ADD_SELECT_FOOD_QUANTITY
    - Cambia a NutriAIStep.ADD_SELECT_MEAL_TIME
    ↓
14. Responde: "¿En qué momento del día?"
    ↓
15. Usuario: "Desayuno"
    ↓
16. ChatbotService:
    - Cambia a NutriAIStep.ADD_CONFIRMATION
    - Guarda en base de datos (si está conectado al backend)
    - Notifica actualización de rutina
    ↓
17. Responde: "✅ Manzana agregada al desayuno"
    ↓
18. Cambia estado a NutriAIStep.IDLE
```

---

## 💻 Implementación Técnica Detallada

### 1. Gestión de Estado con StateFlow

El ViewModel usa `StateFlow` para mantener el estado reactivo:

```kotlin
// Estado privado mutable
private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

// Estado público inmutable (solo lectura)
val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
```

**Ventajas**:
- Reactividad automática: la UI se actualiza cuando cambia el estado
- Thread-safe: StateFlow es seguro para uso concurrente
- Integración con Compose: fácil de observar con `collectAsState()`

### 2. Coroutines para Operaciones Asíncronas

Todas las operaciones de red y procesamiento se hacen en coroutines:

```kotlin
fun sendMessage(message: String) {
    viewModelScope.launch {  // Lanza coroutine en el scope del ViewModel
        try {
            _isLoading.value = true
            val response = chatbotService.sendMessage(...)
            // Procesar respuesta
        } catch (e: Exception) {
            // Manejar error
        } finally {
            _isLoading.value = false
        }
    }
}
```

**Beneficios**:
- No bloquea el hilo principal (UI)
- Manejo de errores simplificado
- Cancelación automática cuando el ViewModel se destruye

### 3. Detección de Intenciones

El sistema detecta intenciones usando palabras clave:

```kotlin
private fun determineIntent(message: String): TipoIntento {
    val lowerMessage = message.lowercase()
    
    return when {
        lowerMessage.contains("agregar") || 
        lowerMessage.contains("añadir") -> TipoIntento.Modificar_Rutina
        
        lowerMessage.contains("ver rutina") || 
        lowerMessage.contains("mostrar rutina") -> TipoIntento.Modificar_Rutina
        
        lowerMessage.contains("calorías") || 
        lowerMessage.contains("nutricional") -> TipoIntento.Pregunta_Nutricional
        
        else -> TipoIntento.Otros
    }
}
```

### 4. Sistema de Flujos Paso a Paso

El `ChatbotService` mantiene un mapa de estados de conversación:

```kotlin
private val conversationStates = mutableMapOf<Long, ConversationState>()

// Obtener o crear estado
val userId = userProfile?.idUsuario ?: 1L
val currentState = conversationStates[userId] 
    ?: ConversationState(userId, NutriAIStep.IDLE)

// Actualizar estado
conversationStates[userId] = currentState.copy(
    currentStep = NutriAIStep.ADD_FOOD_NAME,
    foodName = "Manzana"
)
```

**Ventajas**:
- Permite conversaciones multi-turno
- Mantiene contexto entre mensajes
- Soporta múltiples usuarios simultáneos

### 5. Construcción de Prompts para Gemini

El `GeminiNutriAIService` construye prompts personalizados:

```kotlin
private fun buildContextPrompt(
    userProfile: Usuario?, 
    currentRoutine: List<RegistroAlimentoSalida>?
): String {
    val profileInfo = userProfile?.let { user ->
        """
        PERFIL COMPLETO DEL USUARIO:
        - Nombre: ${user.nombre}
        - Peso: ${user.peso} kg
        - Altura: ${user.altura} cm
        - Objetivo: ${user.objetivosSalud}
        ...
        """
    } ?: "No hay información del perfil"
    
    val routineInfo = currentRoutine?.let { routine ->
        """
        RUTINA REAL DEL USUARIO:
        ${routine.groupBy { it.momentoDelDia }...}
        """
    } ?: "No hay rutina disponible"
    
    return "$profileInfo\n\n$routineInfo"
}
```

### 6. Manejo de Errores

El sistema tiene múltiples capas de manejo de errores:

```kotlin
// En ViewModel
try {
    val response = chatbotService.sendMessage(...)
} catch (e: Exception) {
    _error.value = "Error al enviar mensaje: ${e.message}"
    // Agregar mensaje de error al chat
}

// En GeminiNutriAIService
if (response.isSuccessful) {
    // Procesar respuesta
} else {
    val errorBody = response.body?.string()
    // Parsear error específico de Gemini
    throw Exception("Gemini API Error: $errorMessage")
}
```

---

## 📊 Modelos de Datos

### Frontend (Kotlin)

#### ChatMessage
```kotlin
data class ChatMessage(
    val id: String = "",
    val message: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val tipoIntento: TipoIntento? = null,
    val tipoAccion: TipoAccion? = null
)
```

#### ChatbotRequest
```kotlin
data class ChatbotRequest(
    val mensaje: String,
    val idSesion: Long? = null,
    val tipoIntento: TipoIntento? = null
)
```

#### ChatbotResponse
```kotlin
data class ChatbotResponse(
    val respuesta: String,
    val tipoIntento: TipoIntento,
    val tipoAccion: TipoAccion? = null,
    val idInteraccion: Long? = null,
    val tema: String? = null
)
```

#### SesionChatbot
```kotlin
data class SesionChatbot(
    val idSesion: Long? = null,
    val inicioSesion: Timestamp? = null,
    val finSesion: Timestamp? = null,
    val mensajes: String? = null,
    val retroalimentacion: String? = null,
    val idUsuario: Long? = null
)
```

#### ConversationState
```kotlin
data class ConversationState(
    val userId: Long,
    val currentStep: NutriAIStep,
    val foodName: String? = null,
    val quantity: String? = null,
    val unit: String? = null,
    val mealTime: String? = null,
    val originalFood: String? = null,
    val newFood: String? = null,
    val routineCount: Int = 0,
    val availableCategories: List<String>? = null,
    val selectedCategory: String? = null,
    val availableFoods: List<Alimento>? = null,
    val selectedFood: Alimento? = null,
    val validUnits: List<String>? = null,
    val currentRoutineFoods: List<RegistroAlimentoSalida>? = null
)
```

### Backend (Java)

#### InteraccionChatbot
```java
@Entity
public class InteraccionChatbot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long idInteraccion;
    
    private String consultaUsuario;
    private String respuestaIA;
    private Timestamp timestamp;
    private String tipoIntento;
    private String tipoAccion;
    private String tema;
    
    // Relaciones
    @ManyToOne
    private SesionChatbot sesionChatbot;
}
```

#### ModificacionRutinaChatbot
```java
@Entity
public class ModificacionRutinaChatbot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long idModificacion;
    
    private Date fecha;
    private String accion;  // "AGREGAR", "ELIMINAR", "MODIFICAR"
    private String comida;
    private String motivo;
    
    // Relaciones
    @ManyToOne
    private SesionChatbot sesionChatbot;
}
```

---

## 💬 Flujos de Conversación

### Flujo 1: Pregunta Nutricional General

```
Usuario: "¿Qué son las proteínas?"
   ↓
ChatbotViewModel.determineIntent() → TipoIntento.Pregunta_Nutricional
   ↓
ChatbotService → No hay flujo activo, no es comando específico
   ↓
GeminiNutriAIService.generateResponse():
   - Construye prompt con perfil del usuario
   - Envía a Gemini API
   - Recibe respuesta personalizada
   ↓
Respuesta: "Las proteínas son macronutrientes esenciales que..."
```

### Flujo 2: Ver Rutina del Día Actual

```
Usuario: "ver rutina"
   ↓
ChatbotService detecta comando "ver rutina"
   ↓
generateSpecificResponse():
   - Obtiene rutina actual del parámetro currentRoutine
   - Formatea por momentos del día
   - Si está vacía, indica que no hay datos
   ↓
Respuesta: "📅 Tu rutina de hoy:\n\n🍳 Desayuno:\n- Avena..."
```

### Flujo 3: Ver Rutina de Fecha Específica

```
Usuario: "ver rutina 2025-01-15"
   ↓
ChatbotService.extractDateFromMessage() → "2025-01-15"
   ↓
generateRoutineResponse(userProfile, currentRoutine, "2025-01-15"):
   - Busca rutina de esa fecha (requiere conexión al backend)
   - Formatea la respuesta
   ↓
Respuesta: "📅 Tu rutina del 15 de enero de 2025:\n\n..."
```

### Flujo 4: Generar Rutina Personalizada

```
Usuario: "generar rutina"
   ↓
ChatbotService detecta comando "generar rutina"
   ↓
Responde: "¿Quieres generar una rutina personalizada? Responde 'Sí' o 'Generar'"
   ↓
Usuario: "Sí"
   ↓
ChatbotService.generatePersonalizedRoutine():
   - Analiza perfil del usuario (peso, altura, objetivos, actividad)
   - Calcula necesidades calóricas
   - Genera distribución de macronutrientes
   - Sugiere alimentos por categoría
   - Formatea como rutina completa
   ↓
Respuesta: "🎯 Tu rutina personalizada:\n\n📊 Necesidades diarias:\n..."
```

### Flujo 5: Agregar Alimento (Nuevo Flujo con Categorías)

```
Usuario: "agregar alimento"
   ↓
ChatbotService detecta comando específico
   ↓
Obtiene categorías: ["Frutas", "Verduras", "Proteínas", ...]
   ↓
Cambia estado a ADD_SELECT_CATEGORY
   ↓
Responde: "Puedes agregar alimentos de: Frutas, Verduras, Proteínas..."
   ↓
Usuario: "Frutas"
   ↓
Obtiene alimentos de categoría "Frutas"
   ↓
Cambia estado a ADD_SHOW_FOODS
   ↓
Responde: "Alimentos disponibles: Manzana, Plátano, Naranja..."
   ↓
Usuario: "Manzana"
   ↓
Obtiene unidades válidas para "Manzana": ["unidad", "gramos"]
   ↓
Cambia estado a ADD_SELECT_UNIT
   ↓
Responde: "¿Qué cantidad? Unidades: unidad, gramos"
   ↓
Usuario: "2 unidades"
   ↓
Cambia estado a ADD_SELECT_MEAL_TIME
   ↓
Responde: "¿En qué momento del día? (Desayuno, Almuerzo, Cena, Snack)"
   ↓
Usuario: "Desayuno"
   ↓
Guarda en base de datos (si backend disponible)
   ↓
Notifica actualización de rutina
   ↓
Cambia estado a IDLE
   ↓
Responde: "✅ Manzana (2 unidades) agregada al Desayuno"
```

### Flujo 6: Cambiar Alimento

```
Usuario: "cambiar alimento"
   ↓
ChatbotService detecta comando
   ↓
Obtiene alimentos actuales de la rutina
   ↓
Cambia estado a CHANGE_SELECT_ORIGINAL_FOOD
   ↓
Responde: "Alimentos en tu rutina:\n- Manzana - Desayuno\n- Pollo - Almuerzo\n¿Cuál quieres cambiar?"
   ↓
Usuario: "Manzana"
   ↓
Cambia estado a CHANGE_SELECT_CATEGORY
   ↓
Muestra categorías disponibles
   ↓
Usuario: "Frutas"
   ↓
Muestra alimentos de la categoría
   ↓
Usuario: "Plátano"
   ↓
Pregunta cantidad y momento del día
   ↓
Confirma y guarda
   ↓
Responde: "✅ Manzana cambiada por Plátano en Desayuno"
```

---

## 🤖 Integración con Gemini AI

### Configuración

```kotlin
// GeminiConfig.kt
object GeminiConfig {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1"
    const val MODEL_NAME = "gemini-2.5-flash"
    val API_KEY: String // Se obtiene de SharedPreferences o valor por defecto
}
```

### Request a Gemini API

```kotlin
val requestBody = JSONObject().apply {
    // Estructura de contents
    val contentsArray = JSONArray()
    val contentObject = JSONObject()
    val partsArray = JSONArray()
    val partObject = JSONObject()
    partObject.put("text", personalizedPrompt)
    partsArray.put(partObject)
    contentObject.put("parts", partsArray)
    contentsArray.put(contentObject)
    put("contents", contentsArray)
    
    // Configuración de generación
    val generationConfig = JSONObject()
    generationConfig.put("temperature", 0.7)
    generationConfig.put("maxOutputTokens", 2000)
    put("generationConfig", generationConfig)
}

val request = Request.Builder()
    .url("${BASE_URL}/models/${MODEL_NAME}:generateContent?key=${API_KEY}")
    .post(requestBody.toRequestBody("application/json".toMediaType()))
    .build()
```

### System Prompt

El system prompt define la personalidad y capacidades del chatbot:

```kotlin
private val systemPrompt = """
    🧠 Eres NutriAI, un asistente virtual inteligente de nutrición...
    
    🎯 OBJETIVO GENERAL:
    Tu misión es ayudar al usuario a mejorar su alimentación...
    
    🧩 FUNCIONES PRINCIPALES:
    1. 📚 RESPONDER PREGUNTAS NUTRICIONALES
    2. 🍎 SUGERIR ALIMENTOS ADECUADOS
    3. 📅 MOSTRAR RUTINA NUTRICIONAL
    4. ➕ AGREGAR ALIMENTOS A LA RUTINA
    ...
"""
```

### Personalización de Prompts

Cada request incluye:
1. **System Prompt**: Instrucciones generales
2. **Contexto del Usuario**: Perfil completo (peso, altura, objetivos, etc.)
3. **Rutina Actual**: Alimentos registrados
4. **Mensaje del Usuario**: La pregunta o comando

---

## 🔄 Gestión de Estado

### Estados de la Conversación

El sistema mantiene múltiples estados:

1. **Estado de la UI** (en ViewModel):
   - `_messages`: Lista de mensajes
   - `_isLoading`: Si está procesando
   - `_currentSession`: Sesión actual
   - `_userProfile`: Perfil del usuario
   - `_currentRoutine`: Rutina actual
   - `_apiStatus`: Estado de la API

2. **Estado de Conversación** (en ChatbotService):
   - `conversationStates`: Mapa de estados por usuario
   - Cada estado contiene el paso actual y datos temporales

### Ciclo de Vida de una Sesión

```
1. Usuario abre chatbot
   → ChatbotViewModel.startNewSession()
   → ChatbotService.createSession()
   → _currentSession.value = nueva sesión

2. Usuario envía mensajes
   → Se procesan y se agregan a _messages

3. Usuario cierra chatbot
   → ChatbotViewModel.endSession()
   → ChatbotService.endSession()
   → _currentSession.value = null
   → conversationStates.remove(userId) // Limpia estado
```

---

## 📝 Casos de Uso Específicos

### Caso 1: Usuario nuevo sin perfil completo

```
Situación: Usuario no ha completado su perfil (peso, altura, etc.)

Flujo:
1. ChatbotService detecta valores por defecto
2. Usa valores por defecto razonables (peso: 70kg, altura: 170cm)
3. Gemini genera respuestas genéricas pero útiles
4. Sugiere completar el perfil para personalización mejor

Código relevante:
```kotlin
private fun isDefaultValue(value: String): Boolean {
    return value.contains("Usuario") || value.isBlank() || 
           value == "0.0" || value == "0"
}
```

### Caso 2: Rutina vacía

```
Situación: Usuario pregunta por su rutina pero no tiene alimentos registrados

Flujo:
1. ChatbotService recibe currentRoutine vacía
2. generateSpecificResponse() detecta lista vacía
3. Responde: "No tienes alimentos registrados para hoy. 
              ¿Te gustaría agregar algunos?"

Código relevante:
```kotlin
if (routine.isEmpty()) {
    return "No tienes alimentos registrados para hoy..."
}
```

### Caso 3: Error de conexión con Gemini

```
Situación: La API de Gemini no responde o hay error de red

Flujo:
1. GeminiNutriAIService captura excepción
2. Identifica tipo de error (401, 403, 429, timeout, etc.)
3. Lanza excepción con mensaje descriptivo
4. ChatbotViewModel captura y muestra mensaje de error
5. Agrega mensaje de error al chat

Código relevante:
```kotlin
catch (e: Exception) {
    val errorMessage = when {
        e.message?.contains("401") == true -> "API Key inválida"
        e.message?.contains("429") == true -> "Límite excedido"
        // ...
    }
    throw Exception("Gemini API Error: $errorMessage")
}
```

### Caso 4: Múltiples usuarios simultáneos

```
Situación: Varios usuarios usan el chatbot al mismo tiempo

Solución:
- Cada usuario tiene su propio ConversationState
- Se identifica por userId
- Los estados se mantienen en un mapa: Map<Long, ConversationState>

Código relevante:
```kotlin
private val conversationStates = mutableMapOf<Long, ConversationState>()

val userId = userProfile?.idUsuario ?: 1L
val currentState = conversationStates[userId] 
    ?: ConversationState(userId, NutriAIStep.IDLE)
```

---

## 🔍 Puntos Clave de la Implementación

### 1. Separación de Responsabilidades

- **UI (ChatbotScreen)**: Solo renderiza
- **ViewModel**: Gestiona estado y coordina
- **Service**: Lógica de negocio
- **AI Service**: Comunicación con IA

### 2. Reactividad

- Uso de StateFlow para actualizaciones automáticas
- La UI se actualiza cuando cambia el estado
- No necesita llamadas manuales a actualizar UI

### 3. Manejo de Errores Robusto

- Múltiples capas de try-catch
- Mensajes de error descriptivos
- Fallbacks cuando falla la IA

### 4. Personalización

- Prompts adaptados al perfil del usuario
- Respuestas contextualizadas
- Consideración de objetivos y restricciones

### 5. Flujos Conversacionales

- Sistema de estados para conversaciones multi-turno
- Mantiene contexto entre mensajes
- Guía al usuario paso a paso

---

## 🚀 Mejoras Futuras Posibles

1. **Persistencia de Conversaciones**: Guardar historial en base de datos
2. **Análisis de Sentimiento**: Detectar emociones del usuario
3. **Sugerencias Proactivas**: Ofrecer ayuda sin que el usuario pregunte
4. **Multilenguaje**: Soporte para múltiples idiomas
5. **Reconocimiento de Voz**: Entrada por voz además de texto
6. **Análisis de Imágenes**: Subir fotos de comida para análisis
7. **Integración con Wearables**: Datos de actividad física en tiempo real

---

## 📚 Referencias de Código

### Archivos Principales

**Frontend**:
- `ChatbotScreen.kt`: UI del chatbot
- `ChatbotViewModel.kt`: Lógica de presentación
- `ChatbotService.kt`: Lógica de negocio y flujos
- `GeminiNutriAIService.kt`: Integración con IA
- `GeminiConfig.kt`: Configuración de API

**Backend**:
- `InteraccionChatbotController.java`: Endpoints REST
- `InteraccionChatbotService.java`: Lógica de negocio
- `ModificacionRutinaChatbotController.java`: Gestión de modificaciones

**Modelos**:
- `ChatMessage.kt`: Modelo de mensaje
- `ChatbotRequest.kt`: Request al servicio
- `ChatbotResponse.kt`: Respuesta del servicio
- `SesionChatbot.kt`: Modelo de sesión

---

## ✅ Conclusión

El Chatbot NutriAI es un sistema complejo que combina:

1. **Arquitectura MVVM** para separación de responsabilidades
2. **StateFlow** para reactividad
3. **Coroutines** para operaciones asíncronas
4. **Flujos conversacionales** para interacciones guiadas
5. **Integración con IA** para respuestas inteligentes
6. **Personalización** basada en perfil del usuario

El sistema está diseñado para ser:
- **Escalable**: Soporta múltiples usuarios
- **Mantenible**: Código organizado y documentado
- **Robusto**: Manejo de errores en múltiples capas
- **Extensible**: Fácil agregar nuevas funcionalidades

---

*Documento creado para explicar el funcionamiento completo del Chatbot NutriAI desde cero.*


# 🧠 NutriAI - Guía de Uso Completa

## 🎯 Descripción General

NutriAI es un asistente virtual inteligente de nutrición diseñado para ofrecer orientación alimentaria personalizada y ayudar al usuario a gestionar su rutina nutricional diaria.

## 🎭 Características Principales

### 1. 📚 Respuesta a Preguntas Nutricionales
- Explica conceptos de forma sencilla y práctica
- Responde dudas sobre calorías, macronutrientes, vitaminas, minerales
- Acepta preguntas con errores ortográficos o lenguaje informal
- Proporciona ejemplos prácticos

### 2. 🍎 Sugerencia de Alimentos
- Analiza el perfil del usuario (edad, género, objetivos, alergias)
- Recomienda alimentos saludables adaptados al usuario
- Ofrece alternativas según estilo de vida (vegano, sin gluten, deportivo)

### 3. 📅 Gestión de Rutina Nutricional
- Muestra rutina del día con comidas organizadas
- Permite agregar alimentos a comidas específicas
- Permite eliminar alimentos de la rutina
- Permite cambiar alimentos por equivalentes
- Registra todas las modificaciones realizadas

## 🔧 Implementación Técnica

### Archivos Modificados

1. **GroqNutriAIService.kt**
   - Sistema de prompt mejorado con capacidades conversacionales avanzadas
   - Manejo de contexto del usuario y rutina actual
   - Respuestas personalizadas basadas en perfil

2. **ChatbotViewModel.kt**
   - Nuevas funcionalidades para gestión de rutina
   - Sistema de seguimiento de modificaciones
   - Detección de intenciones mejorada

3. **ChatbotService.kt**
   - Respuestas específicas para gestión de rutina
   - Manejo de operaciones de alimentos
   - Fallback responses mejorados

4. **RoutineModification.kt** (Nuevo)
   - Modelo de datos para seguimiento de modificaciones
   - Tipos de acciones: ADD, REMOVE, CHANGE, VIEW_ROUTINE

### Nuevas Funcionalidades

#### Gestión de Rutina
```kotlin
// Agregar alimento a la rutina
chatbotViewModel.addFoodToRoutine("manzana", "desayuno", "1 unidad")

// Eliminar alimento de la rutina
chatbotViewModel.removeFoodFromRoutine("manzana", "desayuno")

// Cambiar alimento en la rutina
chatbotViewModel.changeFoodInRoutine("arroz blanco", "arroz integral", "almuerzo")

// Obtener historial de modificaciones
val history = chatbotViewModel.getModificationHistory()

// Actualizar rutina actual
chatbotViewModel.updateCurrentRoutine(nuevaRutina)
```

#### Detección de Intenciones Mejorada
- **Modificar_Rutina**: Agregar, eliminar, cambiar, ver rutina
- **Pregunta_Nutricional**: Calorías, macronutrientes, vitaminas, etc.
- **Otros**: Saludos, agradecimientos, consultas generales

## 💬 Ejemplos de Conversación

### Preguntas Nutricionales
```
Usuario: "¿Qué son los carbohidratos?"
NutriAI: "Los carbohidratos son la principal fuente de energía para tu cuerpo. Se dividen en simples (azúcares) y complejos (almidones). Los carbohidratos complejos como arroz integral, avena y quinoa son mejores porque te dan energía sostenida. ¿Te gustaría saber más sobre cómo incluirlos en tu dieta?"
```

### Gestión de Rutina
```
Usuario: "Quiero ver mi rutina de hoy"
NutriAI: "👋 ¡Hola! Aquí tienes tu rutina nutricional de hoy:

🌅 DESAYUNO:
- Avena con frutas
- Café o té

🌞 ALMUERZO:
- Pollo a la plancha
- Arroz integral
- Ensalada verde

🌙 CENA:
- Pescado al vapor
- Verduras al vapor
- Quinoa

🍎 SNACKS:
- Frutas frescas
- Frutos secos

¿Te gustaría modificar algo en tu rutina?"
```

### Operaciones de Alimentos
```
Usuario: "Quiero agregar una manzana al desayuno"
NutriAI: "👋 ¡Hola! ¡Perfecto! He agregado una manzana a tu desayuno. ¿Te gustaría agregar algo más o modificar otra comida?"

Usuario: "Elimina el arroz del almuerzo"
NutriAI: "👋 ¡Hola! He eliminado el arroz de tu almuerzo. ¿Te gustaría reemplazarlo por otro carbohidrato como quinoa o batata?"

Usuario: "Cambia el pollo por pescado en el almuerzo"
NutriAI: "👋 ¡Hola! He cambiado el pollo por pescado en tu almuerzo. El pescado es una excelente fuente de proteína y omega-3. ¿Hay algo más que te gustaría modificar?"
```

## 🎨 Características del Sistema

### Tono y Personalidad
- **Amigable**: Usa emojis apropiados y lenguaje cercano
- **Respetuoso**: Mantiene un tono profesional pero accesible
- **Empático**: Demuestra comprensión de las necesidades del usuario
- **Motivacional**: Ofrece apoyo y aliento en cada interacción

### Manejo de Errores
- Pide aclaración cuando no entiende algo
- Ofrece alternativas cuando no puede cumplir una solicitud
- Mantiene un tono positivo y de ayuda
- Redirige suavemente temas no relacionados con nutrición

### Personalización
- Usa el nombre del usuario cuando está disponible
- Adapta recomendaciones al perfil del usuario
- Considera objetivos, restricciones y nivel de actividad
- Proporciona ejemplos específicos según el perfil

## 📊 Flujo de Trabajo

1. **Inicialización**: El usuario inicia una sesión con NutriAI
2. **Detección de Intención**: El sistema analiza el mensaje del usuario
3. **Procesamiento**: Se genera una respuesta personalizada usando Groq API
4. **Gestión de Rutina**: Si es necesario, se actualiza la rutina del usuario
5. **Registro**: Se guardan las modificaciones en el historial
6. **Respuesta**: Se devuelve una respuesta contextual y útil

## 🚀 Próximos Pasos

1. **Integración con Backend**: Conectar las operaciones de rutina con la API del backend
2. **Persistencia**: Guardar el historial de modificaciones en la base de datos
3. **Notificaciones**: Implementar recordatorios de comidas
4. **Análisis**: Agregar análisis nutricional de la rutina
5. **Reportes**: Generar reportes de progreso nutricional

## 🔧 Configuración

### Variables de Entorno
- `GROQ_API_KEY`: Clave de API de Groq
- `GROQ_BASE_URL`: URL base de la API de Groq
- `GROQ_MODEL_NAME`: Nombre del modelo a utilizar

### Dependencias
- OkHttp para llamadas HTTP
- Kotlinx Coroutines para programación asíncrona
- Gson para serialización JSON

## 📝 Notas de Desarrollo

- El sistema maneja toda la lógica conversacional internamente
- No depende de instrucciones externas para mantener la conversación
- Mantiene el enfoque en nutrición y salud
- Siempre transmite confianza y acompañamiento
- Registra todas las modificaciones para seguimiento

---

**Desarrollado con ❤️ para mejorar la salud nutricional de los usuarios**

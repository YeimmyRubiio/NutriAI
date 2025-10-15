# 📅 Funcionalidades de Rutina Nutricional - NutriAI

## 🎯 Descripción General

Se han implementado nuevas funcionalidades en el chatbot NutriAI para permitir a los usuarios ver sus rutinas nutricionales diarias y de fechas específicas.

## ✨ Nuevas Funcionalidades

### 1. 📅 Mostrar Rutina Nutricional

**Botón:** "📅 Mostrar rutina nutricional"

**Funcionalidad:**
- Muestra la rutina nutricional del día actual
- Organiza los alimentos por momentos del día (Desayuno, Almuerzo, Cena, Snack)
- Utiliza datos reales del usuario cuando están disponibles
- Proporciona rutina de ejemplo si no hay datos registrados

**Ejemplo de respuesta (con alimentos registrados):**
```
👋 ¡Hola Ana! Aquí tienes tu rutina nutricional de hoy:

🌅 Desayuno:
- Avena con frutas
- Café o té

🌞 Almuerzo:
- Pollo a la plancha
- Arroz integral
- Ensalada verde

🌙 Cena:
- Pescado al vapor
- Verduras al vapor
- Quinoa

🍎 Snacks:
- Frutas frescas
- Frutos secos

¿Te gustaría modificar algo en tu rutina?
```

**Ejemplo de respuesta (sin alimentos registrados):**
```
👋 ¡Hola Ana! Aquí tienes tu rutina nutricional de hoy:

📝 **No has registrado alimentos para hoy**

Para ver tu rutina nutricional, necesitas registrar los alimentos que consumes.

💡 **¿Cómo registrar alimentos?**
1. Ve a la sección 'Buscar Alimentos'
2. Busca el alimento que consumiste
3. Selecciona la cantidad y el momento del día
4. ¡Listo! Ya aparecerá en tu rutina

¿Te gustaría que te ayude a buscar algún alimento específico?
```

### 2. 📆 Mostrar Rutina de Fecha Específica

**Funcionalidad:**
- Permite ver rutinas de días anteriores especificando la fecha
- Acepta múltiples formatos de fecha
- Proporciona ejemplos cuando el usuario no especifica la fecha correctamente

**Formatos de fecha soportados:**
- `"Ver rutina 02/10/2025"` (formato recomendado)
- `"Mostrar rutina del 15 de enero"`
- `"Ver rutina del 20/01/2024"`
- `"Rutina del lunes pasado"`
- `"Mostrar rutina del 2024-01-15"`

**Ejemplo de uso:**
```
Usuario: "Mostrar rutina del 15 de enero"
Chatbot: "👋 ¡Hola Ana! Aquí tienes tu rutina nutricional del 15 de enero:

🌅 Desayuno:
- Avena con frutas
- Café o té

🌞 Almuerzo:
- Pollo a la plancha
- Arroz integral
- Ensalada verde

🌙 Cena:
- Pescado al vapor
- Verduras al vapor
- Quinoa

🍎 Snacks:
- Frutas frescas
- Frutos secos

¿Te gustaría modificar algo en tu rutina?"
```

### 3. 📝 Ejemplos de Especificación de Fechas

Cuando el usuario no especifica la fecha correctamente, el chatbot proporciona ejemplos:

```
👋 ¡Hola Ana! Para mostrarte la rutina de una fecha específica, necesito que me indiques la fecha.

📅 **Ejemplos de cómo especificar la fecha:**
• "Mostrar rutina del 15 de enero"
• "Ver rutina del 20/01/2024"
• "Rutina del lunes pasado"
• "Mostrar rutina del 2024-01-15"

💡 **Tip:** También puedes decir "rutina de ayer" o "rutina del martes"

¿De qué fecha te gustaría ver la rutina?
```

### 4. 📝 Manejo de Rutinas Vacías

**Funcionalidad:**
- Detecta cuando el usuario no ha registrado alimentos
- Proporciona instrucciones claras sobre cómo registrar alimentos
- Ofrece ayuda para buscar alimentos específicos

**Ejemplo de respuesta cuando no hay alimentos registrados:**
```
📝 **No has registrado alimentos para hoy**

Para ver tu rutina nutricional, necesitas registrar los alimentos que consumes.

💡 **¿Cómo registrar alimentos?**
1. Ve a la sección 'Buscar Alimentos'
2. Busca el alimento que consumiste
3. Selecciona la cantidad y el momento del día
4. ¡Listo! Ya aparecerá en tu rutina

¿Te gustaría que te ayude a buscar algún alimento específico?
```

## 🔧 Implementación Técnica

### Archivos Modificados

1. **ChatbotService.kt**
   - Agregada función `generateRoutineResponse()` para generar respuestas de rutina
   - Agregada función `generateRoutineContent()` para formatear el contenido
   - Agregada función `extractDateFromMessage()` para extraer fechas del mensaje
   - Mejorada detección de intenciones para rutinas

2. **ChatbotViewModel.kt**
   - Actualizada función `determineIntent()` para detectar solicitudes de rutina
   - Agregada detección para "mostrar rutina nutricional diaria"
   - Agregada detección para rutinas de fecha específica

3. **GeminiNutriAIService.kt**
   - Actualizado el prompt del sistema para incluir nuevas funcionalidades
   - Agregadas instrucciones para manejo de fechas específicas
   - Mejorada documentación de funcionalidades

### Patrones de Detección

El sistema detecta automáticamente las siguientes frases:

**Para rutina del día actual:**
- "ver rutina"
- "mostrar rutina"
- "mi rutina"
- "rutina de hoy"
- "mostrar rutina nutricional diaria"

**Para rutina de fecha específica:**
- "rutina del [fecha]"
- "rutina de [fecha]"
- "mostrar rutina del [fecha]"
- "ver rutina del [fecha]"

### Formatos de Fecha Soportados

1. **Formato DD/MM/YYYY:** `20/01/2024`
2. **Formato YYYY-MM-DD:** `2024-01-20`
3. **Formato "DD de mes":** `15 de enero`
4. **Días de la semana:** `lunes`, `martes`, etc.
5. **Días relativos:** `ayer`, `hoy`, `mañana`, `pasado`

## 🎨 Experiencia de Usuario

### Flujo de Uso

1. **Usuario hace clic en "📅 Mostrar rutina nutricional"**
   - El chatbot muestra la rutina del día actual
   - Organiza los alimentos por momentos del día
   - Pregunta si desea modificar algo

2. **Usuario solicita rutina de fecha específica**
   - El chatbot muestra instrucciones claras sobre cómo solicitar rutinas
   - El usuario puede escribir "Ver rutina 02/10/2025" para ver rutinas de fechas específicas
   - El sistema detecta automáticamente el formato de fecha y muestra la rutina correspondiente

3. **Usuario solicita rutina de fecha específica (continuación)**
   - El chatbot detecta la fecha en el mensaje
   - Muestra la rutina de esa fecha específica
   - Si no detecta fecha, proporciona ejemplos

3. **Usuario no especifica fecha correctamente**
   - El chatbot explica cómo especificar fechas
   - Proporciona múltiples ejemplos de formatos
   - Pregunta qué fecha desea consultar

### Características de la Respuesta

- **Personalización:** Usa el nombre del usuario en el saludo
- **Organización:** Agrupa alimentos por momentos del día
- **Emojis:** Usa emojis para hacer la interfaz más amigable
- **Interactividad:** Pregunta si desea modificar la rutina
- **Flexibilidad:** Acepta múltiples formatos de fecha

## 🚀 Beneficios

1. **Acceso Rápido:** Los usuarios pueden ver su rutina con un solo clic
2. **Historial:** Pueden consultar rutinas de días anteriores
3. **Flexibilidad:** Múltiples formas de especificar fechas
4. **Claridad:** Respuestas bien estructuradas y organizadas
5. **Interactividad:** Opción de modificar la rutina después de verla

## 🔮 Futuras Mejoras

- Integración con calendario para selección visual de fechas
- Comparación de rutinas entre diferentes días
- Estadísticas de consumo por período
- Exportación de rutinas a PDF
- Recordatorios de comidas pendientes

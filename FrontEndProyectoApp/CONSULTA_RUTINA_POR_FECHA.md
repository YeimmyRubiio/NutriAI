# 📅 Consulta de Rutina por Fecha Específica - Implementación

## 🎯 Funcionalidad Implementada

Se ha implementado la funcionalidad para que el chatbot consulte la base de datos y muestre las rutinas nutricionales de fechas específicas cuando el usuario las solicite.

## 🔧 Cambios Implementados

### 1. **Formato de Fecha Estándar**
- **Formato requerido:** DD/MM/YYYY (día/mes/año)
- **Ejemplos:** "05/10/2025", "15/01/2024", "20/12/2024"

### 2. **Mensajes del Chatbot Actualizados**

**Solicitud de fecha específica:**
```
👋 ¡Hola Manuel! Para mostrarte la rutina de una fecha específica, necesito que me indiques la fecha en formato DD/MM/YYYY.

📅 Ejemplos de formato de fecha:
• "Rutina 05/10/2025"
• "Ver rutina 15/01/2024"
• "Mostrar rutina 20/12/2024"

💡 Formato requerido: DD/MM/YYYY (día/mes/año)

¿De qué fecha te gustaría ver la rutina? (ejemplo: 05/10/2025)
```

**Pregunta final actualizada:**
```
¿Deseas ver la rutina de otro día?
📅 Puedes decirme, por ejemplo: "Rutina 05/10/2025" o "Ver rutina 15/01/2024".
```

### 3. **Funcionalidad de Base de Datos**

#### **Nueva Función: `getRoutineForSpecificDate()`**
```kotlin
private suspend fun getRoutineForSpecificDate(dateString: String, userId: Long): List<RegistroAlimentoSalida>? {
    // Parsear la fecha DD/MM/YYYY
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val targetDate = LocalDate.parse(dateString, formatter)
    
    // Obtener todos los registros del usuario
    val allRegistros = repository.obtenerComidasRecientes(userId)
    
    // Filtrar por la fecha específica
    val registrosDelDia = allRegistros.filter { registro ->
        val registroDate = LocalDate.parse(registro.consumidoEn.substring(0, 10))
        registroDate == targetDate
    }
    
    return registrosDelDia
}
```

#### **Función Actualizada: `generateRoutineResponse()`**
```kotlin
private suspend fun generateRoutineResponse(userProfile: Usuario?, currentRoutine: List<RegistroAlimentoSalida>?, datePattern: String?): String {
    return if (datePattern != null) {
        // Rutina de fecha específica - consultar base de datos
        val userId = userProfile?.idUsuario ?: 0L
        val specificRoutine = getRoutineForSpecificDate(datePattern, userId)
        
        "$greeting Aquí tienes tu rutina nutricional del $datePattern:\n\n" +
        generateRoutineContent(specificRoutine, datePattern)
    } else {
        // Rutina de hoy
        "$greeting Aquí tienes tu rutina nutricional de hoy:\n\n" +
        generateRoutineContent(currentRoutine, "hoy")
    }
}
```

### 4. **Detección de Fechas Mejorada**

La función `extractDateFromMessage()` ahora prioriza el formato DD/MM/YYYY:

```kotlin
val datePatterns = listOf(
    // Formato DD/MM/YYYY (prioritario)
    Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})"),
    // Formato YYYY-MM-DD
    Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})"),
    // Días relativos (ayer, hoy, mañana)
    Regex("(ayer|hoy|mañana)"),
    // Otros formatos...
)
```

## 🎯 Flujo de Funcionamiento

### 1. **Usuario solicita rutina de fecha específica**
```
Usuario: "Rutina 05/10/2025"
```

### 2. **Chatbot detecta la fecha**
- Extrae "05/10/2025" del mensaje
- Parsea la fecha usando formato DD/MM/YYYY

### 3. **Consulta a la base de datos**
- Obtiene todos los registros del usuario
- Filtra por la fecha específica
- Retorna solo los registros de ese día

### 4. **Muestra la rutina**
```
👋 ¡Hola Manuel! Aquí tienes tu rutina nutricional del 05/10/2025:

🌅 Desayuno:
- Leche

🌞 Almuerzo:
- Pollo

🌙 Cena:
- No hay alimentos registrados

🍎 Snack:
- No hay alimentos registrados

¿Deseas ver la rutina de otro día?
📅 Puedes decirme, por ejemplo: "Rutina 05/10/2025" o "Ver rutina 15/01/2024".
```

## 🔍 Logs de Debug

La implementación incluye logs detallados para debugging:

```
=== CONSULTANDO RUTINA PARA FECHA ESPECÍFICA ===
Fecha solicitada: 05/10/2025
Usuario ID: 123
Fecha parseada: 2025-10-05
Total de registros del usuario: 50
Registros encontrados para 05/10/2025: 2
- Leche (Desayuno)
- Pollo (Almuerzo)
```

## ✅ Beneficios

1. **🎯 Consulta real:** Va a la base de datos y obtiene datos reales
2. **📅 Fechas específicas:** Permite consultar cualquier fecha
3. **🔍 Debugging:** Logs detallados para verificar funcionamiento
4. **📱 Formato claro:** Formato DD/MM/YYYY fácil de usar
5. **⚡ Eficiente:** Filtrado optimizado de registros

## 🧪 Pruebas Recomendadas

1. **Registrar alimentos** en diferentes fechas
2. **Solicitar rutina** con formato "Rutina DD/MM/YYYY"
3. **Verificar logs** para confirmar consulta a base de datos
4. **Probar fechas** sin registros para ver mensaje apropiado
5. **Probar formato incorrecto** para ver solicitud de formato

## 📝 Notas Técnicas

- **Formato de fecha:** DD/MM/YYYY (día/mes/año)
- **Base de datos:** Consulta `repository.obtenerComidasRecientes()`
- **Filtrado:** Por fecha exacta usando `LocalDate`
- **Logs:** Incluidos para debugging y monitoreo
- **Manejo de errores:** Try-catch para fechas inválidas

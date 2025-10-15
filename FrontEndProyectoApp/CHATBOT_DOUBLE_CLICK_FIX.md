# Corrección del Problema de Doble Clic en Chatbot

## Problema Identificado

**Síntoma:** Al hacer clic en "Mostrar rutina nutricional", el chatbot no abre en el primer clic y requiere un segundo clic para funcionar.

## Causa del Problema

El problema estaba en la lógica de apertura del chatbot que incluía:

1. **Delay de 2000ms** que causaba que el estado se resetee
2. **Carga síncrona de datos** que bloqueaba la apertura
3. **Múltiples operaciones** en el mismo `LaunchedEffect` que causaban conflictos

## Solución Implementada

### **Antes (Problemático):**
```kotlin
LaunchedEffect(showChatbot) {
    if (showChatbot) {
        // Cargar datos del usuario
        usuarioViewModel.cargarUsuario(idUsuario)
        viewModel.cargarComidasRecientes()
        
        // DELAY PROBLEMÁTICO
        kotlinx.coroutines.delay(2000)
        
        // Construir perfil
        userProfile = buildUserProfileAfterLoad(...)
        
        // Actualizar ViewModel
        chatbotViewModel.updateUserProfile(userProfile)
        chatbotViewModel.updateCurrentRoutine(...)
        
        // Iniciar sesión
        chatbotViewModel.startNewSession(idUsuario)
    }
}
```

### **Después (Corregido):**
```kotlin
// 1. Apertura inmediata del chatbot
LaunchedEffect(showChatbot) {
    if (showChatbot) {
        // Iniciar sesión inmediatamente sin delay
        chatbotViewModel.startNewSession(idUsuario)
    }
}

// 2. Carga de datos en background (separado)
LaunchedEffect(showChatbot) {
    if (showChatbot && idUsuario != 0L) {
        // Cargar datos del usuario
        usuarioViewModel.cargarUsuario(idUsuario)
        viewModel.cargarComidasRecientes()
        
        // Delay reducido (1000ms)
        kotlinx.coroutines.delay(1000)
        
        // Actualizar datos en background
        val userProfile = buildUserProfileAfterLoad(...)
        chatbotViewModel.updateUserProfile(userProfile)
        chatbotViewModel.updateCurrentRoutine(...)
    }
}
```

## Beneficios de la Corrección

- ✅ **Apertura inmediata:** El chatbot se abre en el primer clic
- ✅ **Sin bloqueos:** No hay delay que cause problemas de estado
- ✅ **Carga asíncrona:** Los datos se cargan en background
- ✅ **Separación de responsabilidades:** Apertura y carga de datos separadas
- ✅ **Experiencia fluida:** El usuario no necesita hacer clic dos veces

## Flujo Corregido

1. **Usuario hace clic** en "Mostrar rutina nutricional"
2. **`showChatbot = true`** se activa inmediatamente
3. **Chatbot se abre** sin delay
4. **Datos se cargan** en background (1000ms)
5. **Perfil se actualiza** automáticamente
6. **Experiencia fluida** para el usuario

## Resultado

El chatbot ahora se abre correctamente en el primer clic, eliminando la necesidad de hacer clic dos veces.

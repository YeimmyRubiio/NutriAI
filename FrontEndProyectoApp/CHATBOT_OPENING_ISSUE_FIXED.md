# Corrección del Problema de Apertura del Chatbot

## Problema Identificado

**Síntoma:** Al hacer clic en "Mostrar rutina nutricional", el chatbot se abre y se cierra inmediatamente, requiriendo un segundo clic para funcionar correctamente.

## Causa del Problema

El problema estaba en la lógica del `LaunchedEffect(showChatbot)` que se ejecutaba tanto al abrir como al cerrar el chatbot:

```kotlin
// ANTES - PROBLEMÁTICO
LaunchedEffect(showChatbot) {
    if (showChatbot) {
        // Lógica de apertura con delay(2000)
        kotlinx.coroutines.delay(2000)
        // ... más lógica
    } else {
        // Lógica de cierre
        chatbotViewModel.endSession()
    }
}
```

**Problema:** El `delay(2000)` y la lógica compleja de apertura se ejecutaba cada vez que cambiaba `showChatbot`, causando conflictos de estado.

## Solución Implementada

Separé la lógica en dos `LaunchedEffect` independientes:

```kotlin
// DESPUÉS - CORREGIDO
// Manejar apertura del chatbot
LaunchedEffect(showChatbot) {
    if (showChatbot) {
        // Solo lógica de apertura
        // ... cargar datos del usuario
        kotlinx.coroutines.delay(2000)
        // ... iniciar sesión
    }
}

// Manejar cierre del chatbot
LaunchedEffect(showChatbot) {
    if (!showChatbot) {
        // Solo lógica de cierre
        chatbotViewModel.endSession()
    }
}
```

## Beneficios de la Corrección

- ✅ **Apertura estable:** El chatbot se abre correctamente en el primer clic
- ✅ **Sin conflictos:** La lógica de apertura y cierre está separada
- ✅ **Estado limpio:** No hay interferencia entre los efectos
- ✅ **Experiencia fluida:** El usuario no necesita hacer clic dos veces

## Flujo Corregido

1. **Usuario hace clic en "Mostrar rutina nutricional"**
2. **`showChatbot = true`** se activa
3. **Solo se ejecuta la lógica de apertura** (sin interferencia de cierre)
4. **El chatbot se abre correctamente** en el primer intento
5. **Al cerrar, solo se ejecuta la lógica de cierre**

## Resultado

El chatbot ahora se abre correctamente en el primer clic, sin necesidad de hacer clic dos veces.

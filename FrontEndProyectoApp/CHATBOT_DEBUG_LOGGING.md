# Debug Logging para Problema de Apertura del Chatbot

## Problema Persistente

El chatbot sigue cerrándose en el primer clic y requiere un segundo clic para abrir correctamente.

## Debug Logging Implementado

### 1. **Logging del Botón**
```kotlin
onClick = { 
    println("=== BOTÓN CHATBOT CLICKEADO ===")
    println("showChatbot antes: $showChatbot")
    showChatbot = true
    println("showChatbot después: $showChatbot")
}
```

### 2. **Logging de Apertura**
```kotlin
LaunchedEffect(showChatbot) {
    println("=== LAUNCHED EFFECT SHOWCHATBOT: $showChatbot ===")
    if (showChatbot) {
        println("=== INICIANDO APERTURA DEL CHATBOT ===")
        // ... lógica de apertura
        println("=== SESIÓN INICIADA ===")
    }
}
```

### 3. **Logging de Cierre**
```kotlin
LaunchedEffect(showChatbot) {
    println("=== LAUNCHED EFFECT SHOWCHATBOT (CIERRE): $showChatbot ===")
    if (!showChatbot) {
        println("=== CERRANDO CHATBOT ===")
        chatbotViewModel.endSession()
    }
}
```

### 4. **Logging del Dialog**
```kotlin
onDismiss = { 
    println("=== CHATBOT DISMISS LLAMADO ===")
    showChatbot = false 
}
```

## Secuencia Esperada de Logs

**Primer clic (debería funcionar):**
```
=== BOTÓN CHATBOT CLICKEADO ===
showChatbot antes: false
showChatbot después: true
=== LAUNCHED EFFECT SHOWCHATBOT: true ===
=== INICIANDO APERTURA DEL CHATBOT ===
=== CARGANDO USUARIO ACTUAL ===
...
=== SESIÓN INICIADA ===
```

**Si se cierra inmediatamente:**
```
=== CHATBOT DISMISS LLAMADO ===
=== LAUNCHED EFFECT SHOWCHATBOT (CIERRE): false ===
=== CERRANDO CHATBOT ===
```

## Posibles Causas

1. **Dialog se cierra automáticamente** - El `onDismiss` se llama inmediatamente
2. **Estado se resetea** - Algún efecto está cambiando `showChatbot` a `false`
3. **Conflicto de LaunchedEffect** - Múltiples efectos interfieren
4. **Problema en ChatbotDialog** - El dialog tiene algún problema interno

## Próximos Pasos

1. **Ejecutar la aplicación** con el debug activado
2. **Hacer clic en el botón** del chatbot
3. **Revisar los logs** para identificar la secuencia exacta
4. **Identificar dónde** se está cerrando el chatbot
5. **Corregir la causa** específica encontrada

## Logs a Buscar

- Si aparece `=== CHATBOT DISMISS LLAMADO ===` inmediatamente después del clic
- Si hay múltiples `LaunchedEffect` ejecutándose
- Si el estado `showChatbot` cambia de `true` a `false` automáticamente
- Si hay algún error en la inicialización del chatbot

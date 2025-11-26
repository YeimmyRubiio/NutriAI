package com.example.frontendproyectoapp.screen

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.frontendproyectoapp.model.*
import kotlinx.coroutines.delay

@Composable
fun ChatbotDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    isLoading: Boolean = false,
    onClearMessages: (() -> Unit)? = null,
    onAddWelcomeMessage: (() -> Unit)? = null
) {
    if (isVisible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            ChatbotContent(
                messages = messages,
                onSendMessage = onSendMessage,
                onDismiss = onDismiss,
                isLoading = isLoading,
                onClearMessages = onClearMessages,
                onAddWelcomeMessage = onAddWelcomeMessage
            )
        }
    }
}

@Composable
fun ChatbotContent(
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    onClearMessages: (() -> Unit)? = null,
    onAddWelcomeMessage: (() -> Unit)? = null
) {
    var messageText by remember { mutableStateOf("") }
    var isTextInputEnabled by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // Auto scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            delay(100)
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        ChatbotHeader(onDismiss = onDismiss)
        
        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message = message)
            }
            
            // Show typing indicator when loading and there are messages
            if (isLoading && messages.isNotEmpty()) {
                item {
                    TypingIndicator()
                }
            }
        }

        // NO mostrar mensaje automático - el usuario debe elegir una opción
        
        // Quick actions
        if (messages.isEmpty()) {
            QuickActions(
                onSendMessage = { message ->
                    isTextInputEnabled = true
                    onSendMessage(message)
                }
            )
        } else {
            // Botón de regreso cuando hay mensajes
            BackToActionsButton(
                onClearMessages = {
                    isTextInputEnabled = false
                    onClearMessages?.invoke()
                }
            )
        }

        // Input
        MessageInput(
            messageText = messageText,
            onMessageTextChange = { messageText = it },
            onSendMessage = {
                if (messageText.isNotBlank()) {
                    onSendMessage(messageText.trim())
                    messageText = ""
                    focusManager.clearFocus()
                }
            },
            enabled = isTextInputEnabled
        )
    }
}

@Composable
fun ChatbotHeader(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF4CAF50) // Verde como en tu FAB
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Robot icon con gradiente
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Chatbot",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(28.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = "NutriAI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF81C784))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "En línea",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE8F5E8),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

// Función para convertir HTML simple a AnnotatedString
fun parseHtmlToAnnotatedString(html: String, defaultColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        val text = html
        
        while (currentIndex < text.length) {
            val boldStart = text.indexOf("<b>", currentIndex)
            val boldEnd = if (boldStart != -1) text.indexOf("</b>", boldStart) else -1
            
            if (boldStart == -1 || boldEnd == -1) {
                // No hay más etiquetas, agregar el resto del texto
                append(text.substring(currentIndex))
                break
            }
            
            // Agregar texto antes de la etiqueta
            if (boldStart > currentIndex) {
                append(text.substring(currentIndex, boldStart))
            }
            
            // Agregar texto en negrita
            val boldText = text.substring(boldStart + 3, boldEnd)
            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                append(boldText)
            }
            
            currentIndex = boldEnd + 4
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromUser) 
            Arrangement.End else Arrangement.Start
    ) {
        if (!message.isFromUser) {
            // Bot avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Bot",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (message.isFromUser) 
                Alignment.End else Alignment.Start
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(if (message.isFromUser) 0.8f else 0.9f),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isFromUser) 
                        Color(0xFF4CAF50) 
                    else Color(0xFFF5F5F5)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (message.isFromUser) 4.dp else 20.dp,
                    bottomEnd = if (message.isFromUser) 20.dp else 4.dp
                )
            ) {
                val textColor = if (message.isFromUser) 
                    Color.White 
                else Color(0xFF424242)
                
                Text(
                    text = parseHtmlToAnnotatedString(message.message, textColor),
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bot avatar with friendly face
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "Bot",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        
        // Typing bubble with animation
        Card(
            modifier = Modifier.padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF5F5F5)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 4.dp,
                bottomEnd = 20.dp
            )
        ) {
            Box(
                modifier = Modifier.padding(16.dp)
            ) {
                TypingAnimation()
            }
        }
    }
}

@Composable
fun TypingAnimation() {
    val animationDuration = 800
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(3) { index ->
            val alpha = when (index) {
                0 -> dot1Alpha
                1 -> dot2Alpha
                2 -> dot3Alpha
                else -> 1f
            }
            
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        Color(0xFF4CAF50).copy(alpha = alpha)
                    )
            )
        }
    }
}

@Composable
fun QuickActions(onSendMessage: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFE8F5E8)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "📋 Seleccione una opción para continuar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32),
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        val quickActions = listOf(
            "💡 Aclarar sus dudas sobre alimentación y bienestar" to Icons.Default.Quiz,
            "🍎 Sugerir alimentos acordes a su perfil" to Icons.Default.Restaurant,
            "📅 Mostrar su rutina nutricional actual" to Icons.Default.CalendarToday,
            "🎯 Generar una rutina personalizada para usted" to Icons.Default.AutoAwesome
        )
        
        quickActions.forEach { (action, icon) ->
            QuickActionButton(
                text = action,
                icon = icon,
                onClick = { onSendMessage(action) }
            )
        }
    }
}

@Composable
fun QuickActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFBFEFB)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF2E7D32),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun BackToActionsButton(onClearMessages: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFBFEFB)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { 
                    // Limpiar mensajes para volver a las acciones
                    onClearMessages()
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Volver a acciones disponibles",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MessageInput(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = messageText,
            onValueChange = onMessageTextChange,
            enabled = enabled,
            placeholder = {
                Text(
                    text = if (enabled) "Escriba su mensaje..." else "Escriba aquí su consulta...",
                    color = if (enabled) Color(0xFF9E9E9E) else Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4CAF50),
                unfocusedBorderColor = Color(0xFFE0E0E0),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color(0xFFFAFAFA),
                focusedTextColor = Color(0xFF212121),
                unfocusedTextColor = Color(0xFF212121),
                cursorColor = Color(0xFF4CAF50),
                disabledBorderColor = Color(0xFFE0E0E0),
                disabledContainerColor = Color(0xFFF5F5F5),
                disabledTextColor = Color(0xFF9E9E9E)
            ),
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            minLines = 1,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(onSend = { onSendMessage() })
        )
        
        FloatingActionButton(
            onClick = if (enabled) onSendMessage else { { } },
            modifier = Modifier.size(48.dp),
            containerColor = if (enabled) Color(0xFF4CAF50) else Color(0xFFE0E0E0),
            contentColor = if (enabled) Color.White else Color(0xFF9E9E9E),
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = if (enabled) 4.dp else 2.dp,
                pressedElevation = if (enabled) 8.dp else 4.dp
            )
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Enviar",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60000 -> "Ahora"
        diff < 3600000 -> "${diff / 60000}m"
        diff < 86400000 -> "${diff / 3600000}h"
        else -> "${diff / 86400000}d"
    }
}

package com.example.frontendproyectoapp.model

import java.sql.Timestamp

data class ChatMessage(
    val id: String = "",
    val message: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val tipoIntento: TipoIntento? = null,
    val tipoAccion: TipoAccion? = null
)

data class ChatbotRequest(
    val mensaje: String,
    val idSesion: Long? = null,
    val tipoIntento: TipoIntento? = null
)

data class ChatbotResponse(
    val respuesta: String,
    val tipoIntento: TipoIntento,
    val tipoAccion: TipoAccion? = null,
    val idInteraccion: Long? = null,
    val tema: String? = null
)

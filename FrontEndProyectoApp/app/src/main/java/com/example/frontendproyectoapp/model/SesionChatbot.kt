package com.example.frontendproyectoapp.model

import com.google.gson.annotations.SerializedName
import java.sql.Timestamp

data class SesionChatbot (
    @SerializedName("idSesion")
    val idSesion: Long? = null,
    
    @SerializedName("inicioSesion")
    val inicioSesion: Timestamp? = null,
    
    @SerializedName("finSesion")
    val finSesion: Timestamp? = null,
    
    @SerializedName("mensajes")
    val mensajes: String? = null,
    
    @SerializedName("retroalimentacion")
    val retroalimentacion: String? = null,
    
    @SerializedName("id_usuario")
    val idUsuario: Long? = null
)
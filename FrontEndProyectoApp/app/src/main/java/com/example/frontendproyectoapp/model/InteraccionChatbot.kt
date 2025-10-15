package com.example.frontendproyectoapp.model

import com.google.gson.annotations.SerializedName
import java.sql.Timestamp

data class InteraccionChatbot (
    @SerializedName("id_interaccion")
    val idInteraccion: Long? = null,
    
    @SerializedName("consultaUsuario")
    val consultaUsuario: String,
    
    @SerializedName("respuestaIA")
    val respuestaIA: String,
    
    @SerializedName("tipoIntento")
    val tipoIntento: TipoIntento,
    
    @SerializedName("tipoAccion")
    val tipoAccion: TipoAccion? = null,
    
    @SerializedName("tema")
    val tema: String,
    
    @SerializedName("timestamp")
    val timestamp: Timestamp? = null,
    
    @SerializedName("id_sesion")
    val idSesion: Long? = null
)

enum class TipoIntento {
    @SerializedName("Modificar_Rutina")
    Modificar_Rutina,
    
    @SerializedName("Pregunta_Nutricional")
    Pregunta_Nutricional,
    
    @SerializedName("Otros")
    Otros
}

enum class TipoAccion {
    @SerializedName("Modificar")
    Modificar,
    
    @SerializedName("Agregar")
    Agregar,
    
    @SerializedName("Eliminar")
    Eliminar
}
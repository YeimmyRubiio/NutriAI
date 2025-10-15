package com.example.frontendproyectoapp.model

data class Usuario (
    val idUsuario: Long = 0,
    val correo: String = "",
    val contrasena: String = "",
    val nombre: String = "",
    val fechaNacimiento: String,
    val altura: Float = 0f,
    val peso: Float = 0f,
    val sexo: String = "",
    val restriccionesDieta: String = "",
    val objetivosSalud: String = "",
    val nivelActividad: String = "",
    val creadoEn: String? = null,
    val actualizadoEn: String? = null,
    val pesoObjetivo: Float = 0f
)
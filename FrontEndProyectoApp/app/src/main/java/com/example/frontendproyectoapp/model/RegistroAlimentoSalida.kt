package com.example.frontendproyectoapp.model

data class RegistroAlimentoSalida(
    val idRegistroAlimento: Long,
    val tamanoPorcion: Float,
    val unidadMedida: String,
    val tamanoOriginal: Float? = null,
    val unidadOriginal: String? = null,
    val momentoDelDia: String,
    val consumidoEn: String,
    val alimento: Alimento
)
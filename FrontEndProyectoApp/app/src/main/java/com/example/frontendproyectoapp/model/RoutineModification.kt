package com.example.frontendproyectoapp.model

import java.util.*

data class RoutineModification(
    val id: String = UUID.randomUUID().toString(),
    val action: ModificationAction,
    val foodName: String,
    val mealTime: String,
    val timestamp: Long = System.currentTimeMillis(),
    val originalFood: String? = null, // Para cambios de alimentos
    val quantity: String? = null,
    val notes: String? = null
)

enum class ModificationAction {
    ADD,
    REMOVE,
    CHANGE,
    VIEW_ROUTINE
}

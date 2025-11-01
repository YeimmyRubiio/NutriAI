package com.example.frontendproyectoapp.repository

import android.util.Log
import com.example.frontendproyectoapp.interfaces.AlimentoRecienteService
import com.example.frontendproyectoapp.interfaces.AlimentoService
import com.example.frontendproyectoapp.interfaces.RegistroAlimentoService
import com.example.frontendproyectoapp.interfaces.RetrofitClient
import com.example.frontendproyectoapp.model.Alimento
import com.example.frontendproyectoapp.model.AlimentoReciente
import com.example.frontendproyectoapp.model.RegistroAlimentoEntrada
import com.example.frontendproyectoapp.model.RegistroAlimentoSalida
import retrofit2.Response

class AlimentoRepository {
    private val alimentoService = RetrofitClient.createService(AlimentoService::class.java)
    private val recienteService = RetrofitClient.createService(AlimentoRecienteService::class.java)
    private val regAlimentoService = RetrofitClient.createService(RegistroAlimentoService::class.java)

    suspend fun obtenerTodos(): List<Alimento> = alimentoService.listarAlimentos()

    suspend fun obtenerFavoritos(idUsuario: Long): List<Alimento> =
        alimentoService.obtenerFavoritos(idUsuario)

    suspend fun obtenerAlimentosPorCategoria(categoria: String): List<Alimento> =
        alimentoService.obtenerAlimentosPorCategoria(categoria)

    suspend fun registrarAlimentoReciente(idUsuario: Long, idAlimento: Long): Boolean {
        val response = recienteService.registrarReciente(idUsuario, idAlimento)
        return response.isSuccessful
    }

    suspend fun obtenerAlimentosRecientes(idUsuario: Long): List<AlimentoReciente> {
        return recienteService.obtenerRecientes(idUsuario)
    }

    suspend fun eliminarTodosRecientes(idUsuario: Long) {
        recienteService.eliminarTodos(idUsuario)
    }

    suspend fun eliminarRecienteIndividual(idUsuario: Long, idAlimento: Long) {
        val response = recienteService.eliminarRecienteIndividual(idUsuario, idAlimento)
        if (!response.isSuccessful) {
            throw Exception("Error al eliminar alimento reciente")
        }
    }

    suspend fun guardarRegistro(registro: RegistroAlimentoEntrada) {
        Log.d("AlimentoRepo", "→ Enviando registro al backend: $registro")
        val response = regAlimentoService.guardarRegistro(registro)
        Log.d("AlimentoRepo", "← Respuesta backend guardarRegistro: ${response.code()} - ${response.message()}")
    }


    suspend fun obtenerComidasRecientes(idUsuario: Long): List<RegistroAlimentoSalida> {
        return regAlimentoService.obtenerComidasRecientes(idUsuario)
    }

    suspend fun eliminarRegistrosPorFechaYMomento(idUsuario: Long, fecha: String, momento: String): Response<Unit> {
        Log.d("RegistroRepo", "→ Enviando request DELETE con: idUsuario=$idUsuario, fecha=$fecha, momento=$momento")
        return regAlimentoService
            .eliminarPorFechaYMomento(idUsuario, momento, fecha) // orden correcto de los @Path
    }

    suspend fun eliminarRegistroPorId(idRegistro: Long) {
        val response = regAlimentoService.eliminarRegistroPorId(idRegistro)
        if (!response.isSuccessful) throw Exception("No se pudo eliminar el registro")
    }

    suspend fun obtenerUnidadesPorId(idAlimento: Long): List<String> {
        val response = regAlimentoService.obtenerUnidadesPorId(idAlimento)
        if (response.isSuccessful) {
            return response.body() ?: emptyList()
        } else {
            throw Exception("Error al obtener unidades por ID: ${response.code()} ${response.message()}")
        }
    }

    suspend fun obtenerUnidadesPorNombre(nombreAlimento: String): List<String> {
        val response = regAlimentoService.obtenerUnidadesPorNombre(nombreAlimento)
        if (response.isSuccessful) {
            return response.body() ?: emptyList()
        } else {
            throw Exception("Error al obtener unidades por nombre: ${response.code()} ${response.message()}")
        }
    }
    
    // Métodos para integración con NutriAI chatbot
    
    suspend fun agregarAlimentoDesdeChatbot(
        idUsuario: Long,
        nombreAlimento: String,
        cantidad: String,
        unidad: String,
        momentoDelDia: String
    ): Boolean {
        try {
            Log.d("AlimentoRepo", "→ Agregando alimento desde chatbot: $nombreAlimento")
            
            // Buscar el alimento por nombre
            val alimentos = obtenerTodos()
            val alimento = alimentos.find { it.nombreAlimento.equals(nombreAlimento, ignoreCase = true) }
            
            if (alimento == null) {
                Log.e("AlimentoRepo", "❌ Alimento no encontrado: $nombreAlimento")
                return false
            }
            
            // Crear registro de alimento
            val registro = RegistroAlimentoEntrada(
                idUsuario = idUsuario,
                idAlimento = alimento.idAlimento,
                tamanoPorcion = cantidad.toFloatOrNull() ?: 1.0f,
                unidadMedida = unidad,
                tamanoOriginal = cantidad.toFloatOrNull() ?: 1.0f,
                unidadOriginal = unidad,
                momentoDelDia = momentoDelDia
            )
            
            // Guardar en la base de datos
            guardarRegistro(registro)
            Log.d("AlimentoRepo", "✅ Alimento agregado exitosamente desde chatbot")
            return true
            
        } catch (e: Exception) {
            Log.e("AlimentoRepo", "❌ Error agregando alimento desde chatbot: ${e.message}")
            return false
        }
    }
    
    suspend fun cambiarAlimentoDesdeChatbot(
        idUsuario: Long,
        alimentoOriginal: String,
        nuevoAlimento: String,
        cantidad: String,
        unidad: String,
        momentoDelDia: String
    ): Boolean {
        try {
            Log.d("AlimentoRepo", "→ Cambiando alimento desde chatbot: $alimentoOriginal -> $nuevoAlimento")
            
            // Buscar el alimento original y eliminarlo
            val registros = obtenerComidasRecientes(idUsuario)
            val registroOriginal = registros.find { 
                it.alimento.nombreAlimento.equals(alimentoOriginal, ignoreCase = true) &&
                it.momentoDelDia.equals(momentoDelDia, ignoreCase = true)
            }
            
            if (registroOriginal != null) {
                // Eliminar el registro original
                eliminarRegistroPorId(registroOriginal. idRegistroAlimento)
                Log.d("AlimentoRepo", "✅ Registro original eliminado: ${registroOriginal.alimento.nombreAlimento}")
            }
            
            // Agregar el nuevo alimento
            val resultado = agregarAlimentoDesdeChatbot(idUsuario, nuevoAlimento, cantidad, unidad, momentoDelDia)
            
            if (resultado) {
                Log.d("AlimentoRepo", "✅ Cambio de alimento completado exitosamente")
            } else {
                Log.e("AlimentoRepo", "❌ Error agregando nuevo alimento")
            }
            
            return resultado
            
        } catch (e: Exception) {
            Log.e("AlimentoRepo", "❌ Error cambiando alimento desde chatbot: ${e.message}")
            return false
        }
    }
    
    suspend fun buscarAlimentoPorNombre(nombreAlimento: String): Alimento? {
        return try {
            val alimentos = obtenerTodos()
            alimentos.find { it.nombreAlimento.equals(nombreAlimento, ignoreCase = true) }
        } catch (e: Exception) {
            Log.e("AlimentoRepo", "❌ Error buscando alimento por nombre: ${e.message}")
            null
        }
    }
    
    // Métodos para el nuevo flujo de cambio por categorías
    
    suspend fun obtenerCategoriasUnicas(): List<String> {
        return try {
            val alimentos = obtenerTodos()
            val categorias = alimentos.map { it.categoria }.distinct().sorted()
            Log.d("AlimentoRepo", "✅ Categorías obtenidas: $categorias")
            categorias
        } catch (e: Exception) {
            Log.e("AlimentoRepo", "❌ Error obteniendo categorías: ${e.message}")
            emptyList()
        }
    }
    
    suspend fun obtenerAlimentosPorCategoriaParaChatbot(categoria: String): List<Alimento> {
        return try {
            val alimentos = obtenerAlimentosPorCategoria(categoria)
            Log.d("AlimentoRepo", "✅ Alimentos obtenidos para categoría '$categoria': ${alimentos.size} elementos")
            alimentos
        } catch (e: Exception) {
            Log.e("AlimentoRepo", "❌ Error obteniendo alimentos por categoría '$categoria': ${e.message}")
            emptyList()
        }
    }
}

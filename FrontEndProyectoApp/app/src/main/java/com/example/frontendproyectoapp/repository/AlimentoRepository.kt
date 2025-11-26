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
            
            // Normalizar cantidad: reemplazar coma por punto y convertir a float
            val cantidadNormalizada = cantidad.replace(",", ".").trim()
            val cantidadFloat = cantidadNormalizada.toFloatOrNull()
            
            if (cantidadFloat == null || cantidadFloat <= 0f) {
                Log.e("AlimentoRepo", "❌ Cantidad inválida: $cantidad (normalizada: $cantidadNormalizada)")
                return false
            }
            
            Log.d("AlimentoRepo", "📊 Cantidad parseada: $cantidad -> $cantidadNormalizada -> $cantidadFloat")
            
            // Obtener unidades válidas del alimento desde la base de datos
            val unidadesValidas = try {
                obtenerUnidadesPorId(alimento.idAlimento)
            } catch (e: Exception) {
                Log.e("AlimentoRepo", "⚠️ Error obteniendo unidades válidas: ${e.message}")
                emptyList()
            }
            
            Log.d("AlimentoRepo", "📋 Unidades válidas para ${alimento.nombreAlimento}: $unidadesValidas")
            Log.d("AlimentoRepo", "📋 Unidad solicitada: $unidad")
            
            // Buscar una unidad válida que coincida o contenga "gramo"
            val unidadValida = when {
                // Si la unidad solicitada está en las unidades válidas, usarla
                unidadesValidas.any { it.equals(unidad, ignoreCase = true) } -> {
                    unidadesValidas.first { it.equals(unidad, ignoreCase = true) }
                }
                // Buscar una unidad que contenga "gramo" o "g"
                unidadesValidas.any { it.lowercase().contains("gramo") || it.lowercase().contains(" g") } -> {
                    unidadesValidas.first { it.lowercase().contains("gramo") || it.lowercase().contains(" g") }
                }
                // Si hay unidades válidas, usar la primera
                unidadesValidas.isNotEmpty() -> {
                    unidadesValidas.first()
                }
                // Si no hay unidades válidas, usar la unidad base del alimento
                else -> {
                    Log.w("AlimentoRepo", "⚠️ No se encontraron unidades válidas, usando unidad base: ${alimento.unidadBase}")
                    alimento.unidadBase
                }
            }
            
            Log.d("AlimentoRepo", "✅ Unidad seleccionada: $unidadValida")
            
            // IMPORTANTE: Usar la unidad EXACTA de la base de datos para unidadOriginal
            // Usar la unidad tal cual está en la BD, sin normalizar, para mantener el formato exacto
            val unidadParaOriginal = when {
                // Solo normalizar las unidades de peso/volumen básicas a formato estándar
                unidadValida.lowercase() in listOf("gramos", "gramo") -> "g"
                unidadValida.lowercase() in listOf("mililitros", "mililitro") -> "ml"
                unidadValida.lowercase() in listOf("litros", "litro") -> "l"
                unidadValida.lowercase() in listOf("kilogramos", "kilogramo") -> "kg"
                unidadValida.lowercase() in listOf("onzas", "onza") -> "oz"
                unidadValida.lowercase() in listOf("libras", "libra") -> "lb"
                // Para TODAS las demás unidades, usar la unidad EXACTA de la BD (sin normalizar)
                // Esto asegura que usamos el formato exacto que el backend espera
                else -> {
                    // Usar la unidad válida tal cual está en la BD (mantener mayúsculas/minúsculas originales)
                    unidadValida
                }
            }
            
            Log.d("AlimentoRepo", "📋 Unidad para unidadOriginal: $unidadParaOriginal (unidad válida: $unidadValida)")
            
            // Crear registro de alimento con la unidad válida
            // tamanoPorcion y unidadMedida son para el backend (se convertirán a gramos)
            // tamanoOriginal y unidadOriginal son los valores que el usuario ve (la cantidad y unidad original)
            // IMPORTANTE: unidadOriginal debe usar el formato exacto que el backend espera para evitar errores
            val registro = RegistroAlimentoEntrada(
                idUsuario = idUsuario,
                idAlimento = alimento.idAlimento,
                tamanoPorcion = cantidadFloat,  // El backend lo convertirá a gramos
                unidadMedida = unidadValida,      // El backend lo convertirá a "gramos"
                tamanoOriginal = cantidadFloat,   // Cantidad original que el usuario ve
                unidadOriginal = unidadParaOriginal,    // Unidad original en formato que el backend espera
                momentoDelDia = momentoDelDia
            )
            
            // Guardar en la base de datos
            val response = regAlimentoService.guardarRegistro(registro)
            Log.d("AlimentoRepo", "← Respuesta backend guardarRegistro: ${response.code()} - ${response.message()}")
            
            if (response.isSuccessful) {
                Log.d("AlimentoRepo", "✅ Alimento agregado exitosamente desde chatbot")
                return true
            } else {
                Log.e("AlimentoRepo", "❌ Error al guardar alimento: ${response.code()} - ${response.message()}")
                if (response.errorBody() != null) {
                    val errorBody = response.errorBody()?.string()
                    Log.e("AlimentoRepo", "❌ Error body: $errorBody")
                }
                return false
            }
            
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
            // Normalizar categorías completamente: eliminar comas y espacios para comparación
            val categoriasMap = mutableMapOf<String, String>() // Map<normalized_key, best_formatted>
            
            alimentos.forEach { alimento ->
                val categoriaOriginal = alimento.categoria.trim()
                
                // Crear clave normalizada: lowercase, sin comas, sin espacios múltiples
                // Esto hace que "Carnes Aves y Derivados" y "Carnes, Aves, y Derivados" sean iguales
                val categoriaKey = categoriaOriginal.lowercase()
                    .replace(",", "") // Eliminar todas las comas
                    .replace(Regex("\\s+"), " ") // Normalizar espacios múltiples a uno solo
                    .trim()
                
                // Solo agregar si no existe ya
                if (!categoriasMap.containsKey(categoriaKey)) {
                    // Preferir formato con comas y espacios si está disponible
                    val categoriaFormateada = categoriaOriginal
                        .replace(Regex("\\s+"), " ") // Normalizar espacios
                        .replace(Regex(",\\s*"), ", ") // Normalizar comas: "A,B" -> "A, B"
                        .replace(Regex("\\s*,\\s*"), ", ") // Asegurar formato consistente
                    categoriasMap[categoriaKey] = categoriaFormateada
                } else {
                    // Si ya existe, mantener la versión con mejor formato (con comas y espacios)
                    val existente = categoriasMap[categoriaKey] ?: ""
                    val categoriaFormateada = categoriaOriginal
                        .replace(Regex("\\s+"), " ")
                        .replace(Regex(",\\s*"), ", ")
                        .replace(Regex("\\s*,\\s*"), ", ")
                    // Preferir la que tiene comas bien formateadas
                    if (categoriaFormateada.contains(", ") && !existente.contains(", ")) {
                        categoriasMap[categoriaKey] = categoriaFormateada
                    } else if (categoriaFormateada.contains(", ") && existente.contains(", ")) {
                        // Ambas tienen comas, mantener la que ya está (primera encontrada)
                        // No hacer nada
                    }
                }
            }
            
            val categoriasUnicas = categoriasMap.values.sorted()
            Log.d("AlimentoRepo", "✅ Categorías obtenidas (sin duplicados): $categoriasUnicas")
            Log.d("AlimentoRepo", "📊 Total de categorías únicas: ${categoriasUnicas.size}")
            Log.d("AlimentoRepo", "📊 Total de alimentos procesados: ${alimentos.size}")
            
            // Verificar duplicados después de normalización
            val categoriasNormalizadas = categoriasUnicas.map { 
                it.lowercase().replace(",", "").replace(Regex("\\s+"), " ").trim()
            }
            val duplicados = categoriasNormalizadas.groupingBy { it }.eachCount().filter { it.value > 1 }
            if (duplicados.isNotEmpty()) {
                Log.w("AlimentoRepo", "⚠️ ADVERTENCIA: Se encontraron duplicados después de normalización: $duplicados")
                // Log detallado de duplicados
                duplicados.forEach { (key, count) ->
                    val categoriasDuplicadas = categoriasUnicas.filter { 
                        it.lowercase().replace(",", "").replace(Regex("\\s+"), " ").trim() == key 
                    }
                    Log.w("AlimentoRepo", "  Duplicado '$key' aparece $count veces: $categoriasDuplicadas")
                }
            } else {
                Log.d("AlimentoRepo", "✅ No se encontraron duplicados después de normalización")
            }
            
            categoriasUnicas
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

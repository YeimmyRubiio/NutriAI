package com.example.frontendproyectoapp.viewModel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.frontendproyectoapp.DataStores.ReminderDataStore
import com.example.frontendproyectoapp.interfaces.AlimentoService
import com.example.frontendproyectoapp.interfaces.RetrofitClient
import com.example.frontendproyectoapp.model.Alimento
import com.example.frontendproyectoapp.DataStores.UserPreferences
import com.example.frontendproyectoapp.model.ReminderState
import com.example.frontendproyectoapp.model.Usuario
import com.example.frontendproyectoapp.model.UsuarioEntrada
import com.example.frontendproyectoapp.model.UsuarioRespuesta
import com.example.frontendproyectoapp.notificaciones.AlarmHelper
import com.example.frontendproyectoapp.repository.UsuarioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class UsuarioViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val repositoryUsuario = UsuarioRepository()
    private val alimentoService = RetrofitClient.createService(AlimentoService::class.java)

    // Mapa para llevar el conteo por categoría
    private val _favoritosPorCategoria = mutableStateMapOf<String, MutableList<Alimento>>()
    val favoritosPorCategoria = mutableStateMapOf<String, List<Alimento>>()

    // Límite máximo por categoría
    private val LIMITE_POR_CATEGORIA = 3

    // LiveData
    private val _usuarios = MutableLiveData<List<Usuario>>(emptyList())
    val usuarios: LiveData<List<Usuario>> = _usuarios

    // Estados del formulario
    var nombre by mutableStateOf("")
    var correo by mutableStateOf("")
    var contrasena by mutableStateOf("")
    var confirmarContrasena by mutableStateOf("")
    var fechaNacimiento by mutableStateOf("")
    var altura by mutableStateOf(0f)
    var peso by mutableStateOf(0f)
    var sexo by mutableStateOf("")
    var restriccionesDieta by mutableStateOf("")
    var objetivosSalud by mutableStateOf("")
    var nivelActividad by mutableStateOf("")
    var pesoObjetivo by mutableStateOf(0f)

    // Regex de validación
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$")
    private val passwordRegex = Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$")

    // Estados de error
    var nombreValidationError by mutableStateOf<String?>(null)
    var correoValidationError by mutableStateOf<String?>(null)
    var contrasenaValidationError by mutableStateOf<String?>(null)
    var confirmarContrasenaValidationError by mutableStateOf<String?>(null)

    // Estado general
    var registroExitoso by mutableStateOf(false)
    var cargando by mutableStateOf(false)
    var errorRegistro by mutableStateOf<String?>(null)

    //Variable para guardar temporalmente los alimentos hasta que se registre un usuario y se puedan guardar los alimentos
    val alimentosFavoritos = mutableStateListOf<Alimento>()

    private val _reminders = mutableStateMapOf<String, ReminderState>()
    val reminders: Map<String, ReminderState> get() = _reminders

    private val _eliminacionState = MutableStateFlow<Result<Unit>?>(null)
    val eliminacionState: StateFlow<Result<Unit>?> = _eliminacionState.asStateFlow()

    var nombreUsuario by mutableStateOf("Usuario")
        private set
    var correoUsuario by mutableStateOf("CorreoUsuario")
    var fechaNacimientoUsuario by mutableStateOf("FechaNacimientoUsuario")
        private set
    var alturaUsuario by mutableStateOf("AlturaUsuario")
    var pesoUsuario by mutableStateOf("PesoUsuario")
    var sexoUsuario by mutableStateOf("SexoUsuario")
        private set
    var restriccionesDietaUsuario by mutableStateOf("RestriccionesDietaUsuario")
    var objetivosSaludUsuario by mutableStateOf("ObjetivosSaludUsuario")
    var nivelActividadUsuario by mutableStateOf("NivelActividadUsuario")
    var pesoObjetivoUsuario by mutableStateOf("PesoObjetivoUsuario")

    // Estado para controlar si el campo de altura está en modo de edición
    var isEditingAltura by mutableStateOf(false)
    // Variable para el nuevo valor de la altura
    var nuevaAltura by mutableStateOf("")

    var isEditingPeso by mutableStateOf(false)
    var nuevoPeso by mutableStateOf("")
    var isEditingPesoObjetivo by mutableStateOf(false)
    var nuevoPesoObjetivo by mutableStateOf("")
    var isEditingDieta by mutableStateOf(false)
    var nuevoDieta by mutableStateOf("")
    var isEditingObjetivo by mutableStateOf(false)
    var nuevoObjetivo by mutableStateOf("")
    var isEditingCorreo by mutableStateOf(false)
    var nuevoCorreo by mutableStateOf("")
    var isEditingNivelAct by mutableStateOf(false)
    var nuevoNivelAct by mutableStateOf("")

    val _actualizacionAlturaState = MutableStateFlow<Result<UsuarioRespuesta>?>(null)
    val actualizacionAlturaState: StateFlow<Result<UsuarioRespuesta>?> = _actualizacionAlturaState.asStateFlow()
    val _actualizacionPesoState = MutableStateFlow<Result<UsuarioRespuesta>?>(null)
    val actualizacionPesoState: StateFlow<Result<UsuarioRespuesta>?> = _actualizacionPesoState.asStateFlow()
    val _actualizacionPesoObjetivoState = MutableStateFlow<Result<UsuarioRespuesta>?>(null)
    val actualizacionPesoObjetivoState: StateFlow<Result<UsuarioRespuesta>?> = _actualizacionPesoObjetivoState.asStateFlow()
    val _actualizacionCorreoState = MutableStateFlow<Result<UsuarioRespuesta>?>(null)
    val actualizacionCorreoState: StateFlow<Result<UsuarioRespuesta>?> = _actualizacionCorreoState.asStateFlow()
    val _actualizacionDietaState = MutableStateFlow<Result<UsuarioRespuesta>?>(null)
    val actualizacionDietaState: StateFlow<Result<UsuarioRespuesta>?> = _actualizacionDietaState.asStateFlow()
    val _actualizacionObjetivoState = MutableStateFlow<Result<UsuarioRespuesta>?>(null)
    val actualizacionObjetivoState: StateFlow<Result<UsuarioRespuesta>?> = _actualizacionObjetivoState.asStateFlow()
    val _actualizacionNivelActState = MutableStateFlow<Result<UsuarioRespuesta>?>(null)
    val actualizacionNivelActState: StateFlow<Result<UsuarioRespuesta>?> = _actualizacionNivelActState.asStateFlow()


    // Estado para manejar el mensaje de éxito o error de la actualización
    var mensajeActualizacionInformacion by mutableStateOf<String?>(null)
    var mensajeActualizacionObjetivos by mutableStateOf<String?>(null)
    var mensajeActualizacionCuenta by mutableStateOf<String?>(null)

    fun cargarUsuario(idUsuario: Long) {
        viewModelScope.launch {
            println("=== CARGANDO USUARIO ===")
            println("ID Usuario: $idUsuario")
            val usuario = repositoryUsuario.obtenerUsuarioPorId(idUsuario)
            println("Usuario obtenido: $usuario")
            if (usuario != null && !usuario.nombre.isNullOrEmpty()) {
                println("Nombre del usuario: '${usuario.nombre}'")
                nombreUsuario = usuario.nombre
                correoUsuario = usuario.correo
                fechaNacimientoUsuario = usuario.fechaNacimiento
                alturaUsuario = usuario.altura.toString()
                pesoUsuario = usuario.peso.toString()
                sexoUsuario = usuario.sexo.toString()
                restriccionesDietaUsuario = usuario.restriccionesDieta.toString()
                objetivosSaludUsuario = usuario.objetivosSalud.toString()
                nivelActividadUsuario = usuario.nivelActividad.toString()
                pesoObjetivoUsuario = usuario.pesoObjetivo.toString()
                println("Usuario cargado exitosamente: $nombreUsuario")
            } else {
                println("No se pudo cargar el usuario o el nombre está vacío")
            }
        }
    }

    fun cargarNombreUsuario(idUsuario: Long) {
        viewModelScope.launch {
            val usuario = repositoryUsuario.obtenerUsuarioPorId(idUsuario)
            if (usuario != null && !usuario.nombre.isNullOrEmpty()) {
                nombreUsuario = usuario.nombre
            }
        }
    }

    fun validateNombre(nombre: String): String? {
        return if (nombre.isBlank()) "El nombre no puede estar vacío" else null
    }

    fun validateEmail(email: String): String? {
        return if (!emailRegex.matches(email)) "Correo inválido" else null
    }

    fun validatePassword(password: String): String? {
        return if (passwordRegex.matches(password)) null
        else "Mínimo 8 caracteres, una mayúscula, una minúscula y un número"
    }

    fun validateConfirmPassword(password: String, confirmPassword: String): String? {
        return if (password == confirmPassword) null
        else "Las contraseñas no coinciden"
    }

    fun calcularRangoPesoNormal(alturaCm: Float): Pair<Int, Int> {
        val alturaM = alturaCm / 100f
        val pesoMin = 18.5 * (alturaM * alturaM)
        val pesoMax = 24.9 * (alturaM * alturaM)
        return Pair(pesoMin.toInt(), pesoMax.toInt())
    }

    fun calcularEdadCalendario(fechaNacimiento: String): Int {
        return try {
            val formato = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val fecha = formato.parse(fechaNacimiento)
            val dob = Calendar.getInstance().apply { time = fecha!! }
            val hoy = Calendar.getInstance()

            var edad = hoy.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
            if (hoy.get(Calendar.DAY_OF_YEAR) < dob.get(Calendar.DAY_OF_YEAR)) {
                edad--
            }
            edad
        } catch (e: Exception) {
            0
        }
    }

    fun calcularEdadReg7(fechaNacimiento: String): Int {
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val fecha = LocalDate.parse(fechaNacimiento, formatter)
            val hoy = LocalDate.now()
            Period.between(fecha, hoy).years
        } catch (e: Exception) {
            25 // edad por defecto en caso de error
        }
    }

    fun registrarUsuario(onResultado: (Boolean) -> Unit) {
        viewModelScope.launch {
            cargando = true
            registroExitoso = false
            errorRegistro = null

            // Validaciones locales
            nombreValidationError = validateNombre(nombre)
            correoValidationError = validateEmail(correo)
            contrasenaValidationError = validatePassword(contrasena)
            confirmarContrasenaValidationError = validateConfirmPassword(contrasena, confirmarContrasena)

            if (correoValidationError == null && correo.isNotBlank()) {
                if (repositoryUsuario.existeCorreo(correo)) {
                    correoValidationError = "El correo ya está registrado"
                }
            }

            if (nombreValidationError == null && nombre.isNotBlank()) {
                if (repositoryUsuario.existeNombre(nombre)) {
                    nombreValidationError = "El nombre de usuario ya está en uso"
                }
            }

            if (
                nombreValidationError != null ||
                correoValidationError != null ||
                contrasenaValidationError != null ||
                confirmarContrasenaValidationError != null
            ) {
                cargando = false
                onResultado(false)
                return@launch
            }

            val usuario = UsuarioEntrada(
                nombre = nombre,
                correo = correo,
                contrasena = contrasena,
                fechaNacimiento = fechaNacimiento,
                altura = altura,
                peso = peso,
                sexo = sexo,
                restriccionesDieta = restriccionesDieta,
                objetivosSalud = objetivosSalud,
                nivelActividad = nivelActividad,
                pesoObjetivo = pesoObjetivo
            )

            val result = repositoryUsuario.registrarUsuario(usuario)

            result.fold(
                onSuccess = { usuarioRespuesta ->
                    registroExitoso = true
                    UserPreferences.guardarIdUsuario(context, usuarioRespuesta.idUsuario)

                    // Consolidar favoritos por categoría antes de guardar
                    consolidarFavoritosPorCategoria()

                    try {
                        kotlinx.coroutines.coroutineScope {
                            alimentosFavoritos.map { alimento ->
                                launch {
                                    try {
                                        alimentoService.marcarFavorito(
                                            usuarioRespuesta.idUsuario,
                                            alimento.idAlimento
                                        )
                                    } catch (e: Exception) {
                                        Log.e("UsuarioViewModel", "Error guardando favorito: ${e.message}")
                                    }
                                }
                            }.joinAll()
                        }
                    } catch (e: Exception) {
                        Log.e("UsuarioViewModel", "Error en favoritos: ${e.message}")
                    }

                    cargando = false
                    onResultado(true)
                },
                onFailure = { throwable ->
                    errorRegistro = throwable.message ?: "Error inesperado al registrar"
                    cargando = false
                    onResultado(false)
                }
            )
        }
    }

    fun toggleFavoritoConLimite(alimento: Alimento, categoria: String) {
        val actuales = favoritosPorCategoria[categoria]?.toMutableList() ?: mutableListOf()

        if (actuales.any { it.idAlimento == alimento.idAlimento }) {
            actuales.removeAll { it.idAlimento == alimento.idAlimento }
        } else {
            if (actuales.size < 3) {
                actuales.add(alimento)
            }
        }
        favoritosPorCategoria[categoria] = actuales.toList()
    }

    fun consolidarFavoritosPorCategoria() {
        alimentosFavoritos.clear()
        favoritosPorCategoria.values.flatten().distinctBy { it.idAlimento }.forEach {
            alimentosFavoritos.add(it)
        }
    }

    fun eliminarCuenta(idUsuario: Long) {
        viewModelScope.launch {
            try {
                // 1. Eliminar en backend
                val result = repositoryUsuario.eliminarCuenta(idUsuario)

                result.fold(
                    onSuccess = {
                        // 2. Limpiar DataStore
                        UserPreferences.limpiarDatos(context)
                        // 3. Actualizar el estado
                        _eliminacionState.value = Result.success(Unit)
                    },
                    onFailure = { throwable ->
                        _eliminacionState.value = Result.failure(throwable)
                    }
                )
            } catch (e: Exception) {
                _eliminacionState.value = Result.failure(e)
            }
        }
    }

    // Metodos para actualizar datos en la pestaña perfil
    // Metodo para actualizar la altura
    fun actualizarAltura(idUsuario: Long, altura: Float) {
        viewModelScope.launch {
            _actualizacionAlturaState.value = repositoryUsuario.actualizarAltura(idUsuario, altura)
        }
    }

    // Métodos para cambiar el estado de la edición
    fun toggleEditingAltura() {
        isEditingAltura = !isEditingAltura
    }

    fun actualizarPeso(idUsuario: Long, peso: Float) {
        viewModelScope.launch {
            _actualizacionPesoState.value = repositoryUsuario.actualizarPeso(idUsuario, peso)
        }
    }

    fun toggleEditingPeso() {
        isEditingPeso = !isEditingPeso
    }

    fun actualizarPesoObjetivo(idUsuario: Long, pesoObjetivo: Float) {
        viewModelScope.launch {
            _actualizacionPesoObjetivoState.value = repositoryUsuario.actualizarPesoObjetivo(idUsuario, pesoObjetivo)
        }
    }

    fun toggleEditingPesoObjetivo() {
        isEditingPesoObjetivo = !isEditingPesoObjetivo
    }

    fun actualizarCorreo(idUsuario: Long, correo: String) {
        viewModelScope.launch {
            val error = validateEmail(correo)

            if (error == null) {
                // El correo es válido, procede con la actualización
                _actualizacionCorreoState.value = repositoryUsuario.actualizarCorreo(idUsuario, correo)
            } else {
                // El correo no es válido, establece un mensaje de error y un estado de fallo
                _actualizacionCorreoState.value = Result.failure(IllegalArgumentException(error))
            }
        }
    }

    fun toggleEditingCorreo() {
        isEditingCorreo = !isEditingCorreo
    }

    fun actualizarDieta(idUsuario: Long, dieta: String) {
        viewModelScope.launch {
            _actualizacionDietaState.value = repositoryUsuario.actualizarDieta(idUsuario, dieta)
        }
    }

    fun toggleEditingDieta() {
        isEditingDieta = !isEditingDieta
    }

    fun actualizarObjetivo(idUsuario: Long, objetivo: String) {
        viewModelScope.launch {
            _actualizacionObjetivoState.value = repositoryUsuario.actualizarObjetivo(idUsuario, objetivo)
        }
    }

    fun toggleEditingObjetivo() {
        isEditingObjetivo = !isEditingObjetivo
    }

    fun actualizarNivelAct(idUsuario: Long, nivelAct: String) {
        viewModelScope.launch {
            _actualizacionNivelActState.value = repositoryUsuario.actualizarNivelAct(idUsuario, nivelAct)
        }
    }

    fun toggleEditingNivelAct() {
        isEditingNivelAct = !isEditingNivelAct
    }

    fun clearActualizacionState() {
        _actualizacionAlturaState.value = null
        _actualizacionPesoState.value = null
        _actualizacionPesoObjetivoState.value = null
        _actualizacionCorreoState.value = null
        _actualizacionDietaState.value = null
        _actualizacionObjetivoState.value = null
        _actualizacionNivelActState.value = null
    }

    // Metodos para manejar los recordatorios
    fun loadReminder(context: Context, userId: Long, tipo: String) {
        viewModelScope.launch {
            val (activo, hora) = ReminderDataStore.getReminderFlow(context, userId, tipo).first()
            _reminders[tipo] = ReminderState(activo, hora)
            Log.d("RecordatorioDebug", "📥 Cargado de DataStore -> userId=$userId, tipo=$tipo, activo=$activo, hora=$hora")
        }
    }

    fun updateReminder(
        context: Context,
        userId: Long,
        tipo: String,
        activo: Boolean? = null,
        hora: String? = null
    ) {
        val previo = _reminders[tipo] ?: ReminderState()
        val nuevoActivo = activo ?: previo.activo
        val nuevaHora = hora ?: previo.hora

        _reminders[tipo] = ReminderState(nuevoActivo, nuevaHora)
        Log.d("RecordatorioDebug", "🔄 updateReminder -> userId=$userId, tipo=$tipo, activo=$nuevoActivo, hora=$nuevaHora")

        viewModelScope.launch {
            ReminderDataStore.saveReminder(context, userId, tipo, nuevoActivo, nuevaHora)
            Log.d("RecordatorioDebug", "💾 Guardado desde updateReminder -> userId=$userId, tipo=$tipo, activo=$nuevoActivo, hora=$nuevaHora")

            val (h, m) = parseHora24(nuevaHora)
            if (nuevoActivo) {
                Log.d("RecordatorioDebug", "⏰ Programando alarma -> $tipo a las $h:$m (24h)")
                AlarmHelper.scheduleDailyReminder(context, userId, tipo, h, m)
            } else {
                Log.d("RecordatorioDebug", "❌ Cancelando alarma -> $tipo")
                AlarmHelper.cancelReminder(context, userId, tipo)
            }
        }
    }

    private fun parseHora24(hora: String): Pair<Int, Int> {
        val parts = hora.split("[: ]".toRegex())
        var h = parts[0].toInt()
        val m = parts[1].toInt()
        val amPm = if (parts.size == 3) parts[2] else ""
        if (amPm == "PM" && h < 12) h += 12
        if (amPm == "AM" && h == 12) h = 0
        return h to m
    }
}
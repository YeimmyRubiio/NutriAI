package com.example.frontendproyectoapp.screen

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.frontendproyectoapp.DataStores.UserPreferences
import kotlinx.coroutines.flow.map
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import com.example.frontendproyectoapp.model.Alimento
import com.example.frontendproyectoapp.model.RegistroAlimentoSalida
import com.example.frontendproyectoapp.model.Usuario
import com.example.frontendproyectoapp.viewModel.AlimentoViewModel
import com.example.frontendproyectoapp.viewModel.AlimentoViewModelFactory
import com.example.frontendproyectoapp.viewModel.ChatbotViewModel
import com.example.frontendproyectoapp.viewModel.ChatbotViewModelFactory
import com.example.frontendproyectoapp.viewModel.UsuarioViewModel
import com.example.frontendproyectoapp.viewModel.UsuarioViewModelFactory

@Composable
fun RutinaScreen(navController: NavHostController) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: AlimentoViewModel = viewModel(factory = AlimentoViewModelFactory(application))

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val lifecycle = currentBackStackEntry?.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.actualizarUsuarioYDatos()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    RutinaScreenContent(viewModel, navController)
}

@Composable
fun RutinaScreenContent(viewModel: AlimentoViewModel, navController: NavHostController) {
    val focusManager = LocalFocusManager.current
    var showChatbot by remember { mutableStateOf(false) }
    
    // Chatbot ViewModel
    val chatbotViewModel: ChatbotViewModel = viewModel(factory = ChatbotViewModelFactory(LocalContext.current.applicationContext as Application))
    val messages by chatbotViewModel.messages.collectAsState()
    val isLoading by chatbotViewModel.isLoading.collectAsState()
    val error by chatbotViewModel.error.collectAsState()
    
    // Usuario ViewModel para obtener datos del perfil
    val usuarioViewModel: UsuarioViewModel = viewModel(factory = UsuarioViewModelFactory(LocalContext.current.applicationContext as Application))
    
    // Obtener perfil del usuario y rutina actual
    val currentRoutine = viewModel.comidasRecientes
    
    // Debugging de la rutina actual
    LaunchedEffect(currentRoutine) {
        println("=== CURRENT ROUTINE DEBUG ===")
        println("CurrentRoutine: ${currentRoutine.size} elementos")
        println("CurrentRoutine detalle: ${currentRoutine.map { "${it.alimento.nombreAlimento} (${it.momentoDelDia})" }}")
    }
    
    // Estado para el perfil del usuario
    var userProfile by remember { mutableStateOf<Usuario?>(null) }
    
    // Debugging del userProfile
    LaunchedEffect(userProfile) {
        println("=== USER PROFILE DEBUG ===")
        println("UserProfile: $userProfile")
        println("UserProfile ID: ${userProfile?.idUsuario}")
        println("UserProfile nombre: ${userProfile?.nombre}")
    }
    
    // Obtener ID del usuario actualmente logueado
    val idUsuario by UserPreferences.obtenerIdUsuario(LocalContext.current)
        .map { it ?: 0L }
        .collectAsState(initial = 0L)
    
    // Observar cambios en la rutina del chatbot
    LaunchedEffect(Unit) {
        chatbotViewModel.routineUpdated.collect { isUpdated ->
            if (isUpdated) {
                println("=== RUTINA ACTUALIZADA DESDE CHATBOT ===")
                // Recargar la rutina cuando se actualiza
                viewModel.cargarComidasRecientes()
                // Limpiar la notificación
                chatbotViewModel.clearRoutineUpdateNotification()
                println("✅ Rutina recargada después de cambios en chatbot")
            }
        }
    }
    
    // Manejar apertura del chatbot
    LaunchedEffect(showChatbot) {
        println("=== LAUNCHED EFFECT SHOWCHATBOT: $showChatbot ===")
        if (showChatbot) {
            println("=== INICIANDO APERTURA DEL CHATBOT ===")
            // Iniciar sesión inmediatamente sin delay
            chatbotViewModel.startNewSession(idUsuario)
            println("=== SESIÓN INICIADA INMEDIATAMENTE ===")
        }
    }
    
    // Cargar datos del usuario de forma asíncrona cuando se abre el chatbot
    LaunchedEffect(showChatbot) {
        if (showChatbot && idUsuario != 0L) {
            println("=== CARGANDO DATOS DEL USUARIO EN BACKGROUND ===")
            // Cargar datos del usuario
            usuarioViewModel.cargarUsuario(idUsuario)
            viewModel.cargarComidasRecientes()
            
            // Esperar a que los datos se carguen realmente
            var intentos = 0
            val maxIntentos = 10
            while (intentos < maxIntentos) {
                kotlinx.coroutines.delay(500)
                println("=== INTENTO ${intentos + 1} DE CARGA DE DATOS ===")
                println("Nombre usuario: '${usuarioViewModel.nombreUsuario}'")
                println("Peso usuario: '${usuarioViewModel.pesoUsuario}'")
                println("Altura usuario: '${usuarioViewModel.alturaUsuario}'")
                
                // Verificar si los datos se cargaron correctamente
                if (usuarioViewModel.nombreUsuario.isNotBlank() && 
                    usuarioViewModel.nombreUsuario != "Usuario" &&
                    usuarioViewModel.pesoUsuario.isNotBlank() &&
                    usuarioViewModel.alturaUsuario.isNotBlank()) {
                    println("✅ DATOS CARGADOS CORRECTAMENTE")
                    break
                }
                intentos++
            }
            
            // Construir el perfil del usuario
            val userProfile = buildUserProfileAfterLoad(usuarioViewModel, idUsuario)
            println("=== PERFIL CONSTRUIDO EN BACKGROUND: $userProfile ===")
            
            // Actualizar el perfil del usuario en el ChatbotViewModel
            chatbotViewModel.updateUserProfile(userProfile)
            chatbotViewModel.updateCurrentRoutine(viewModel.comidasRecientes)
            println("=== DATOS ACTUALIZADOS EN CHATBOT VIEWMODEL ===")
        }
    }
    
    // Manejar cierre del chatbot
    LaunchedEffect(showChatbot) {
        println("=== LAUNCHED EFFECT SHOWCHATBOT (CIERRE): $showChatbot ===")
        if (!showChatbot) {
            println("=== CERRANDO CHATBOT ===")
            // Cerrar sesión cuando se cierra el chatbot
            chatbotViewModel.endSession()
        }
    }
    
    // Mostrar errores si los hay
    val context = LocalContext.current
    error?.let { errorMessage ->
        LaunchedEffect(errorMessage) {
            Toast.makeText(
                context,
                errorMessage,
                Toast.LENGTH_LONG
            ).show()
            chatbotViewModel.clearError()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomNavigationBar(navController) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    println("=== BOTÓN CHATBOT CLICKEADO ===")
                    println("showChatbot antes: $showChatbot")
                    showChatbot = true
                    println("showChatbot después: $showChatbot")
                },
                modifier = Modifier.size(56.dp),
                containerColor = Color(0xFF4CAF50), // Verde claro como en tu imagen
                contentColor = Color.White
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Chatbot",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { focusManager.clearFocus() },
        ) {
            Text(
                "Rútina y Consumo de alimentos",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Divider(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            SeccionConsumoDiario(viewModel, navController)
        }
        
        // Chatbot Dialog - Fuera del contenido del Scaffold pero dentro del composable
        ChatbotDialog(
            isVisible = showChatbot,
            onDismiss = { 
                println("=== CHATBOT DISMISS LLAMADO ===")
                showChatbot = false 
            },
            messages = messages,
            onSendMessage = { message ->
                // El ChatbotViewModel ahora maneja el perfil del usuario y la rutina internamente
                chatbotViewModel.sendMessage(message, userProfile, currentRoutine)
            },
            isLoading = isLoading,
            onClearMessages = { chatbotViewModel.clearMessages() },
            onAddWelcomeMessage = { chatbotViewModel.addWelcomeMessage() }
        )
    }
}

@Composable
fun SeccionConsumoDiario(viewModel: AlimentoViewModel, navController: NavHostController) {
    Text(
        text = "Resumen de Consumo Diario",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 12.dp)
    )

    val comidasAgrupadas = viewModel.comidasRecientes.groupBy { it.momentoDelDia }
    val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
    var registroAEliminar by remember { mutableStateOf<RegistroAlimentoSalida?>(null) }

    momentos.forEach { momento ->
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = momento,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.eliminarRegistrosPorMomentoYFecha(momento) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Eliminar",
                                tint = MaterialTheme.colorScheme.error,
                                //modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                viewModel.mostrarDialogoRegistro.value = true
                                viewModel.momentoSeleccionado = momento
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Agregar",
                                tint = MaterialTheme.colorScheme.primary,
                                //modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val registros = comidasAgrupadas[momento].orEmpty()

                if (registros.isEmpty()) {
                    Text(
                        text = "No has registrado los alimentos para $momento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    registros.forEach { registro ->
                        AlimentoRutinaItem(
                            alimento = registro.alimento,
                            esFavorito = viewModel.esFavorito(registro.alimento.idAlimento),
                            onClick = {
                                navController.navigate("detalleAlimento/${registro.alimento.idAlimento}")
                            },
                            onToggleFavorito = {
                                viewModel.toggleFavorito(registro.alimento)
                            },
                            onEliminarFavorito = {
                                registroAEliminar = registro
                            },
                            mostrarFavorito = false
                        )
                    }
                }
            }
        }
    }

    DialogoRegistroAlimento(viewModel)

    // Diálogo de confirmación
    registroAEliminar?.let {
        AlertDialog(
            onDismissRequest = { registroAEliminar = null },
            title = { Text("Eliminar alimento") },
            text = { Text("¿Estás seguro de que deseas eliminar este alimento del registro?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.eliminarRegistroIndividual(it.idRegistroAlimento)
                    registroAEliminar = null
                }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { registroAEliminar = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

// Diálogo de registro adaptado visualmente
@Composable
fun DialogoRegistroAlimento(
    viewModel: AlimentoViewModel
) {
    if (!viewModel.mostrarDialogoRegistro.value) return

    val contexto = LocalContext.current
    val listaAlimentos = viewModel.listaAlimentos

    var alimentoSeleccionado by remember { mutableStateOf<Alimento?>(null) }
    var cantidadTexto by remember { mutableStateOf("") }
    var unidadTexto by remember { mutableStateOf("") }

    var alimentoExpanded by remember { mutableStateOf(false) }
    var unidadExpanded by remember { mutableStateOf(false) }

    var alimentoFieldPx by remember { mutableStateOf(0) }
    var unidadFieldPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    // Observar las unidades desde el ViewModel
    val unidadesDisponibles by viewModel.unidades

    AlertDialog(
        onDismissRequest = {
            viewModel.mostrarDialogoRegistro.value = false
            alimentoSeleccionado = null
            cantidadTexto = ""
            unidadTexto = ""
        },
        title = { Text("Registrar Alimento", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                // Selector de alimento
                Box {
                    OutlinedTextField(
                        value = alimentoSeleccionado?.nombreAlimento ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Selecciona un alimento") },
                        trailingIcon = {
                            IconButton(onClick = { alimentoExpanded = !alimentoExpanded }) {
                                Icon(
                                    if (alimentoExpanded) Icons.Default.KeyboardArrowUp
                                    else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { alimentoFieldPx = it.size.width }
                    )

                    DropdownMenu(
                        expanded = alimentoExpanded,
                        onDismissRequest = { alimentoExpanded = false },
                        modifier = Modifier.width(with(density) { alimentoFieldPx.toDp() })
                    ) {
                        listaAlimentos.forEach { alimento ->
                            DropdownMenuItem(
                                text = { Text(alimento.nombreAlimento) },
                                onClick = {
                                    alimentoSeleccionado = alimento
                                    alimentoExpanded = false
                                    unidadTexto = ""
                                    // Cargar unidades según el alimento seleccionado
                                    viewModel.cargarUnidadesPorId(alimento.idAlimento)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Cantidad
                OutlinedTextField(
                    value = cantidadTexto,
                    onValueChange = { cantidadTexto = it },
                    label = { Text("Cantidad") },
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Selector de unidad dinámico
                Box {
                    OutlinedTextField(
                        value = unidadTexto,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Unidad de medida") },
                        trailingIcon = {
                            IconButton(onClick = { unidadExpanded = !unidadExpanded }) {
                                Icon(
                                    if (unidadExpanded) Icons.Default.KeyboardArrowUp
                                    else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { unidadExpanded = true }
                            .onGloballyPositioned { unidadFieldPx = it.size.width }
                    )

                    DropdownMenu(
                        expanded = unidadExpanded,
                        onDismissRequest = { unidadExpanded = false },
                        modifier = Modifier.width(with(density) { unidadFieldPx.toDp() })
                    ) {
                        unidadesDisponibles.forEach { unidad ->
                            DropdownMenuItem(
                                text = { Text(unidad) },
                                onClick = {
                                    unidadTexto = unidad
                                    unidadExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cantidad = cantidadTexto.toFloatOrNull()
                if (alimentoSeleccionado != null && unidadTexto.isNotBlank() && cantidad != null) {
                    viewModel.registrarAlimentoDesdeDialogo(
                        idAlimento = alimentoSeleccionado!!.idAlimento,
                        cantidad = cantidad,
                        unidad = unidadTexto,
                        momento = viewModel.momentoSeleccionado
                    )
                    viewModel.mostrarDialogoRegistro.value = false
                    alimentoSeleccionado = null
                    cantidadTexto = ""
                    unidadTexto = ""
                    Toast.makeText(contexto, "Alimento registrado con éxito", Toast.LENGTH_SHORT).show()
                    viewModel.cargarComidasRecientes()
                } else {
                    Toast.makeText(contexto, "Completa todos los campos correctamente", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Registrar", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.mostrarDialogoRegistro.value = false }) {
                Text("Cancelar", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun AlimentoRutinaItem(
    alimento: Alimento,
    esFavorito: Boolean,
    onClick: () -> Unit,
    onToggleFavorito: () -> Unit,
    onEliminarFavorito: (() -> Unit)? = null,
    mostrarFavorito: Boolean = true
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Imagen del alimento
            Image(
                painter = rememberAsyncImagePainter(alimento.urlImagen),
                contentDescription = "Imagen del alimento",
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = alimento.nombreAlimento,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = alimento.categoria,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                if (mostrarFavorito) {
                    IconButton(onClick = onToggleFavorito) {
                        Icon(
                            imageVector = if (esFavorito) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorito",
                            tint = if (esFavorito)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                }

                onEliminarFavorito?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Eliminar",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// Función auxiliar para construir el perfil del usuario
@Composable
fun buildUserProfile(usuarioViewModel: UsuarioViewModel): Usuario? {
    println("=== BUILD USER PROFILE ===")
    println("Nombre del usuario desde ViewModel: '${usuarioViewModel.nombreUsuario}'")
    println("Correo del usuario desde ViewModel: '${usuarioViewModel.correoUsuario}'")
    
    // Obtener ID real del usuario loggeado
    val idUsuario by UserPreferences.obtenerIdUsuario(LocalContext.current)
        .map { it ?: 0L }
        .collectAsState(initial = 0L)
    
    println("ID Usuario real obtenido: $idUsuario")
    
    return Usuario(
        idUsuario = idUsuario,
        nombre = usuarioViewModel.nombreUsuario,
        correo = usuarioViewModel.correoUsuario,
        contrasena = "", // No necesitamos la contraseña para el chatbot
        fechaNacimiento = usuarioViewModel.fechaNacimientoUsuario,
        altura = usuarioViewModel.alturaUsuario.toFloatOrNull() ?: 0f,
        peso = usuarioViewModel.pesoUsuario.toFloatOrNull() ?: 0f,
        sexo = usuarioViewModel.sexoUsuario,
        pesoObjetivo = usuarioViewModel.pesoObjetivoUsuario.toFloatOrNull() ?: 0f,
        restriccionesDieta = usuarioViewModel.restriccionesDietaUsuario,
        objetivosSalud = usuarioViewModel.objetivosSaludUsuario,
        nivelActividad = usuarioViewModel.nivelActividadUsuario
    ).also {
        println("✅ Usuario creado exitosamente:")
        println("  - ID: ${it.idUsuario}")
        println("  - Nombre: ${it.nombre}")
        println("  - Peso: ${it.peso}")
        println("  - Altura: ${it.altura}")
        println("  - Objetivos: ${it.objetivosSalud}")
    }
}

// Función para construir el perfil después de cargar los datos
fun buildUserProfileAfterLoad(usuarioViewModel: UsuarioViewModel, idUsuario: Long): Usuario {
    println("=== BUILD USER PROFILE AFTER LOAD ===")
    println("ID Usuario pasado: $idUsuario")
    println("Nombre del usuario desde ViewModel: '${usuarioViewModel.nombreUsuario}'")
    println("Correo del usuario desde ViewModel: '${usuarioViewModel.correoUsuario}'")
    println("Peso del usuario desde ViewModel: '${usuarioViewModel.pesoUsuario}'")
    println("Altura del usuario desde ViewModel: '${usuarioViewModel.alturaUsuario}'")
    println("Sexo del usuario desde ViewModel: '${usuarioViewModel.sexoUsuario}'")
    println("Restricciones dieta desde ViewModel: '${usuarioViewModel.restriccionesDietaUsuario}'")
    println("Objetivos salud desde ViewModel: '${usuarioViewModel.objetivosSaludUsuario}'")
    println("Nivel actividad desde ViewModel: '${usuarioViewModel.nivelActividadUsuario}'")
    
    // Función helper para detectar valores por defecto
    fun isDefaultValue(value: String): Boolean {
        return value.contains("Usuario") || value.isBlank() || value == "0.0" || value == "0"
    }
    
    // Usar valores por defecto apropiados si los datos no están cargados
    val nombre = if (isDefaultValue(usuarioViewModel.nombreUsuario)) "Usuario" else usuarioViewModel.nombreUsuario
    val peso = if (isDefaultValue(usuarioViewModel.pesoUsuario)) 0f else usuarioViewModel.pesoUsuario.toFloatOrNull() ?: 0f
    val altura = if (isDefaultValue(usuarioViewModel.alturaUsuario)) 0f else usuarioViewModel.alturaUsuario.toFloatOrNull() ?: 0f
    val sexo = if (isDefaultValue(usuarioViewModel.sexoUsuario)) "" else usuarioViewModel.sexoUsuario
    val pesoObjetivo = if (isDefaultValue(usuarioViewModel.pesoObjetivoUsuario)) 0f else usuarioViewModel.pesoObjetivoUsuario.toFloatOrNull() ?: 0f
    val restriccionesDieta = if (isDefaultValue(usuarioViewModel.restriccionesDietaUsuario)) "" else usuarioViewModel.restriccionesDietaUsuario
    val objetivosSalud = if (isDefaultValue(usuarioViewModel.objetivosSaludUsuario)) "" else usuarioViewModel.objetivosSaludUsuario
    val nivelActividad = if (isDefaultValue(usuarioViewModel.nivelActividadUsuario)) "" else usuarioViewModel.nivelActividadUsuario
    
    return Usuario(
        idUsuario = idUsuario,
        nombre = nombre,
        correo = usuarioViewModel.correoUsuario,
        contrasena = "", // No necesitamos la contraseña para el chatbot
        fechaNacimiento = usuarioViewModel.fechaNacimientoUsuario,
        altura = altura,
        peso = peso,
        sexo = sexo,
        pesoObjetivo = pesoObjetivo,
        restriccionesDieta = restriccionesDieta,
        objetivosSalud = objetivosSalud,
        nivelActividad = nivelActividad
    ).also {
        println("✅ Usuario creado exitosamente después de cargar:")
        println("  - ID: ${it.idUsuario}")
        println("  - Nombre: ${it.nombre}")
        println("  - Peso: ${it.peso}")
        println("  - Altura: ${it.altura}")
        println("  - Sexo: ${it.sexo}")
        println("  - Objetivos: ${it.objetivosSalud}")
        println("  - Restricciones: ${it.restriccionesDieta}")
        println("  - Nivel actividad: ${it.nivelActividad}")
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewRutinaScreen() {
    val navController = rememberNavController()
    BuscarAlimentoScreen(navController = navController)
}

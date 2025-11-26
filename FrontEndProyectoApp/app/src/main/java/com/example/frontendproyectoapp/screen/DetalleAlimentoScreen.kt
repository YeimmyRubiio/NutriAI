package com.example.frontendproyectoapp.screen

import android.app.Application
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.frontendproyectoapp.interfaces.AlimentoService
import com.example.frontendproyectoapp.interfaces.RetrofitClient
import com.example.frontendproyectoapp.model.Alimento
import com.example.frontendproyectoapp.viewModel.AlimentoViewModel
import com.example.frontendproyectoapp.viewModel.AlimentoViewModelFactory
import com.example.frontendproyectoapp.viewModel.DetalleAlimentoViewModel
import com.example.frontendproyectoapp.viewModel.DetalleAlimentoViewModelFactory
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleAlimentoScreen(
    idAlimento: Long,
    navController: NavHostController,
    cantidadInicial: String = "",
    unidadInicial: String = "",
    momentoInicial: String = "",
    desdeRutina: Boolean = false
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: DetalleAlimentoViewModel = viewModel(
        factory = DetalleAlimentoViewModelFactory(application)
    )
    val alimentoViewModel: AlimentoViewModel = viewModel(factory = AlimentoViewModelFactory(application))

    val alimentoService = RetrofitClient.createService(AlimentoService::class.java)
    val alimentoState = remember { mutableStateOf<Alimento?>(null) }

    LaunchedEffect(idAlimento) {
        try {
            val alimento = alimentoService.listarIdAlimento(idAlimento)
            alimentoState.value = alimento
            alimento?.let { alimentoViewModel.cargarUnidadesPorId(it.idAlimento) }
        } catch (e: Exception) {
            Log.e("DetalleAlimento", "Error: ${e.message}")
        }
    }

    DetalleAlimentoScreenContent(
        alimento = alimentoState.value,
        navController = navController,
        viewModel = viewModel,
        onBackClick = { navController.popBackStack() },
        alimentoViewModel = alimentoViewModel,
        cantidadInicial = cantidadInicial,
        unidadInicial = unidadInicial,
        momentoInicial = momentoInicial,
        desdeRutina = desdeRutina
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleAlimentoScreenContent(
    alimento: Alimento?,
    navController: NavHostController,
    viewModel: DetalleAlimentoViewModel,
    onBackClick: () -> Unit = {},
    alimentoViewModel: AlimentoViewModel,
    cantidadInicial: String = "",
    unidadInicial: String = "",
    momentoInicial: String = "",
    desdeRutina: Boolean = false
) {
    val focusManager = LocalFocusManager.current
    
    // Formatear cantidad inicial a 1 decimal si viene desde rutina
    val cantidadFormateada = if (cantidadInicial.isNotBlank()) {
        try {
            val cantidadFloat = cantidadInicial.replace(",", ".").toFloatOrNull() ?: 0f
            "%.1f".format(cantidadFloat).replace(",", ".")
        } catch (e: Exception) {
            cantidadInicial
        }
    } else {
        "1"
    }
    
    var cantidad by remember { mutableStateOf(cantidadFormateada) }
    var unidadSeleccionada by remember { mutableStateOf(if (unidadInicial.isNotBlank()) unidadInicial else "Unidad de medida") }
    var unidadExpanded by remember { mutableStateOf(false) }

    var momentoSeleccionado by remember { mutableStateOf(if (momentoInicial.isNotBlank()) momentoInicial else "Momento del día") }
    var momentoExpanded by remember { mutableStateOf(false) }

    val momentos = listOf("Desayuno", "Almuerzo", "Cena", "Snack")
    val unidadesDisponibles by alimentoViewModel.unidades

    if (alimento == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    // La cantidad puede venir en diferentes unidades (gramos, porción, unidad, etc.)
    // Si la unidad es "gramos", usamos la cantidad directamente
    // Si la unidad es otra, necesitamos convertir a gramos usando cantidadBase del alimento
    val cantidadFloat = cantidad.replace(",", ".").toFloatOrNull() ?: 1f
    val unidadLower = unidadSeleccionada.lowercase()
    
    // Convertir cantidad a gramos si es necesario
    val cantidadEnGramos = if (unidadLower.contains("gramo") || unidadLower == "g") {
        // Ya está en gramos
        cantidadFloat
    } else {
        // Convertir a gramos usando cantidadBase (por ejemplo, si es "porción", multiplicar por cantidadBase)
        cantidadFloat * alimento.cantidadBase
    }
    
    // Los valores nutricionales están por 100g, dividir por 100 para obtener el factor correcto
    val factorNutricional = cantidadEnGramos / 100f
    
    val totalCalorias = (alimento.calorias * factorNutricional).roundToInt()
    val totalProteinas = alimento.proteinas * factorNutricional
    val totalCarbs = alimento.carbohidratos * factorNutricional
    val totalGrasas = alimento.grasas * factorNutricional

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Detalles Alimento",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Atrás"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = { BottomNavigationBar(navController) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(alimento.nombreAlimento, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text("Genérico", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Text("Datos por 100 ${alimento.unidadBase} (${alimento.cantidadBase}g)", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.CenterHorizontally)
                    .border(8.dp, MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Text("$totalCalorias kcal", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NutrienteTag("Proteínas", totalProteinas, MaterialTheme.colorScheme.primary)
                NutrienteTag("Carbs", totalCarbs, MaterialTheme.colorScheme.secondary)
                NutrienteTag("Grasas", totalGrasas, MaterialTheme.colorScheme.tertiary)
            }

            Spacer(Modifier.height(24.dp))

            // TextField cantidad estilo Card + borde de card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                BasicTextField(
                    value = cantidad,
                    onValueChange = { if (!desdeRutina) cantidad = it.filter { it.isDigit() || it == '.' } },
                    readOnly = desdeRutina,
                    enabled = !desdeRutina,
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = if (desdeRutina) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) { innerTextField ->
                    Box {
                        if (cantidad.isEmpty() && !desdeRutina) {
                            Text("Cantidad", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        innerTextField()
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            DropdownSelector(
                label = "Unidad de medida",
                selected = unidadSeleccionada,
                options = unidadesDisponibles,
                expanded = unidadExpanded && !desdeRutina,
                onExpandedChange = { if (!desdeRutina) unidadExpanded = it },
                enabled = !desdeRutina,
                onItemSelected = {
                    if (!desdeRutina) {
                        unidadSeleccionada = it
                        unidadExpanded = false
                    }
                }
            )

            Spacer(Modifier.height(8.dp))

            DropdownSelector(
                label = "Momento del día",
                selected = momentoSeleccionado,
                options = momentos,
                expanded = momentoExpanded && !desdeRutina,
                onExpandedChange = { if (!desdeRutina) momentoExpanded = it },
                enabled = !desdeRutina,
                onItemSelected = {
                    if (!desdeRutina) {
                        momentoSeleccionado = it
                        momentoExpanded = false
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (unidadSeleccionada.isNotBlank() && cantidadFloat > 0f && !desdeRutina) {
                        viewModel.registrarAlimento(
                            idAlimento = alimento.idAlimento,
                            cantidad = cantidadFloat,
                            unidad = unidadSeleccionada,
                            momento = momentoSeleccionado
                        )
                        navController.popBackStack()
                    }
                },
                enabled = !desdeRutina && unidadSeleccionada != "Unidad de medida" && momentoSeleccionado != "Momento del día",
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    if (desdeRutina) "Vista desde rutina (solo lectura)" else "Ingresar a $momentoSeleccionado",
                    color = if (desdeRutina) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
fun NutrienteTag(nombre: String, valor: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(color, shape = CircleShape)
        )
        Spacer(Modifier.height(4.dp))
        Text(nombre, style = MaterialTheme.typography.bodySmall)
        Text("${"%.1f".format(valor)} g", style = MaterialTheme.typography.bodyMedium)
    }
}
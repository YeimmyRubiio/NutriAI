package com.example.frontendproyectoapp.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.frontendproyectoapp.screen.AlimentosFavoritosScreen
import com.example.frontendproyectoapp.screen.BuscarAlimentoScreen
import com.example.frontendproyectoapp.screen.ConfiguracionPerfilScreen
import com.example.frontendproyectoapp.screen.ConfiguracionRecordatorioScreen
import com.example.frontendproyectoapp.screen.ConfiguracionScreen
import com.example.frontendproyectoapp.screen.DetalleAlimentoScreen
import com.example.frontendproyectoapp.screen.EstadisticasScreen
import com.example.frontendproyectoapp.screen.GeminiConfigScreen
import com.example.frontendproyectoapp.screen.InicioScreen
import com.example.frontendproyectoapp.screen.LoginScreen
import com.example.frontendproyectoapp.screen.RegistroVent10Screen
import com.example.frontendproyectoapp.screen.RegistroVent1Screen
import com.example.frontendproyectoapp.screen.RegistroVent2Screen
import com.example.frontendproyectoapp.screen.RegistroVent3Screen
import com.example.frontendproyectoapp.screen.RegistroVent4Screen
import com.example.frontendproyectoapp.screen.RegistroVent5Screen
import com.example.frontendproyectoapp.screen.RegistroVent6Screen
import com.example.frontendproyectoapp.screen.RegistroVent7Screen
import com.example.frontendproyectoapp.screen.RegistroVent8Screen
import com.example.frontendproyectoapp.screen.RegistroVent9Screen
import com.example.frontendproyectoapp.screen.RutinaScreen
import com.example.frontendproyectoapp.screen.SplashScreen
import com.example.frontendproyectoapp.viewModel.UsuarioViewModel

@Composable
fun AppNavigation(
    navController: NavHostController,
    viewModel: UsuarioViewModel,
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") { SplashScreen(navController) }

        // Registro paso a paso
        composable("registro1") { RegistroVent1Screen(navController) }
        composable("registro2") { RegistroVent2Screen(navController, viewModel) }
        composable("registro3") { RegistroVent3Screen(navController, viewModel) }
        composable("registro4") { RegistroVent4Screen(navController, viewModel) }
        composable("registro5") { RegistroVent5Screen(navController, viewModel) }
        composable("registro6") { RegistroVent6Screen(navController, viewModel) }
        composable("registro7") { RegistroVent7Screen(navController, viewModel) }
        composable("registro8") { RegistroVent8Screen(navController) }
        composable("registro9") { RegistroVent9Screen(navController, viewModel) }
        composable("registro10") { RegistroVent10Screen(navController, viewModel) }

        // Navegación principal
        composable("inicio") { InicioScreen(navController) }
        composable("buscarAlimentos") { BuscarAlimentoScreen(navController) }
        composable("rutina") { RutinaScreen(navController) }
        composable("estadisticas") { EstadisticasScreen(navController) }

        // Login
        composable("login") { LoginScreen(navController) }

        // Configuración
        composable("configuracion") {
            ConfiguracionScreen(
                navController = navController,
                isDarkTheme = isDarkTheme,
                onThemeChange = onThemeChange
            )
        }
        composable("perfil") { ConfiguracionPerfilScreen(navController) }
        composable("recordatorios") { ConfiguracionRecordatorioScreen(navController)}
        composable("gemini-config") { GeminiConfigScreen(navController::navigateUp) }

        // Alimentos
        composable("favoritos") { AlimentosFavoritosScreen(navController) }
        composable("detalleAlimento/{idAlimento}") { backStackEntry ->
            val idAlimento = backStackEntry.arguments?.getString("idAlimento")?.toLongOrNull()
            if (idAlimento != null) {
                DetalleAlimentoScreen(idAlimento = idAlimento, navController = navController)
            }
        }
    }
}

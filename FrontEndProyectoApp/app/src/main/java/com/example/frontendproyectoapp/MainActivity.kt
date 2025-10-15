package com.example.frontendproyectoapp

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.example.frontendproyectoapp.DataStores.UserPreferences
import com.example.frontendproyectoapp.navigation.AppNavigation
import com.example.frontendproyectoapp.ui.theme.FrontEndProyectoAppTheme
import com.example.frontendproyectoapp.viewModel.UsuarioViewModel
import kotlinx.coroutines.launch
import android.provider.Settings
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // 🔹 El usuario negó el permiso
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                ) {
                    // Puede volver a mostrar el diálogo
                    requestNotificationPermission()
                } else {
                    // Usuario marcó "No volver a preguntar" → llevar a Ajustes
                    openAppSettings()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 👉 Inicializar configuración de Gemini
        com.example.frontendproyectoapp.config.GeminiConfig.initialize(this)

        // 👉 Crear canal de notificaciones
        createNotificationChannel()

        // 👉 Verificar permisos exactos de alarmas (solo Android 12+)
        checkExactAlarmPermission()

        // 👉 Verificar permiso de notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkNotificationPermission()
        }

        setContent {
            val context = applicationContext as Application

            val temaFlow = UserPreferences.obtenerTema(context)
            val temaPreferencia by temaFlow.collectAsState(initial = isSystemInDarkTheme())
            var isDarkTheme by remember { mutableStateOf(temaPreferencia) }

            FrontEndProyectoAppTheme(darkTheme = isDarkTheme) {
                val usuarioViewModel: UsuarioViewModel = viewModel(
                    factory = ViewModelProvider.AndroidViewModelFactory(context)
                )
                val navController = rememberNavController()

                AppNavigation(
                    navController = navController,
                    viewModel = usuarioViewModel,
                    isDarkTheme = isDarkTheme,
                    onThemeChange = { nuevoTema ->
                        isDarkTheme = nuevoTema
                        lifecycleScope.launch {
                            UserPreferences.guardarTema(context, nuevoTema)
                        }
                    }
                )
            }
        }
    }

    private fun checkNotificationPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "recordatorios_channel",
                "Recordatorios diarios",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Canal para notificaciones de recordatorios diarios (agua, comidas, etc.)"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
}

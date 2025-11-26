package com.example.Proyecto;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Clase principal de la aplicación Spring Boot para el sistema de nutrición NutriAI
 * 
 * Esta clase inicia la aplicación backend que gestiona:
 * - Usuarios y perfiles nutricionales
 * - Rutinas alimentarias personalizadas
 * - Interacciones con el chatbot
 * - Registros de alimentos y agua
 * - Estadísticas nutricionales
 * 
 * @author [Tu nombre]
 * @version 1.0
 */
@SpringBootApplication
public class ProyectoApplication {

	/**
	 * Método principal que inicia la aplicación Spring Boot
	 * 
	 * @param args Argumentos de línea de comandos
	 */
	public static void main(String[] args) {
		// Cargar variables de entorno antes de iniciar Spring Boot
		// Esto asegura que las credenciales de la base de datos estén disponibles
		loadEnv();
		// Inicia la aplicación Spring Boot con todas sus configuraciones
		SpringApplication.run(ProyectoApplication.class, args);
	}

	/**
	 * Carga las variables de entorno desde un archivo .env
	 * 
	 * Las variables cargadas son:
	 * - BD_URL: URL de conexión a la base de datos PostgreSQL
	 * - BD_USERNAME: Usuario de la base de datos
	 * - BD_PASSWORD: Contraseña de la base de datos
	 * 
	 * Estas variables se establecen como propiedades del sistema para que
	 * Spring Boot las pueda leer desde application.properties
	 */
	private static void loadEnv(){
		Dotenv dotenv = Dotenv.load();
		System.setProperty("BD_URL", dotenv.get("BD_URL"));
		System.setProperty("BD_USERNAME", dotenv.get("BD_USERNAME"));
		System.setProperty("BD_PASSWORD", dotenv.get("BD_PASSWORD"));
	}


}

package com.example.Proyecto.Configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfigurer {
    @Bean(name= "CustomCorsConfigurer")
    public WebMvcConfigurer corsConfigurer(){
        return new WebMvcConfigurer() {
          @Override
          public void addCorsMappings(CorsRegistry registry){
              registry.addMapping("/api/**")
                      .allowedOriginPatterns(
                              "http://192.168.20.151:8080",
                              "http://10.0.2.2:8080",   // Emulador Android
                              "http://localhost:8080", // Postman (si usas el interceptor de Postman)
                              "*"                      // Opción segura si NO usas cookies/autenticación basada en sesión
                      )
                      .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                      .allowedHeaders("*")
                      .allowCredentials(false); //  Importante para que funcione con "*"
          }
        };
    }
}

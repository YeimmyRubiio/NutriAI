package com.example.Proyecto.Model;


import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * Modelo de entidad que representa un Usuario en el sistema NutriAI
 * 
 * Esta clase mapea la tabla "Usuario" de la base de datos PostgreSQL y contiene
 * toda la información del perfil nutricional del usuario, incluyendo:
 * 
 * - Datos personales básicos (nombre, correo, fecha de nacimiento)
 * - Datos físicos (peso, altura, sexo)
 * - Objetivos y preferencias (peso objetivo, restricciones dietéticas, nivel de actividad)
 * - Relaciones con otras entidades (rutinas, registros, estadísticas)
 * 
 * Relaciones principales:
 * - Un usuario tiene muchas rutinas alimentarias
 * - Un usuario tiene muchos registros de alimentos
 * - Un usuario tiene muchos registros de agua
 * - Un usuario tiene un perfil de preferencias
 * - Un usuario tiene estadísticas diarias y mensuales
 * 
 * @author [Tu nombre]
 */
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
@Table(name = "Usuario")
public class Usuario {
    // ==================== CAMPOS BÁSICOS ====================
    
    /** ID único del usuario, generado automáticamente por la base de datos */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_usuario")
    private Long idUsuario;

    /** Correo electrónico del usuario (usado para autenticación) */
    @Column(name = "Correo", nullable = false, length = 100)
    private String correo;

    /** Contraseña encriptada del usuario */
    @Column(name = "Contraseña", nullable = false, length = 150)
    private String contrasena;

    /** Nombre completo del usuario */
    @Column(name = "Nombre", nullable = false, length = 100)
    private String nombre;

    /** Fecha de nacimiento del usuario (formato: yyyy-MM-dd) */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Column(name = "Fecha_Nacimiento", nullable = false)
    private LocalDate fechaNacimiento;

    // ==================== DATOS FÍSICOS ====================
    
    /** Altura del usuario en centímetros */
    @Column(name = "Altura", nullable = false)
    private Float altura;

    /** Peso actual del usuario en kilogramos */
    @Column(name = "Peso", nullable = false)
    private Float peso;

    /** Sexo del usuario (ej: "Masculino", "Femenino") */
    @Column(name = "Sexo", nullable = false, length = 25)
    private String sexo;

    // ==================== OBJETIVOS Y PREFERENCIAS ====================
    
    /** Restricciones dietéticas del usuario (ej: "Vegetariano", "Vegano", "Sin gluten") */
    @Column(name = "Restricciones_Dieta")
    private String restriccionesDieta;

    /** Objetivos de salud del usuario (ej: "Perder peso", "Ganar masa muscular") */
    @Column(name = "Objetivos_Salud")
    private String objetivosSalud;

    /** Peso objetivo del usuario en kilogramos */
    @Column(name = "Peso_Objetivo")
    private Float pesoObjetivo;

    /** Nivel de actividad física del usuario (ej: "Sedentario", "Moderado", "Activo") */
    @Column(name = "nivel_actividad")
    private String nivelActividad;

    // ==================== METADATOS ====================
    
    /** Fecha y hora en que se creó el registro del usuario */
    @Column(name = "Creado_En", nullable = false)
    private Timestamp creadoEn;

    /** Fecha y hora de la última actualización del perfil del usuario */
    @Column(name = "Actualizado_En")
    private Timestamp actualizadoEn;

    // ==================== RELACIONES ENTRE TABLAS ====================
    // Uno a muchos (Un usuario tiene muchos registros de estos tipos)

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<RegistroAgua> registroAguas;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<RegistroAlimento> registroAlimentos;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<RutinaAlimenticiaIA> rutinaAlimenticiaIAS;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<EstadisticaDiaria> estadisticaDiarias;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<EstadisticaMensual> estadisticaMensuales;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<RegistroRespuestasIA> registroRespuestasIAS;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<SesionChatbot> sesionChatbots;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<TokenSesion> tokenSesions;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Recordatorio> recordatorios;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<ModificacionRutinaChatbot> modificacionRutinaChatbots;

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<AlimentoReciente> alimentosRecientes;

    // Uno a Uno
    @OneToOne(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private PreferenciasUsuario preferenciasUsuario;

    @OneToOne(mappedBy = "usuario", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private ConfiguracionAplicacion configuracionAplicacion;

}

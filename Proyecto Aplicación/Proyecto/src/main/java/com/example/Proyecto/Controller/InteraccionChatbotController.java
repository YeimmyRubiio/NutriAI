package com.example.Proyecto.Controller;

import com.example.Proyecto.Model.InteraccionChatbot;
import com.example.Proyecto.Service.InteraccionChatbotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Controlador REST para gestionar las interacciones del chatbot con los usuarios
 * 
 * Este controlador expone endpoints para:
 * - Listar todas las interacciones
 * - Buscar interacciones por ID
 * - Guardar nuevas interacciones
 * - Actualizar interacciones existentes
 * - Eliminar interacciones
 * 
 * Cada interacción guarda:
 * - La consulta del usuario
 * - La respuesta generada por la IA
 * - El tipo de consulta/intento
 * - El timestamp de la interacción
 * 
 * @author [Tu nombre]
 */
@RestController
@RequestMapping("/api/InteraccionChatbot")
public class InteraccionChatbotController {
    
    // Servicio que contiene la lógica de negocio para las interacciones
    @Autowired
    public InteraccionChatbotService interaccionService;

    /**
     * Endpoint para listar todas las interacciones del chatbot
     * 
     * @return ResponseEntity con la lista de interacciones o código 204 si está vacía
     *         Código 200: Lista obtenida exitosamente
     *         Código 204: No hay interacciones registradas
     */
    @GetMapping("/listar")
    public ResponseEntity<List<InteraccionChatbot>> listarInteraccionesChatbot() {
        List<InteraccionChatbot> interaccionChatbots = interaccionService.listarInteraccionesChatbot();
        // Verificar si la lista está vacía
        if (interaccionChatbots.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NO_CONTENT); // 204 No Content
        }
        return new ResponseEntity<>(interaccionChatbots, HttpStatus.OK); // 200 OK
    }

    /**
     * Endpoint para buscar una interacción específica por su ID
     * 
     * @param id_interaccion ID de la interacción a buscar
     * @return ResponseEntity con la interacción encontrada o código de error
     *         Código 200: Interacción encontrada
     *         Código 404: Interacción no encontrada
     *         Código 400: ID inválido
     */
    @GetMapping("/buscar/{id_interaccion}")
    public ResponseEntity<InteraccionChatbot> listarPorIdInteraccionChatbot(@PathVariable long id_interaccion){
        try {
            Optional<InteraccionChatbot> interaccionOpt = interaccionService.listarPorIdInteraccionChatbot(id_interaccion);
            return interaccionOpt.map(interaccionChatbot -> new ResponseEntity<>(interaccionChatbot, HttpStatus.OK))
                    .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND)); // 404 Not Found
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST); // 400 Bad Request
        }
    }

    /**
     * Endpoint para guardar una nueva interacción del chatbot
     * 
     * Este endpoint se usa para registrar cada conversación entre el usuario y el chatbot,
     * almacenando tanto la pregunta del usuario como la respuesta generada por la IA.
     * 
     * @param interaccionChatbot Objeto con los datos de la interacción a guardar
     * @return ResponseEntity con la interacción guardada o código de error
     *         Código 201: Interacción creada exitosamente
     *         Código 400: Datos inválidos (campos obligatorios faltantes)
     */
    @PostMapping("/guardar")
    public ResponseEntity<InteraccionChatbot> guardarInteraccionChatbot(@RequestBody InteraccionChatbot interaccionChatbot){
        try {
            InteraccionChatbot nuevoInteraccionChatbot = interaccionService.guardarInteraccionChatbot(interaccionChatbot);
            return new ResponseEntity<>(nuevoInteraccionChatbot, HttpStatus.CREATED); // 201 Created
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST); // 400 Bad Request
        }
    }

    /**
     * Endpoint para eliminar una interacción del chatbot
     * 
     * @param id_interaccion ID de la interacción a eliminar
     * @return ResponseEntity sin contenido o código de error
     *         Código 204: Interacción eliminada exitosamente
     *         Código 404: Interacción no encontrada
     *         Código 400: ID inválido
     */
    @DeleteMapping("/eliminar/{id_interaccion}")
    public ResponseEntity<Void> eliminarInteraccionChatbot(@PathVariable long id_interaccion){
        try {
            interaccionService.eliminarInteraccionChatbot(id_interaccion);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT); // 204 No Content
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND); // 404 Not Found
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST); // 400 Bad Request
        }
    }

    /**
     * Endpoint para actualizar una interacción existente
     * 
     * Permite modificar los datos de una interacción ya registrada,
     * útil para corregir errores o actualizar información.
     * 
     * @param id_interaccion ID de la interacción a actualizar
     * @param interaccionActualizado Objeto con los nuevos datos de la interacción
     * @return ResponseEntity con la interacción actualizada o código de error
     *         Código 200: Interacción actualizada exitosamente
     *         Código 404: Interacción no encontrada
     *         Código 400: Datos inválidos
     */
    @PutMapping("/actualizar/{id_interaccion}")
    public ResponseEntity<InteraccionChatbot> actualizarInteraccionChatbot(@PathVariable long id_interaccion, @RequestBody InteraccionChatbot interaccionActualizado){
        try {
            InteraccionChatbot interaccionChatbot = interaccionService.actualizarInteraccionChatbot(id_interaccion, interaccionActualizado);
            return new ResponseEntity<>(interaccionChatbot, HttpStatus.OK); // 200 OK
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND); // 404 Not Found
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST); // 400 Bad Request
        }
    }
}

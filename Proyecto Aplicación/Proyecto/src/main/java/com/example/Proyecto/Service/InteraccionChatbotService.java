package com.example.Proyecto.Service;

import com.example.Proyecto.Model.InteraccionChatbot;
import com.example.Proyecto.Repository.InteraccionChatbotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Servicio que contiene la lógica de negocio para gestionar las interacciones del chatbot
 * 
 * Este servicio actúa como capa intermedia entre el controlador y el repositorio,
 * proporcionando validaciones y manejo de errores antes de realizar operaciones en la base de datos.
 * 
 * Funcionalidades principales:
 * - Listar todas las interacciones
 * - Buscar interacciones por ID
 * - Guardar nuevas interacciones con validación
 * - Actualizar interacciones existentes
 * - Eliminar interacciones
 * - Obtener historial de interacciones por sesión
 * - Filtrar interacciones por fecha y tipo
 * 
 * @author [Tu nombre]
 */
@Service
public class InteraccionChatbotService {
    
    // Repositorio para acceder a la base de datos
    @Autowired
    public InteraccionChatbotRepository interaccionChatbotRepository;

    /**
     * Lista todas las interacciones del chatbot registradas en el sistema
     * 
     * @return Lista de todas las interacciones del chatbot
     * @throws RuntimeException Si ocurre un error al consultar la base de datos
     */
    public List<InteraccionChatbot> listarInteraccionesChatbot(){
        // Validacion para intentar obtener la lista de Interacciones del Chatbot
        try {
            List<InteraccionChatbot> interaccionChatbots = interaccionChatbotRepository.findAll();
            // Validar que la lista no sea nula
            if (interaccionChatbots == null) {
                throw new IllegalStateException("No se encontraron Interacciones de Chatbot.");
            }
            return interaccionChatbots;
        } catch (Exception e) {
            // Manejo de excepciones
            throw new RuntimeException("Error al listar las Interacciones del Chatbot: " + e.getMessage(), e);
        }
    }

    public Optional<InteraccionChatbot> listarPorIdInteraccionChatbot(long id_interaccion){
        try {
            Optional<InteraccionChatbot> interaccionChatbot = interaccionChatbotRepository.findById(id_interaccion);
            if (interaccionChatbot.isPresent()) {
                return interaccionChatbot;
            } else {
                throw new IllegalStateException("No se encontraron Interacciones con el Chatbot.");
            }
        }catch (Exception e){
            throw new RuntimeException("Error al listar la Interaccion con el Chatbot " + id_interaccion +": "+ e.getMessage(), e);
        }
    }

    /**
     * Guarda una nueva interacción del chatbot en la base de datos
     * 
     * Valida que todos los campos obligatorios estén presentes:
     * - consultaUsuario: La pregunta o mensaje del usuario
     * - respuestaIA: La respuesta generada por la IA
     * - timestamp: Fecha y hora de la interacción
     * 
     * @param interaccionChatbot Objeto con los datos de la interacción a guardar
     * @return La interacción guardada con su ID generado
     * @throws IllegalArgumentException Si faltan campos obligatorios o son inválidos
     * @throws RuntimeException Si ocurre un error al guardar en la base de datos
     */
    public InteraccionChatbot guardarInteraccionChatbot(InteraccionChatbot interaccionChatbot){
        try{
            if(interaccionChatbot==null){
                throw new IllegalArgumentException("La Interaccion del Chatbot no puede ser nulo");

            }else{
                // Validar que la consulta del usuario no esté vacía
                if (interaccionChatbot.getConsultaUsuario() == null || interaccionChatbot.getConsultaUsuario().isEmpty()) {
                    throw new IllegalArgumentException("La consulta con el usuario es obligatorio.");
                // Validar que la respuesta de la IA no esté vacía
                }else if(interaccionChatbot.getRespuestaIA() == null || interaccionChatbot.getRespuestaIA().isEmpty()){
                    throw new IllegalArgumentException("La respuesta de IA es obligatoria.");
                // Validar que el timestamp esté presente
                }else if(interaccionChatbot.getTimestamp() == null){
                    throw new IllegalArgumentException("El timestamp de la interaccion es obligatoria.");
                }
                // Guardar en la base de datos
                return  interaccionChatbotRepository.save(interaccionChatbot);
            }
        }catch (Exception e){
            throw new RuntimeException("Error al intentar la interaccion con el chatbot" + e.getMessage(), e);
        }
    }

    public void eliminarInteraccionChatbot(long id_interaccion){
        try {
            if (id_interaccion<=0) {
                throw new IllegalArgumentException("El ID de la interaccion debe ser un número positivo.");
            }
            if (!interaccionChatbotRepository.existsById(id_interaccion)) {
                throw new NoSuchElementException("No se encontró una interaccion con el chatbot con el ID: " + id_interaccion);
            }
            interaccionChatbotRepository.deleteById(id_interaccion);
        }catch (Exception e){
            throw new RuntimeException("Error al eliminar la interaccion del chatbot "+ id_interaccion +": "+ e.getMessage(), e);
        }
    }

    public InteraccionChatbot actualizarInteraccionChatbot(long id_interaccion, InteraccionChatbot interaccionActualizado){
        Optional<InteraccionChatbot> interaccionOpt = interaccionChatbotRepository.findById(id_interaccion);
        if(interaccionOpt.isPresent()){
            InteraccionChatbot interaccionExistente = interaccionOpt.get();
            interaccionExistente.setConsultaUsuario(interaccionActualizado.getConsultaUsuario());
            interaccionExistente.setRespuestaIA(interaccionActualizado.getRespuestaIA());
            interaccionExistente.setTimestamp(interaccionActualizado.getTimestamp());
            interaccionExistente.setTipoIntento(interaccionActualizado.getTipoIntento());
            interaccionExistente.setTipoAccion(interaccionActualizado.getTipoAccion());
            interaccionExistente.setTema(interaccionActualizado.getTema());
            return interaccionChatbotRepository.save(interaccionExistente);
        }else{
            return null;
        }
    }

    public void obtenerInteraccion(@Param("id_sesion") Long id_sesion, @Param("mensajeUsuario") String mensajeUsuario, @Param("respuestaChatbot") String respuestaChatbot, @Param("tipoConsulta") String tipoConsulta){
        interaccionChatbotRepository.registrarInteraccion(id_sesion,mensajeUsuario,respuestaChatbot,tipoConsulta);
    }

    public List<InteraccionChatbot> HistorialInteracciones(@Param("id_sesion") Long id_sesion){
        return interaccionChatbotRepository.obtenerHistorialInteracciones(id_sesion);
    }

    public String obtenerRespuestaPorTipo(@Param("id_sesion") Long id_sesion, @Param("tipoConsulta") String tipoConsulta){
        return interaccionChatbotRepository.consultarRespuestaPorTipo(id_sesion,tipoConsulta);
    }

    public List<InteraccionChatbot> obtenerPorFechaYTipo(@Param("id_sesion") Long id_sesion,@Param("fechaInicio") String fechaInicio,@Param("fechaFin") String fechaFin,@Param("tipoConsulta") String tipoConsulta){
        return interaccionChatbotRepository.filtrarPorFechaYTipo(id_sesion,fechaInicio,fechaFin,tipoConsulta);
    }
}

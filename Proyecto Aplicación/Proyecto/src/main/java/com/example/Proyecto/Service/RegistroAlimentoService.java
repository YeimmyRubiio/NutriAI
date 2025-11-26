package com.example.Proyecto.Service;

import com.example.Proyecto.DTO.RegistroAlimentoEntradaDTO;
import com.example.Proyecto.Model.Alimento;
import com.example.Proyecto.Model.RegistroAlimento;
import com.example.Proyecto.Model.UnidadEquivalencia;
import com.example.Proyecto.Model.Usuario;
import com.example.Proyecto.Repository.AlimentoRepository;
import com.example.Proyecto.Repository.RegistroAlimentoRepository;
import com.example.Proyecto.Repository.UnidadEquivalenciaRepository;
import com.example.Proyecto.Repository.UsuarioRepository;
import com.example.Proyecto.Service.UnidadEquivalenciaService;
import com.example.Proyecto.DTO.UnidadEquivalenciaDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;


@Service
public class RegistroAlimentoService {

    private static final Logger log = LoggerFactory.getLogger(RegistroAlimentoService.class);

    @Autowired
    public RegistroAlimentoRepository registroAlimentoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    public AlimentoRepository alimentoRepository;

    @Autowired
    public UnidadEquivalenciaRepository unidadEquivalenciaRepository;
    
    @Autowired
    public UnidadEquivalenciaService unidadEquivalenciaService;

    @Autowired
    public EstadisticasNutricionalesService estadisticasService;
    
    @PersistenceContext
    private EntityManager entityManager;

    public List<RegistroAlimento> listarRegistroAlimento(){
        // Validacion para intentar obtener la lista de Registros de Alimento
        try {
            List<RegistroAlimento> registroAlimentos = registroAlimentoRepository.findAll();
            // Validar que la lista no sea nula
            if (registroAlimentos == null) {
                throw new IllegalStateException("No se encontraron Registros de Alimento.");
            }
            return registroAlimentos;
        } catch (Exception e) {
            // Manejo de excepciones
            throw new RuntimeException("Error al listar los Registros de Alimento: " + e.getMessage(), e);
        }
    }

    public Optional<RegistroAlimento> listarPorIdRegistroAlimento(long idRegistroAlimento){
        try {
            Optional<RegistroAlimento> registroAlimento = registroAlimentoRepository.findById(idRegistroAlimento);
            if (registroAlimento.isPresent()) {
                return registroAlimento;
            } else {
                throw new IllegalStateException("No se encontraron Registros de Alimentos.");
            }
        }catch (Exception e){
            throw new RuntimeException("Error al listar el Registro del Alimento " + idRegistroAlimento +": "+ e.getMessage(), e);
        }
    }

    @Transactional
    public RegistroAlimento guardarRegistro(RegistroAlimentoEntradaDTO dto) {
        Usuario usuario = usuarioRepository.findById(dto.getIdUsuario())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        Alimento alimento = alimentoRepository.findById(dto.getIdAlimento())
                .orElseThrow(() -> new RuntimeException("Alimento no encontrado"));

        String unidadOrigen = dto.getUnidadOriginal().toLowerCase();  // <- original
        String unidadDestino = "gramos";
        float cantidadOriginal = dto.getTamanoOriginal();

        // Validación explícita de unidad original (evita valores inválidos)
        // Aceptar todas las unidades válidas, no solo gramos
        List<String> unidadesValidas = List.of(
                "mg", "g", "kg", "ml", "l", "tsp", "tbsp", "cup", "oz", "lb",
                "unidad", "porción", "rebanada", "pieza", "taza", "vaso",
                "lonja", "filete", "puñado", "cucharada", "hoja", "bola"
        );

        if (!unidadesValidas.contains(unidadOrigen)) {
            throw new IllegalArgumentException("Unidad original inválida: " + unidadOrigen);
        }

        float cantidadEnGramos;

        Optional<UnidadEquivalencia> equivalenciaOpt = unidadEquivalenciaRepository
                .findByAlimentoAndUnidadOrigenAndUnidadDestino(alimento, unidadOrigen, unidadDestino);

        float factor;
        if (equivalenciaOpt.isPresent()) {
            factor = equivalenciaOpt.get().getFactorConversion();
        } else {
            // Si no existe, calcular el factor basado en la unidad y cantidadBase del alimento
            // cantidadBase indica cuántos gramos equivale 1 unidad base del alimento
            Float cantidadBase = alimento.getCantidadBase();
            if (cantidadBase == null || cantidadBase <= 0) {
                cantidadBase = 100f; // Valor por defecto si no está definido
            }
            
            // Calcular el factor de conversión según la unidad de origen
            if (unidadOrigen.equals("g") || unidadOrigen.equals("gramos") || unidadOrigen.equals("gramo")) {
                // Si la unidad es gramos, 1 gramo = 1 gramo
                factor = 1.0f;
            } else if (unidadOrigen.equals("kg") || unidadOrigen.equals("kilogramos") || unidadOrigen.equals("kilogramo")) {
                // Si la unidad es kilogramos, 1 kg = 1000 gramos
                factor = 1000.0f;
            } else if (unidadOrigen.equals("mg") || unidadOrigen.equals("miligramos") || unidadOrigen.equals("miligramo")) {
                // Si la unidad es miligramos, 1 mg = 0.001 gramos
                factor = 0.001f;
            } else {
                // Para otras unidades (porción, taza, pieza, etc.), usar cantidadBase
                // Si cantidadBase = 100g y la unidad es "porción", entonces 1 porción = 100 gramos
                // El factor es la cantidad de gramos que equivale 1 unidad
                factor = cantidadBase;
            }
            
            // NO intentar crear la equivalencia dentro de esta transacción
            // Esto evita problemas de sesión de Hibernate que pueden causar que falle el guardado
            // La equivalencia se creará automáticamente cuando sea necesario en otro momento
            // o se puede crear manualmente más tarde
            // Por ahora, simplemente usar el factor calculado y continuar
        }
        cantidadEnGramos = cantidadOriginal * factor;

        RegistroAlimento registro = new RegistroAlimento();
        registro.setUsuario(usuario);
        registro.setAlimento(alimento);
        registro.setTamanoPorcion(cantidadEnGramos);      // convertido
        registro.setUnidadMedida(unidadDestino);          // convertido (gramos)
        registro.setTamanoOriginal(cantidadOriginal);     // original
        registro.setUnidadOriginal(unidadOrigen);         // original
        registro.setMomentoDelDia(dto.getMomentoDelDia());
        registro.setConsumidoEn(LocalDateTime.now());

        RegistroAlimento registroGuardado = registroAlimentoRepository.save(registro);
        // Actualizar estadísticas diarias justo después de guardar el registro
        LocalDate fechaRegistro = registroGuardado.getConsumidoEn().toLocalDate();
        estadisticasService.guardarEstadisticaDiaria(usuario.getIdUsuario(), fechaRegistro);

        // Actualizar estadísticas mensuales
        int anio = fechaRegistro.getYear();
        int mes = fechaRegistro.getMonthValue();
        estadisticasService.guardarEstadisticaMensual(usuario.getIdUsuario(), anio, mes);

        return registroGuardado;
    }

    public List<RegistroAlimento> obtenerRecientesPorUsuario(Long idUsuario) {
        return registroAlimentoRepository.findRecientesConAlimento(idUsuario);
    }

    public void eliminarRegistroAlimento(long idRegistroAlimento){
        try {
            if (idRegistroAlimento<=0) {
                throw new IllegalArgumentException("El ID del Registro del Alimento debe ser un número positivo.");
            }
            if (!registroAlimentoRepository.existsById(idRegistroAlimento)) {
                throw new NoSuchElementException("No se encontró un Registro del Alimento con el ID: " + idRegistroAlimento);
            }
            registroAlimentoRepository.deleteById(idRegistroAlimento);
        }catch (Exception e){
            throw new RuntimeException("Error al eliminar el Registro del Alimento "+ idRegistroAlimento +": "+ e.getMessage(), e);
        }
    }

    public RegistroAlimento actualizarRegistroAlimento(long idRegistroAlimento, RegistroAlimento registroAlimentoActualizado){
        Optional<RegistroAlimento> registroAlimentoOpt = registroAlimentoRepository.findById(idRegistroAlimento);
        if(registroAlimentoOpt.isPresent()){
            RegistroAlimento registroAlimentoExistente = registroAlimentoOpt.get();
            registroAlimentoExistente.setTamanoPorcion(registroAlimentoActualizado.getTamanoPorcion());
            registroAlimentoExistente.setUnidadMedida(registroAlimentoActualizado.getUnidadMedida());
            registroAlimentoExistente.setMomentoDelDia(registroAlimentoActualizado.getMomentoDelDia());
            return registroAlimentoRepository.save(registroAlimentoExistente);
        }else{
            return null;
        }
    }

    public List<RegistroAlimento> obtenerPorUsuarioFechaYMomento(Long idUsuario, LocalDate fecha, String momento) {
        LocalDateTime inicio = fecha.atStartOfDay();
        LocalDateTime fin = fecha.atTime(LocalTime.MAX);
        return registroAlimentoRepository.findByUsuarioFechaYMomento(idUsuario, inicio, fin, momento);
    }

    public void eliminarPorMomentoYFecha(Long idUsuario, String momento, LocalDate fecha) {
        LocalDateTime inicio = fecha.atStartOfDay();
        LocalDateTime fin = fecha.atTime(LocalTime.MAX);
        registroAlimentoRepository.deleteByUsuarioFechaYMomento(idUsuario, momento, inicio, fin);
    }

    public void eliminarRegistroPorId(Long idRegistro) {
        if (!registroAlimentoRepository.existsById(idRegistro)) {
            throw new RuntimeException("Registro no encontrado");
        }
        registroAlimentoRepository.deleteById(idRegistro);
    }

    // Obtener todas las unidades de origen de un alimento por su ID.
    public List<String> obtenerUnidadesPorIdAlimento(Long idAlimento) {
        List<String> unidades = unidadEquivalenciaRepository.findUnidadOrigenByAlimentoId(idAlimento);
        log.info("Obteniendo unidades de origen para Alimento ID {}: {}", idAlimento, unidades);
        return unidades;
    }

    // Obtener todas las unidades de origen de un alimento por su nombre.
    public List<String> obtenerUnidadesPorNombreAlimento(String nombreAlimento) {
        List<String> unidades = unidadEquivalenciaRepository.findUnidadOrigenByAlimentoNombre(nombreAlimento);
        log.info("Obteniendo unidades de origen para Alimento '{}': {}", nombreAlimento, unidades);
        return unidades;
    }


}

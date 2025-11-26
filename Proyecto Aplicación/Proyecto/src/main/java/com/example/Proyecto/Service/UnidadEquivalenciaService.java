package com.example.Proyecto.Service;

import com.example.Proyecto.DTO.UnidadEquivalenciaDTO;
import com.example.Proyecto.Model.Alimento;
import com.example.Proyecto.Model.UnidadEquivalencia;
import com.example.Proyecto.Repository.AlimentoRepository;
import com.example.Proyecto.Repository.UnidadEquivalenciaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class UnidadEquivalenciaService {
    @Autowired
    public UnidadEquivalenciaRepository unidadEquivalenciaRepository;

    @Autowired
    public AlimentoRepository alimentoRepository;
    
    @PersistenceContext
    private EntityManager entityManager;

    public List<UnidadEquivalencia> listarUnidadEquivalencia(){
        // Validacion para intentar obtener la lista de las equivalencias de las unidades
        try {
            List<UnidadEquivalencia> unidadEquivalencias = unidadEquivalenciaRepository.findAll();
            // Validar que la lista no sea nula
            if (unidadEquivalencias == null) {
                throw new IllegalStateException("No se encontraron unidades de equivalencias.");
            }
            return unidadEquivalencias;
        } catch (Exception e) {
            // Manejo de excepciones
            throw new RuntimeException("Error al listar los unidades de equivalencias: " + e.getMessage(), e);
        }
    }

    public Optional<UnidadEquivalencia> listarPorIdUnidadEquivalencia(long id_unidad){
        try {
            Optional<UnidadEquivalencia> unidadEquivalencia = unidadEquivalenciaRepository.findById(id_unidad);
            if (unidadEquivalencia.isPresent()) {
                return unidadEquivalencia;
            } else {
                throw new IllegalStateException("No se encontraron unidades de equivalencia.");
            }
        }catch (Exception e){
            throw new RuntimeException("Error al listar las unidades de equivalencia " + id_unidad +": "+ e.getMessage(), e);
        }
    }

    public UnidadEquivalencia guardarUnidadEquivalencia(UnidadEquivalencia unidadEquivalencia){
        try{
            if(unidadEquivalencia==null){
                throw new IllegalArgumentException("El Unidad de Equivalencia no puede ser nulo");

            }else{
                if (unidadEquivalencia.getUnidadOrigen() == null ||unidadEquivalencia.getUnidadOrigen().isEmpty()) {
                    throw new IllegalArgumentException("La unidad de origen del alimento es obligatorio.");
                }else if (unidadEquivalencia.getUnidadDestino() == null ||unidadEquivalencia.getUnidadDestino().isEmpty()) {
                    throw new IllegalArgumentException("La unidad de destino del alimento es obligatorio.");
                }else if (unidadEquivalencia.getFactorConversion() < 0  ) {
                    throw new IllegalArgumentException("El factor de conversion del alimento es obligatorio.");
                }
                return  unidadEquivalenciaRepository.save(unidadEquivalencia);
            }
        }catch (Exception e){
            throw new RuntimeException("Error al intentar guardar la unidad de equivalencia" + e.getMessage(), e);
        }
    }

    public void eliminarUnidadEquivalencia(long id_unidad){
        try {
            if (id_unidad<=0) {
                throw new IllegalArgumentException("El ID de la unidad de equivalencia debe ser un número positivo.");
            }
            if (!unidadEquivalenciaRepository.existsById(id_unidad)) {
                throw new NoSuchElementException("No se encontró un Registro de la unidad de equivalencia con el ID: " + id_unidad);
            }
            unidadEquivalenciaRepository.deleteById(id_unidad);
        }catch (Exception e){
            throw new RuntimeException("Error al eliminar la unidad de equivalencia "+ id_unidad +": "+ e.getMessage(), e);
        }
    }

    public UnidadEquivalencia actualizarUnidadEquivalencia(long id_unidad, UnidadEquivalencia unidadEquivalenciaActualizado){
        Optional<UnidadEquivalencia> unidadEquivalenciaOpt = unidadEquivalenciaRepository.findById(id_unidad);
        if(unidadEquivalenciaOpt.isPresent()){
            UnidadEquivalencia unidadEquivalenciaExistente = unidadEquivalenciaOpt.get();
            unidadEquivalenciaExistente.setUnidadOrigen(unidadEquivalenciaActualizado.getUnidadOrigen());
            unidadEquivalenciaExistente.setUnidadDestino(unidadEquivalenciaActualizado.getUnidadDestino());
            unidadEquivalenciaExistente.setFactorConversion(unidadEquivalenciaActualizado.getFactorConversion());
            return unidadEquivalenciaRepository.save(unidadEquivalenciaExistente);
        }else{
            return null;
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public UnidadEquivalencia crearOActualizarEquivalencia(UnidadEquivalenciaDTO dto) {
        Alimento alimento = alimentoRepository.findById(dto.getIdAlimento())
                .orElseThrow(() -> new RuntimeException("Alimento no encontrado"));

        String origen = dto.getUnidadOrigen().toLowerCase();
        String destino = dto.getUnidadDestino().toLowerCase();

        // Buscar la equivalencia existente
        Optional<UnidadEquivalencia> existente = unidadEquivalenciaRepository
                .findByAlimentoAndUnidadOrigenAndUnidadDestino(alimento, origen, destino);

        if (existente.isPresent()) {
            // Si existe, actualizar el factor si es necesario y retornar
            UnidadEquivalencia equivalencia = existente.get();
            equivalencia.setFactorConversion(dto.getFactorConversion());
            return unidadEquivalenciaRepository.save(equivalencia);
        } else {
            // Si no existe, intentar crear una nueva
            // IMPORTANTE: Asegurarse de que el ID sea null para que Hibernate lo genere automáticamente
            try {
                UnidadEquivalencia nuevaEquivalencia = new UnidadEquivalencia();
                // CRÍTICO: NO asignar el ID manualmente - debe ser null para que se genere automáticamente
                nuevaEquivalencia.setIdUnidad(null); // Asegurar que el ID sea null
                nuevaEquivalencia.setAlimento(alimento);
                nuevaEquivalencia.setUnidadOrigen(origen);
                nuevaEquivalencia.setUnidadDestino(destino);
                nuevaEquivalencia.setFactorConversion(dto.getFactorConversion());
                return unidadEquivalenciaRepository.save(nuevaEquivalencia);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Limpiar la sesión de Hibernate después del error
                entityManager.clear();
                
                // Si falla por clave duplicada (condición de carrera), buscar de nuevo
                // REQUIRES_NEW crea una nueva transacción limpia, así que podemos buscar de nuevo
                Optional<UnidadEquivalencia> equivalenciaRecuperada = unidadEquivalenciaRepository
                        .findByAlimentoAndUnidadOrigenAndUnidadDestino(alimento, origen, destino);
                if (equivalenciaRecuperada.isPresent()) {
                    // Si ahora existe, actualizar el factor y retornar
                    UnidadEquivalencia equivalencia = equivalenciaRecuperada.get();
                    equivalencia.setFactorConversion(dto.getFactorConversion());
                    return unidadEquivalenciaRepository.save(equivalencia);
                } else {
                    // Si aún no existe después del error, lanzar excepción
                    throw new RuntimeException("Error al crear equivalencia de unidad después de intento fallido. " +
                            "La equivalencia no existe y no se pudo crear. " + e.getMessage(), e);
                }
            } catch (org.hibernate.AssertionFailure e) {
                // Limpiar la sesión de Hibernate después del error
                entityManager.clear();
                
                // Si Hibernate falla por sesión inválida, buscar de nuevo en una nueva transacción
                Optional<UnidadEquivalencia> equivalenciaRecuperada = unidadEquivalenciaRepository
                        .findByAlimentoAndUnidadOrigenAndUnidadDestino(alimento, origen, destino);
                if (equivalenciaRecuperada.isPresent()) {
                    UnidadEquivalencia equivalencia = equivalenciaRecuperada.get();
                    equivalencia.setFactorConversion(dto.getFactorConversion());
                    return unidadEquivalenciaRepository.save(equivalencia);
                } else {
                    throw new RuntimeException("Error de sesión de Hibernate al crear equivalencia: " + e.getMessage(), e);
                }
            } catch (Exception e) {
                // Limpiar la sesión de Hibernate después de cualquier error
                entityManager.clear();
                
                // Si es un error de integridad de datos, buscar de nuevo
                if (e.getCause() instanceof org.springframework.dao.DataIntegrityViolationException ||
                    e instanceof org.springframework.dao.DataIntegrityViolationException) {
                    Optional<UnidadEquivalencia> equivalenciaRecuperada = unidadEquivalenciaRepository
                            .findByAlimentoAndUnidadOrigenAndUnidadDestino(alimento, origen, destino);
                    if (equivalenciaRecuperada.isPresent()) {
                        UnidadEquivalencia equivalencia = equivalenciaRecuperada.get();
                        equivalencia.setFactorConversion(dto.getFactorConversion());
                        return unidadEquivalenciaRepository.save(equivalencia);
                    }
                }
                throw new RuntimeException("Error inesperado al crear equivalencia de unidad: " + e.getMessage(), e);
            }
        }
    }
}

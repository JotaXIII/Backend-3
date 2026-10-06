package cl.duoc.bancoxyz.solicitudes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SolicitudServiceTest {
    JdbcTemplate jdbc;
    JmsTemplate jms;
    SolicitudService service;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void preparar() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(datasource);
        // Persistencia de prueba.
        jdbc.execute("CREATE TABLE solicitudes (id VARCHAR(36) PRIMARY KEY, proceso VARCHAR(50) NOT NULL, "
                + "propietario VARCHAR(100) NOT NULL, estado VARCHAR(20) NOT NULL, creada TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jms = mock(JmsTemplate.class);
        service = new SolicitudService(jdbc, jms, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"transacciones", "intereses", "estados-anuales"})
    void persisteAntesDePublicarYRestringePropietario(String proceso) {
        var solicitud = service.solicitar(proceso, "operador");
        String id = solicitud.get("id").toString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(solicitud).containsEntry("proceso", proceso).containsEntry("estado", "PENDIENTE");
        assertThat(solicitud.get("creada")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT propietario FROM solicitudes WHERE id = ?", String.class, id)).isEqualTo("operador");
        assertThat(service.consultar(id, "operador")).containsEntry("id", id);
        assertThatThrownBy(() -> service.consultar(id, "otro")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(jms);
    }

    @Test
    void procesoInvalidoNoPersiste() {
        assertThatThrownBy(() -> service.solicitar("rechazados", "operador"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM solicitudes", Integer.class)).isZero();
        verifyNoInteractions(jms);
    }

    @Test
    void publicacionLeePersistenciaYMarcaEnviadaDespuesDelEnvio() throws Exception {
        String id = service.solicitar("intereses", "operador").get("id").toString();
        doAnswer(invocation -> {
            var mensaje = mapper.readTree((String) invocation.getArgument(1));
            assertThat(mensaje.path("id").asText()).isEqualTo(id);
            assertThat(mensaje.path("proceso").asText()).isEqualTo("intereses");
            assertThat(estado(id)).isEqualTo("PENDIENTE");
            return null;
        }).when(jms).convertAndSend(eq("procesos.solicitudes"), anyString());
        // Persistencia entre instancias.
        new SolicitudService(jdbc, jms, mapper).publicarPendientes();
        assertThat(estado(id)).isEqualTo("ENVIADA");
        service.publicarPendientes();
        verify(jms, times(1)).convertAndSend(eq("procesos.solicitudes"), anyString());
    }

    @Test
    void falloJmsConservaPendienteYPermiteReintentar() throws Exception {
        String id = service.solicitar("transacciones", "operador").get("id").toString();
        doThrow(new JmsException("broker no disponible") {
        }).doNothing()
                .when(jms).convertAndSend(eq("procesos.solicitudes"), anyString());
        assertThatThrownBy(service::publicarPendientes).isInstanceOf(JmsException.class);
        assertThat(estado(id)).isEqualTo("PENDIENTE");
        service.publicarPendientes();
        assertThat(estado(id)).isEqualTo("ENVIADA");
        verify(jms, times(2)).convertAndSend(eq("procesos.solicitudes"), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED"})
    void resultadoActualizaSoloEstado(String resultado) throws Exception {
        String id = service.solicitar("intereses", "operador").get("id").toString();
        var antes = service.consultar(id, "operador");
        service.publicarPendientes();
        service.recibirResultado(mapper.writeValueAsString(Map.of("id", id, "estado", resultado)));
        var despues = service.consultar(id, "operador");
        assertThat(despues).containsEntry("estado", resultado).containsEntry("id", id)
                .containsEntry("proceso", antes.get("proceso")).containsEntry("creada", antes.get("creada"));
        service.publicarPendientes();
        verify(jms, times(1)).convertAndSend(eq("procesos.solicitudes"), anyString());
    }

    @Test
    void resultadoInvalidoNoAlteraPersistencia() {
        String id = service.solicitar("intereses", "operador").get("id").toString();
        assertThatThrownBy(() -> service.recibirResultado("{\"id\":\"" + id + "\",\"estado\":\"DESCONOCIDO\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(estado(id)).isEqualTo("PENDIENTE");
    }

    private String estado(String id) {
        return jdbc.queryForObject("SELECT estado FROM solicitudes WHERE id = ?", String.class, id);
    }
}

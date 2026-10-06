package cl.duoc.bancoxyz.consultas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ConsultaController.class, properties = {
        "spring.config.name=cloud-test", "spring.cloud.config.enabled=false", "spring.config.import=", "eureka.client.enabled=false",
        "app.security.jwk-uri=http://localhost/unused", "app.security.issuer=http://localhost"})
class ConsultaControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    @Test
    void sinTokenEs401() throws Exception {
        mvc.perform(get("/api/transacciones")).andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc);
    }

    @Test
    void sinPermisoLecturaEs403() throws Exception {
        mvc.perform(get("/api/transacciones").with(jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_procesos.write"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @CsvSource({"transacciones,transacciones_procesadas,id", "intereses,intereses_calculados,cuenta_id",
            "estados-anuales,estados_cuenta_anuales,'cuenta_id, anio'", "rechazados,registros_rechazados,id"})
    void lecturaAutorizadaPaginaEnJdbc(String recurso, String tabla, String orden) throws Exception {
        String sql = "SELECT * FROM " + tabla + " ORDER BY " + orden + " LIMIT ? OFFSET ?";
        when(jdbc.queryForList(sql, 5, 10)).thenReturn(List.of(Map.of("id", 7)));
        mvc.perform(get("/api/" + recurso).param("limite", "5").param("pagina", "2")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_datos.read"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(7));
        verify(jdbc).queryForList(sql, 5, 10);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1001,0", "1,-1", "1,100001"})
    void paginacionInvalidaEs400(int limite, int pagina) throws Exception {
        mvc.perform(get("/api/transacciones").param("limite", "" + limite).param("pagina", "" + pagina)
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_datos.read"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }

    @Test
    void recursoDesconocidoEs404() throws Exception {
        mvc.perform(get("/api/desconocido").with(jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_datos.read")))).andExpect(status().isNotFound());
        verifyNoInteractions(jdbc);
    }
}

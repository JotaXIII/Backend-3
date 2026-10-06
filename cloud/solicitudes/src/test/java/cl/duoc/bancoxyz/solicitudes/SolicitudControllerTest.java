package cl.duoc.bancoxyz.solicitudes;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SolicitudController.class, properties = {
        "spring.config.name=cloud-test", "spring.cloud.config.enabled=false", "spring.config.import=", "eureka.client.enabled=false",
        "app.security.jwk-uri=http://localhost/unused", "app.security.issuer=http://localhost"})
class SolicitudControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SolicitudService solicitudes;
    @MockitoBean ConsultaService consultas;
    @MockitoBean JwtDecoder decoder;

    @Test
    void sinTokenEs401EnLecturaYEscritura() throws Exception {
        mvc.perform(get("/api/solicitudes/id")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/procesos/transacciones")).andExpect(status().isUnauthorized());
        verifyNoInteractions(solicitudes, consultas);
    }

    @Test
    void permisosInsuficientesSon403() throws Exception {
        mvc.perform(post("/api/procesos/transacciones").with(jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_datos.read")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/solicitudes/id").with(jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_procesos.write")))).andExpect(status().isForbidden());
        verifyNoInteractions(solicitudes, consultas);
    }

    @Test
    void lecturaUsaPropietarioDelJwt() throws Exception {
        when(solicitudes.consultar("id", "lector")).thenReturn(Map.of("id", "id", "estado", "PENDIENTE"));
        mvc.perform(get("/api/solicitudes/id").param("propietario", "otro")
                .with(jwt().jwt(j -> j.subject("lector")).authorities(new SimpleGrantedAuthority("SCOPE_datos.read"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("PENDIENTE"));
        verify(solicitudes).consultar("id", "lector");
    }

    @Test
    void solicitudAjenaEs404() throws Exception {
        when(solicitudes.consultar("id", "otro")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/solicitudes/id").with(jwt().jwt(j -> j.subject("otro"))
                .authorities(new SimpleGrantedAuthority("SCOPE_datos.read")))).andExpect(status().isNotFound());
        verify(solicitudes).consultar("id", "otro");
    }

    @Test
    void escrituraUsaPropietarioDelJwtYDevuelveLocation() throws Exception {
        when(solicitudes.solicitar("intereses", "operador")).thenReturn(Map.of("id", "nueva", "estado", "PENDIENTE"));
        mvc.perform(post("/api/procesos/intereses").with(jwt().jwt(j -> j.subject("operador"))
                .authorities(new SimpleGrantedAuthority("SCOPE_procesos.write"))))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/solicitudes/nueva"))
                .andExpect(jsonPath("$.id").value("nueva"));
        verify(solicitudes).solicitar("intereses", "operador");
    }

    @Test
    void lecturaRemotaPropagaTokenYPaginacion() throws Exception {
        tokenRemoto();
        when(consultas.consultar("rechazados", "Bearer remoto", 10, 2)).thenReturn(Map.of("disponible", true));
        mvc.perform(get("/api/consultas/rechazados").header("Authorization", "Bearer remoto")
                .param("limite", "10").param("pagina", "2").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_datos.read"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disponible").value(true));
        verify(consultas).consultar("rechazados", "Bearer remoto", 10, 2);
    }

    @Test
    void errorRemoto403ConservaEstadoHttp() throws Exception {
        tokenRemoto();
        when(consultas.consultar("intereses", "Bearer remoto", 100, 0))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        mvc.perform(get("/api/consultas/intereses").header("Authorization", "Bearer remoto")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_datos.read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void resilienciaPermiteLectura() throws Exception {
        when(consultas.estado()).thenReturn("OPEN");
        mvc.perform(get("/api/resiliencia").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_datos.read"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.circuito").value("OPEN"));
    }

    private void tokenRemoto() {
        // Simula la decodificacion del token Bearer.
        when(decoder.decode("remoto")).thenReturn(Jwt
                .withTokenValue("remoto").header("alg", "RS256").subject("lector")
                .claim("scope", "datos.read").build());
    }
}

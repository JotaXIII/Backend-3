package cl.duoc.bancoxyz.bffmovil;
import cl.duoc.bancoxyz.comun.BancoHttp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=MovilController.class,properties={"spring.config.name=cloud-test",
 "spring.cloud.config.enabled=false","spring.config.import=","eureka.client.enabled=false",
 "app.security.jwk-uri=http://localhost/unused","app.security.issuer=http://localhost"})
class MovilControllerTest {
 @Autowired MockMvc mvc;
 @MockitoBean BancoHttp http;
 @MockitoBean JwtDecoder decoder;
 @Test void anonimoEs401() throws Exception {
   mvc.perform(get("/api/movil/resumen")).andExpect(status().isUnauthorized());verifyNoInteractions(http);
 }
 @Test void otroCanalEs403() throws Exception {
   mvc.perform(get("/api/movil/resumen").header("Authorization","Token demo")
    .with(jwt().jwt(j -> j.claim("cliente_id",1).claim("canal","otro"))
      .authorities(new SimpleGrantedAuthority("SCOPE_datos.read"))))
     .andExpect(status().isForbidden());verifyNoInteractions(http);
 }
 @Test void sinPermisoEs403() throws Exception {
   mvc.perform(get("/api/movil/resumen").header("Authorization","Token demo")
    .with(jwt().jwt(j -> j.claim("cliente_id",1).claim("canal","movil"))
      .authorities(new SimpleGrantedAuthority("SCOPE_procesos.write"))))
     .andExpect(status().isForbidden());verifyNoInteractions(http);
 }
 @Test void respuestaDelCanalLimitaCampos() throws Exception {
   var cuenta=Map.<String,Object>of("id",1,"saldo",100,"moneda","CLP","cliente_id",1,"tipo","AHORRO","estado","ACTIVA");
   when(http.get(eq("cuentas"),anyString(),anyString(),eq(List.class))).thenReturn(List.of(cuenta));
   when(http.get(eq("cuentas"),anyString(),anyString(),eq(Map.class))).thenReturn(cuenta);
   when(http.get(eq("clientes"),anyString(),anyString(),eq(Map.class))).thenReturn(Map.of("nombre","Cliente","email","cliente@example.test"));
   var result=mvc.perform(get("/api/movil/resumen").header("Authorization","Token demo")
    .with(jwt().jwt(j -> j.claim("cliente_id",1).claim("canal","movil"))
      .authorities(new SimpleGrantedAuthority("SCOPE_datos.read"))))
     .andExpect(status().isOk());
   result.andExpect(jsonPath("$.cliente").doesNotExist()).andExpect(jsonPath("$.cuentas[0].saldo").value(100)).andExpect(jsonPath("$.cuentas[0].cliente_id").doesNotExist()).andExpect(jsonPath("$.cuentas[0].tipo").doesNotExist());
 }
}

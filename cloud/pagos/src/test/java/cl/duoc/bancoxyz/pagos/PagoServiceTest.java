package cl.duoc.bancoxyz.pagos;

import cl.duoc.bancoxyz.comun.BancoHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(PagoServiceTest.Config.class)
class PagoServiceTest {
    @Configuration static class Config {
        @Bean DataSource ds() { var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:pagos;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");return ds; }
        @Bean JdbcTemplate db(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean BancoHttp http() { return mock(BancoHttp.class); }
        @Bean TokenInterno token() { return mock(TokenInterno.class); }
        @Bean PagoService servicio(JdbcTemplate db,BancoHttp http,TokenInterno token,DataSource ds) {
            return new PagoService(db,http,token,new ObjectMapper(),new DataSourceTransactionManager(ds));
        }
    }
    @Autowired PagoService servicio;@Autowired JdbcTemplate db;@Autowired DataSource ds;
    @Autowired BancoHttp http;@Autowired TokenInterno token;
    @BeforeEach void preparar() {
        db.execute("DROP ALL OBJECTS");new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        reset(http,token);when(token.obtener()).thenReturn("Bearer interno");
        when(http.post(anyString(),anyString(),anyString(),any(),eq(Map.class))).thenReturn(Map.of("id","t1"));
    }
    private PagoService.Datos datos(String monto) { return new PagoService.Datos("t1","TRANSFERENCIA",1L,2L,new BigDecimal(monto)); }
    @Test void completaConEventoAtomico() {
        assertThat(servicio.solicitar(datos("20"),1).get("estado")).isEqualTo("COMPLETADO");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox",Integer.class)).isEqualTo(1);
    }
    @Test void repeticionNoDuplicaEventoNiLlamada() {
        servicio.solicitar(datos("20"),1);servicio.solicitar(datos("20"),1);
        verify(http,times(1)).post(anyString(),anyString(),anyString(),any(),eq(Map.class));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox",Integer.class)).isEqualTo(1);
    }
    @Test void caidaRemotaSeRecuperaSinCambiarIdentificador() {
        when(http.post(anyString(),anyString(),anyString(),any(),eq(Map.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).thenReturn(Map.of());
        assertThat(servicio.solicitar(datos("20"),1).get("estado")).isEqualTo("PENDIENTE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox",Integer.class)).isZero();
        servicio.recuperar();assertThat(servicio.obtener("t1",1).get("estado")).isEqualTo("COMPLETADO");
        verify(http,times(2)).post(eq("cuentas"),eq("/interno/movimientos"),anyString(),argThat((Object value) -> ((Map)value).get("id").equals("t1")),eq(Map.class));
    }
    @Test void identificadorAjenoNoExponePago() {
        servicio.solicitar(datos("20"),1);
        assertThatThrownBy(() -> servicio.obtener("t1",2)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> servicio.solicitar(datos("20"),2)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void identificadorConMontoDistintoSeRechaza() {
        servicio.solicitar(datos("20"),1);
        assertThatThrownBy(() -> servicio.solicitar(datos("30"),1)).isInstanceOf(ResponseStatusException.class);
        verify(http,times(1)).post(anyString(),anyString(),anyString(),any(),eq(Map.class));
    }
    @Test void rechazoPermanenteGeneraAlerta() {
        when(http.post(anyString(),anyString(),anyString(),any(),eq(Map.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(HttpStatus.CONFLICT));
        assertThat(servicio.solicitar(datos("20"),1).get("estado")).isEqualTo("RECHAZADO");
        assertThat(db.queryForObject("SELECT topico FROM outbox",String.class)).isEqualTo("seguridad.alertas");
    }
}

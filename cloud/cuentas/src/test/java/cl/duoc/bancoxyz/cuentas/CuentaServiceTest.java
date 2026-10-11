package cl.duoc.bancoxyz.cuentas;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringJUnitConfig(CuentaServiceTest.Config.class)
class CuentaServiceTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            var ds=new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:cuentas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return ds;
        }
        @Bean JdbcTemplate db(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager manager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean CuentaService servicio(JdbcTemplate db) { return new CuentaService(db); }
    }
    @Autowired CuentaService servicio;
    @Autowired JdbcTemplate db;
    @Autowired DataSource ds;
    @BeforeEach void preparar() {
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        db.update("INSERT INTO cuentas(id,cliente_id,tipo,saldo) VALUES (1,1,'AHORRO',100),(2,2,'AHORRO',0),(3,1,'CORRIENTE',0)");
    }
    private CuentaService.Movimiento transferencia(String id,String monto) {
        return new CuentaService.Movimiento(id,1,"TRANSFERENCIA",1L,2L,new BigDecimal(monto));
    }
    private BigDecimal saldo(long id) { return db.queryForObject("SELECT saldo FROM cuentas WHERE id=?",BigDecimal.class,id); }
    @Test void transferenciaAtomicaConservaTotal() {
        servicio.ejecutar(transferencia("t1","40.00"));
        assertThat(saldo(1)).isEqualByComparingTo("60");assertThat(saldo(2)).isEqualByComparingTo("40");
        assertThat(saldo(1).add(saldo(2))).isEqualByComparingTo("100");
    }
    @Test void duplicadoNoRepiteDebito() {
        servicio.ejecutar(transferencia("t1","40.00"));servicio.ejecutar(transferencia("t1","40.00"));
        assertThat(saldo(1)).isEqualByComparingTo("60");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM movimientos",Integer.class)).isEqualTo(1);
    }
    @Test void identificadorConOtrosDatosSeRechaza() {
        servicio.ejecutar(transferencia("t1","40.00"));
        assertThatThrownBy(() -> servicio.ejecutar(transferencia("t1","50.00"))).isInstanceOf(ResponseStatusException.class);
        assertThat(saldo(1)).isEqualByComparingTo("60");
    }
    @Test void falloNoModificaSaldos() {
        assertThatThrownBy(() -> servicio.ejecutar(transferencia("t1","140.00"))).isInstanceOf(ResponseStatusException.class);
        assertThat(saldo(1)).isEqualByComparingTo("100");assertThat(saldo(2)).isEqualByComparingTo("0");
    }
    @Test void cuentaAjenaNoPermiteDebito() {
        assertThatThrownBy(() -> servicio.ejecutar(new CuentaService.Movimiento("t1",2,"RETIRO",1L,null,new BigDecimal("20"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(saldo(1)).isEqualByComparingTo("100");
    }
    @Test void cierreRequiereSaldoCero() {
        assertThatThrownBy(() -> servicio.cerrar(1,1)).isInstanceOf(ResponseStatusException.class);
        assertThat(servicio.cerrar(3,1).get("estado")).isEqualTo("CERRADA");
        assertThatThrownBy(() -> servicio.ejecutar(new CuentaService.Movimiento("d1",1,"DEPOSITO",null,3L,new BigDecimal("10"))))
                .isInstanceOf(ResponseStatusException.class);
    }
    @Test void transferenciasConcurrentesNoSobregiran() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var inicio=new CountDownLatch(1);
            Callable<Boolean> op=() -> { inicio.await();try { servicio.ejecutar(transferencia(java.util.UUID.randomUUID().toString(),"80"));return true; }
                catch(ResponseStatusException error) { return false; } };
            var a=pool.submit(op);var b=pool.submit(op);inicio.countDown();
            assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(saldo(1)).isEqualByComparingTo("20");assertThat(saldo(2)).isEqualByComparingTo("80");
    }
}

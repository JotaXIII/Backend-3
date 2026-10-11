package cl.duoc.bancoxyz.cuentas;
import cl.duoc.bancoxyz.comun.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
public class CuentaController {
    private final CuentaService cuentas;
    private final JdbcTemplate db;
    private final BancoHttp http;
    public CuentaController(CuentaService cuentas, JdbcTemplate db, BancoHttp http) {
        this.cuentas=cuentas; this.db=db; this.http=http;
    }
    public record Apertura(@NotNull @Pattern(regexp="AHORRO|CORRIENTE|PRESTAMO") String tipo) {}
    public record Mantenimiento(@NotNull @Size(max=80) String alias) {}
    @PutMapping("/api/cuentas/{id}")
    public Map<String,Object> actualizar(@PathVariable long id,@Valid @RequestBody Mantenimiento datos,
                                        @AuthenticationPrincipal Jwt jwt) {
        long cliente=Identidad.cliente(jwt);
        cuentas.obtener(id,cliente);
        if (db.update("UPDATE cuentas SET alias=? WHERE id=? AND cliente_id=? AND estado='ACTIVA'",datos.alias(),id,cliente)==0)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"Cuenta cerrada");
        return cuentas.obtener(id,cliente);
    }
    @GetMapping("/api/cuentas")
    public List<Map<String,Object>> listar(@AuthenticationPrincipal Jwt jwt) { return cuentas.listar(Identidad.cliente(jwt)); }
    @GetMapping("/api/cuentas/{id}")
    public Map<String,Object> obtener(@PathVariable long id,@AuthenticationPrincipal Jwt jwt) {
        return cuentas.obtener(id,Identidad.cliente(jwt));
    }
    @PostMapping("/api/cuentas")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> abrir(@Valid @RequestBody Apertura datos,@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader("Authorization") String token) {
        http.get("clientes","/api/clientes/me",token,Map.class);
        return db.queryForMap("INSERT INTO cuentas(cliente_id,tipo) VALUES (?,?) RETURNING *",
                Identidad.cliente(jwt),datos.tipo());
    }
    @PostMapping("/api/cuentas/{id}/cierre")
    public Map<String,Object> cerrar(@PathVariable long id,@AuthenticationPrincipal Jwt jwt) {
        return cuentas.cerrar(id,Identidad.cliente(jwt));
    }
    @PostMapping("/interno/movimientos")
    public Map<String,Object> movimiento(@Valid @RequestBody CuentaService.Movimiento movimiento) {
        return cuentas.ejecutar(movimiento);
    }
}

package cl.duoc.bancoxyz.pagos;

import cl.duoc.bancoxyz.comun.Identidad;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

@RestController
public class PagoController {
    private final PagoService pagos;
    private final JdbcTemplate db;
    public PagoController(PagoService pagos,JdbcTemplate db) { this.pagos=pagos;this.db=db; }
    @PostMapping("/api/pagos")
    public ResponseEntity<Map<String,Object>> pagar(@Valid @RequestBody PagoService.Datos datos,
                                                   @AuthenticationPrincipal Jwt jwt) {
        if ("RETIRO".equals(datos.tipo())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Canal no autorizado");
        return respuesta(pagos.solicitar(datos,Identidad.cliente(jwt)));
    }
    @PostMapping("/api/pagos/retiros")
    public ResponseEntity<Map<String,Object>> retirar(@Valid @RequestBody PagoService.Datos datos,
                                                      @AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"cajeros");
        if (!"RETIRO".equals(datos.tipo())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Operacion invalida");
        return respuesta(pagos.solicitar(datos,Identidad.cliente(jwt)));
    }
    @GetMapping("/api/pagos/{id}")
    public Map<String,Object> consultar(@PathVariable String id,@AuthenticationPrincipal Jwt jwt) {
        return pagos.obtener(id,Identidad.cliente(jwt));
    }
    @GetMapping("/api/eventos")
    public List<Map<String,Object>> eventos() {
        return db.queryForList("SELECT id,topico,recibida FROM eventos_recibidos ORDER BY recibida DESC LIMIT 100");
    }
    private ResponseEntity<Map<String,Object>> respuesta(Map<String,Object> pago) {
        String estado=pago.get("estado").toString();
        return ResponseEntity.status(estado.equals("PENDIENTE")?202:estado.equals("RECHAZADO")?409:200).body(pago);
    }
}

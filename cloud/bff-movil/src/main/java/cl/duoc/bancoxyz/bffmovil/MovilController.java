package cl.duoc.bancoxyz.bffmovil;

import cl.duoc.bancoxyz.comun.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/api/movil")
public class MovilController extends BffBase {
    public MovilController(BancoHttp http) { super(http); }
    @GetMapping("/pagos/{id}")
    public Map<String,Object> consultarPago(@PathVariable String id,@RequestHeader("Authorization") String token,
                                            @AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"movil");return estado(id,token);
    }
    @GetMapping("/resumen")
    public Map<String,Object> resumen(@RequestHeader("Authorization") String token,@AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"movil");
        return Map.of("cuentas",cuentas(token).stream().map(c -> reducido(c,"id","saldo","moneda")).toList());
    }
    @PostMapping("/pagos")
    public ResponseEntity<Map<String,Object>> pagar(@RequestBody Map<String,Object> datos,
           @RequestHeader("Authorization") String token,@AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"movil");return pago(datos,token,false);
    }
}

package cl.duoc.bancoxyz.bffcajeros;

import cl.duoc.bancoxyz.comun.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/api/cajeros")
public class CajeroController extends BffBase {
    public CajeroController(BancoHttp http) { super(http); }
    @GetMapping("/retiros/{id}")
    public Map<String,Object> consultarRetiro(@PathVariable String id,@RequestHeader("Authorization") String token,
                                             @AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"cajeros");return estado(id,token);
    }
    @GetMapping("/cuentas/{id}/saldo")
    public Map<String,Object> saldo(@PathVariable long id,@RequestHeader("Authorization") String token,
                                   @AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"cajeros");
        return reducido(http.get("cuentas","/api/cuentas/"+id,token,Map.class),"id","saldo","moneda");
    }
    @PostMapping("/retiros")
    public ResponseEntity<Map<String,Object>> retirar(@RequestBody Map<String,Object> datos,
           @RequestHeader("Authorization") String token,@AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"cajeros");return pago(datos,token,true);
    }
}

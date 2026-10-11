package cl.duoc.bancoxyz.bffweb;

import cl.duoc.bancoxyz.comun.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
@RequestMapping("/api/web")
public class WebController extends BffBase {
    public WebController(BancoHttp http) { super(http); }
    @GetMapping("/pagos/{id}")
    public Map<String,Object> consultarPago(@PathVariable String id,@RequestHeader("Authorization") String token,
                                            @AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"web");return estado(id,token);
    }
    @GetMapping("/resumen")
    public Map<String,Object> resumen(@RequestHeader("Authorization") String token,@AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"web");
        return Map.of("cliente",http.get("clientes","/api/clientes/me",token,Map.class),"cuentas",cuentas(token));
    }
    @PostMapping("/cuentas")
    public Map abrir(@RequestBody Map<String,Object> datos,@RequestHeader("Authorization") String token,
                      @AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"web");return http.post("cuentas","/api/cuentas",token,datos,Map.class);
    }
    @PutMapping("/perfil")
    public Map actualizarPerfil(@RequestBody Map<String,Object> datos,@RequestHeader("Authorization") String token,
                                 @AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"web");return http.put("clientes","/api/clientes/me",token,datos,Map.class);
    }
    @PutMapping("/cuentas/{id}")
    public Map actualizarCuenta(@PathVariable long id,@RequestBody Map<String,Object> datos,
                                @RequestHeader("Authorization") String token,@AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"web");return http.put("cuentas","/api/cuentas/"+id,token,datos,Map.class);
    }
    @PostMapping("/cuentas/{id}/cierre")
    public Map cerrar(@PathVariable long id,@RequestHeader("Authorization") String token,@AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"web");return http.post("cuentas","/api/cuentas/"+id+"/cierre",token,Map.of(),Map.class);
    }
    @PostMapping("/pagos")
    public ResponseEntity<Map<String,Object>> pagar(@RequestBody Map<String,Object> datos,
           @RequestHeader("Authorization") String token,@AuthenticationPrincipal Jwt jwt) {
        Identidad.canal(jwt,"web");return pago(datos,token,false);
    }
}

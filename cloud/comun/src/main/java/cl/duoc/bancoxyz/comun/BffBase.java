package cl.duoc.bancoxyz.comun;

import org.springframework.http.ResponseEntity;
import java.util.*;

public abstract class BffBase {
    protected final BancoHttp http;
    protected BffBase(BancoHttp http) { this.http=http; }
    protected List<Map<String,Object>> cuentas(String token) {
        return (List<Map<String,Object>>)(List<?>)http.get("cuentas","/api/cuentas",token,List.class);
    }
    protected Map<String,Object> reducido(Map<String,Object> datos,String... campos) {
        var salida=new LinkedHashMap<String,Object>();
        for(String campo:campos) if(datos.containsKey(campo)) salida.put(campo,datos.get(campo));
        return salida;
    }
    protected ResponseEntity<Map<String,Object>> pago(Map<String,Object> datos,String token,boolean retiro) {
        var resultado=http.post("pagos",retiro?"/api/pagos/retiros":"/api/pagos",token,datos,Map.class);
        return respuesta(resultado);
    }
    protected ResponseEntity<Map<String,Object>> respuesta(Map<String,Object> resultado) {
        String estado=String.valueOf(resultado.get("estado"));
        return ResponseEntity.status("PENDIENTE".equals(estado)?202:200)
                .body(reducido(resultado,"id","estado","tipo","monto"));
    }
    protected Map<String,Object> estado(String id,String token) {
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,"Identificador invalido");
        return reducido(http.get("pagos","/api/pagos/"+id,token,Map.class),"id","estado","tipo","monto");
    }
}

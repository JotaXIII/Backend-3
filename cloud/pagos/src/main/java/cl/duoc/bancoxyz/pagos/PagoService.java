package cl.duoc.bancoxyz.pagos;

import cl.duoc.bancoxyz.comun.BancoHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.*;

@Service
public class PagoService {
    private final JdbcTemplate db;
    private final BancoHttp http;
    private final TokenInterno token;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    public record Datos(@NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9_-]+") String id,
                        @NotNull @Pattern(regexp="TRANSFERENCIA|DEPOSITO|PAGO|RETIRO") String tipo,
                        Long origen, Long destino,
                        @NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal monto) {}

    public PagoService(JdbcTemplate db,BancoHttp http,TokenInterno token,ObjectMapper mapper,
                       PlatformTransactionManager manager) {
        this.db=db;this.http=http;this.token=token;this.mapper=mapper;this.tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> solicitar(Datos datos,long cliente) {
        validar(datos);
        try {
            db.update("INSERT INTO pagos(id,cliente_id,tipo,origen,destino,monto) VALUES (?,?,?,?,?,?)",
                    datos.id(),cliente,datos.tipo(),datos.origen(),datos.destino(),datos.monto());
        } catch (org.springframework.dao.DuplicateKeyException ignored) { }
        var actual=obtener(datos.id(),cliente);
        if (!actual.get("tipo").equals(datos.tipo())
                || !Objects.equals(numero(actual.get("origen")),datos.origen())
                || !Objects.equals(numero(actual.get("destino")),datos.destino())
                || ((BigDecimal)actual.get("monto")).compareTo(datos.monto())!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Identificador reutilizado con otros datos");
        procesar(actual);
        return obtener(datos.id(),cliente);
    }
    private void validar(Datos datos) {
        boolean debito=!datos.tipo().equals("DEPOSITO");
        boolean credito=Set.of("TRANSFERENCIA","DEPOSITO").contains(datos.tipo());
        if ((debito && datos.origen()==null) || (credito && datos.destino()==null)
                || (!debito && datos.origen()!=null) || (!credito && datos.destino()!=null)
                || (debito && credito && datos.origen().equals(datos.destino())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cuentas invalidas");
    }
    public Map<String,Object> obtener(String id,long cliente) {
        var filas=db.queryForList("SELECT * FROM pagos WHERE id=? AND cliente_id=?",id,cliente);
        if (filas.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pago no disponible");
        return filas.getFirst();
    }
    @Scheduled(fixedDelayString="${app.pagos.intervalo:5000}")
    public void recuperar() {
        for(var pago:db.queryForList("SELECT * FROM pagos WHERE estado='PENDIENTE' ORDER BY creada LIMIT 100")) {
            procesar(pago);
        }
    }
    private void procesar(Map<String,Object> pago) {
        if (!"PENDIENTE".equals(pago.get("estado"))) return;
        String id=pago.get("id").toString();
        var movimiento=new LinkedHashMap<String,Object>();
        movimiento.put("id",id);movimiento.put("clienteId",pago.get("cliente_id"));
        movimiento.put("tipo",pago.get("tipo"));movimiento.put("origen",pago.get("origen"));
        movimiento.put("destino",pago.get("destino"));movimiento.put("monto",pago.get("monto"));
        db.update("UPDATE pagos SET intentos=intentos+1,actualizada=CURRENT_TIMESTAMP WHERE id=? AND estado='PENDIENTE'",id);
        try {
            http.post("cuentas","/interno/movimientos",token.obtener(),movimiento,Map.class);
            finalizar(id,"COMPLETADO","transacciones.completadas");
        } catch(HttpClientErrorException exception) {
            if (exception.getStatusCode().value()==401) { token.invalidar(); return; }
            if (exception.getStatusCode().value()==429) return;
            finalizar(id,"RECHAZADO","seguridad.alertas");
        } catch(ResponseStatusException | org.springframework.web.client.ResourceAccessException exception) {
            // El identificador conserva la operación pendiente.
        }
    }
    private void finalizar(String id,String estado,String topico) {
        tx.executeWithoutResult(status -> {
            var pago=db.queryForMap("SELECT * FROM pagos WHERE id=? FOR UPDATE",id);
            if (!"PENDIENTE".equals(pago.get("estado"))) return;
            db.update("UPDATE pagos SET estado=?,actualizada=CURRENT_TIMESTAMP WHERE id=?",estado,id);
            String evento=id+"-"+estado;
            try {
                String contenido=mapper.writeValueAsString(Map.of("id",evento,"pagoId",id,
                        "estado",estado,"tipo",pago.get("tipo"),"clienteId",pago.get("cliente_id")));
                db.update("INSERT INTO outbox(id,topico,contenido) VALUES (?,?,?)",evento,topico,contenido);
            } catch(com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("Evento invalido",exception);
            }
        });
    }
    private Long numero(Object value) { return value==null?null:((Number)value).longValue(); }
}

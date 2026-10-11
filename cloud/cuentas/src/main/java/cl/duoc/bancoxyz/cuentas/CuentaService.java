package cl.duoc.bancoxyz.cuentas;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.*;

@Service
public class CuentaService {
    private final JdbcTemplate db;
    public CuentaService(JdbcTemplate db) { this.db = db; }
    public record Movimiento(@NotBlank @Size(max=80) String id,
                             @Positive long clienteId, @Pattern(regexp="TRANSFERENCIA|DEPOSITO|RETIRO|PAGO") @NotNull String tipo,
                             Long origen, Long destino,
                             @NotNull @DecimalMin("0.01") @Digits(integer=14,fraction=2) BigDecimal monto) {}

    public List<Map<String,Object>> listar(long cliente) {
        return db.queryForList("SELECT * FROM cuentas WHERE cliente_id=? ORDER BY id", cliente);
    }
    public Map<String,Object> obtener(long id, long cliente) {
        var filas = db.queryForList("SELECT * FROM cuentas WHERE id=? AND cliente_id=?", id, cliente);
        if (filas.isEmpty()) throw error(HttpStatus.NOT_FOUND,"Cuenta no disponible");
        return filas.getFirst();
    }

    @Transactional
    public Map<String,Object> ejecutar(Movimiento op) {
        boolean debito = !op.tipo().equals("DEPOSITO");
        boolean credito = op.tipo().equals("TRANSFERENCIA") || op.tipo().equals("DEPOSITO");
        if (op.id() == null || op.id().isBlank() || op.clienteId() <= 0 || op.monto() == null
                || op.monto().signum() <= 0 || op.monto().scale() > 2
                || !Set.of("TRANSFERENCIA","DEPOSITO","RETIRO","PAGO").contains(op.tipo()))
            throw error(HttpStatus.BAD_REQUEST,"Movimiento invalido");
        if ((debito && op.origen() == null) || (credito && op.destino() == null)
                || (!debito && op.origen() != null) || (!credito && op.destino() != null)
                || (debito && credito && op.origen().equals(op.destino())))
            throw error(HttpStatus.BAD_REQUEST,"Cuentas invalidas");
        var ids = new TreeSet<Long>();
        if (debito) ids.add(op.origen());
        if (credito) ids.add(op.destino());
        var cuentas = new HashMap<Long,Map<String,Object>>();
        for (Long id : ids) {
            var filas = db.queryForList("SELECT * FROM cuentas WHERE id=? FOR UPDATE", id);
            if (filas.isEmpty()) throw error(HttpStatus.NOT_FOUND,"Cuenta no disponible");
            cuentas.put(id, filas.getFirst());
        }
        var anterior = db.queryForList("SELECT * FROM movimientos WHERE id=?", op.id());
        if (!anterior.isEmpty()) {
            var fila = anterior.getFirst();
            if (((Number)fila.get("cliente_id")).longValue() != op.clienteId()
                    || !fila.get("tipo").equals(op.tipo())
                    || !Objects.equals(numero(fila.get("origen")), op.origen())
                    || !Objects.equals(numero(fila.get("destino")), op.destino())
                    || ((BigDecimal)fila.get("monto")).compareTo(op.monto()) != 0)
                throw error(HttpStatus.CONFLICT,"Identificador reutilizado con otros datos");
            return fila;
        }
        for (var cuenta : cuentas.values()) {
            if (!"ACTIVA".equals(cuenta.get("estado"))) throw error(HttpStatus.CONFLICT,"Cuenta cerrada");
            if (!"CLP".equals(cuenta.get("moneda"))) throw error(HttpStatus.CONFLICT,"Moneda incompatible");
        }
        var propia = cuentas.get(debito ? op.origen() : op.destino());
        if (((Number)propia.get("cliente_id")).longValue() != op.clienteId())
            throw error(HttpStatus.NOT_FOUND,"Cuenta no disponible");
        if (debito && ((BigDecimal)propia.get("saldo")).compareTo(op.monto()) < 0)
            throw error(HttpStatus.CONFLICT,"Saldo insuficiente");
        if (debito) db.update("UPDATE cuentas SET saldo=saldo-? WHERE id=?", op.monto(), op.origen());
        if (credito) db.update("UPDATE cuentas SET saldo=saldo+? WHERE id=?", op.monto(), op.destino());
        db.update("INSERT INTO movimientos(id,cliente_id,tipo,origen,destino,monto) VALUES (?,?,?,?,?,?)",
                op.id(), op.clienteId(), op.tipo(), op.origen(), op.destino(), op.monto());
        return db.queryForMap("SELECT * FROM movimientos WHERE id=?", op.id());
    }

    @Transactional
    public Map<String,Object> cerrar(long id, long cliente) {
        var filas = db.queryForList("SELECT * FROM cuentas WHERE id=? AND cliente_id=? FOR UPDATE", id, cliente);
        if (filas.isEmpty()) throw error(HttpStatus.NOT_FOUND,"Cuenta no disponible");
        if (((BigDecimal)filas.getFirst().get("saldo")).signum() != 0)
            throw error(HttpStatus.CONFLICT,"La cuenta conserva saldo");
        db.update("UPDATE cuentas SET estado='CERRADA' WHERE id=?", id);
        return obtener(id,cliente);
    }
    private Long numero(Object value) { return value == null ? null : ((Number)value).longValue(); }
    private ResponseStatusException error(HttpStatus status,String texto) { return new ResponseStatusException(status,texto); }
}

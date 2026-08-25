package cl.duoc.bancoxyz.batch.processor;

import cl.duoc.bancoxyz.batch.domain.MovimientoAnual;
import cl.duoc.bancoxyz.batch.domain.MovimientoAnualCsv;
import cl.duoc.bancoxyz.batch.support.FechasLegacy;
import cl.duoc.bancoxyz.batch.support.RegistroInvalidoException;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

public class MovimientoAnualProcessor implements ItemProcessor<MovimientoAnualCsv, MovimientoAnual> {

    private static final Set<String> TIPOS_VALIDOS = Set.of("deposito", "retiro", "compra");
    @Override
    // Valida el movimiento y convierte sus valores al formato interno.
    public MovimientoAnual process(MovimientoAnualCsv item) {
        if (item.cuentaId() == null || esVacio(item.transaccion()) || esVacio(item.descripcion())) {
            throw new RegistroInvalidoException("Campos obligatorios ausentes", item);
        }

        BigDecimal monto;
        try {
            monto = new BigDecimal(item.monto().trim());
        } catch (Exception exception) {
            throw new RegistroInvalidoException("Monto invalido", item);
        }

        String tipo = item.transaccion().trim().toLowerCase(Locale.ROOT);
        if (!TIPOS_VALIDOS.contains(tipo) || monto.signum() == 0) {
            throw new RegistroInvalidoException("Tipo o monto invalido", item);
        }
        if (tipo.equals("deposito") && monto.signum() < 0) {
            throw new RegistroInvalidoException("Deposito con monto negativo", item);
        }
        if (!tipo.equals("deposito") && monto.signum() > 0) {
            throw new RegistroInvalidoException("Cargo con monto positivo", item);
        }

        var fecha = FechasLegacy.parsear(item.fecha(), item);
        String descripcion = item.descripcion().trim();
        return new MovimientoAnual(item.cuentaId(), fecha, tipo, monto, descripcion);
    }

    private boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
    }
}

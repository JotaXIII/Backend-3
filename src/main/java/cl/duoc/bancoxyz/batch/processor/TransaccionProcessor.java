package cl.duoc.bancoxyz.batch.processor;

import cl.duoc.bancoxyz.batch.domain.Transaccion;
import cl.duoc.bancoxyz.batch.domain.TransaccionCsv;
import cl.duoc.bancoxyz.batch.support.FechasLegacy;
import cl.duoc.bancoxyz.batch.support.RegistroInvalidoException;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

public class TransaccionProcessor implements ItemProcessor<TransaccionCsv, Transaccion> {

    private static final Set<String> TIPOS_VALIDOS = Set.of("debito", "credito");
    @Override
    // Transacciones normalizadas y anomalías.
    public Transaccion process(TransaccionCsv item) {
        if (item.id() == null || item.monto() == null || item.tipo() == null) {
            throw new RegistroInvalidoException("Campos obligatorios ausentes", item);
        }

        String tipo = item.tipo().trim().toLowerCase(Locale.ROOT);
        if (!TIPOS_VALIDOS.contains(tipo)) {
            throw new RegistroInvalidoException("Tipo de transaccion invalido", item);
        }

        BigDecimal monto;
        try {
            monto = new BigDecimal(item.monto().trim());
        } catch (NumberFormatException exception) {
            throw new RegistroInvalidoException("Monto invalido", item);
        }

        var fecha = FechasLegacy.parsear(item.fecha(), item);
        return new Transaccion(item.id(), fecha, monto, tipo, monto.signum() <= 0);
    }
}

package cl.duoc.bancoxyz.batch.processor;

import cl.duoc.bancoxyz.batch.domain.InteresCalculado;
import cl.duoc.bancoxyz.batch.domain.InteresCsv;
import cl.duoc.bancoxyz.batch.support.RegistroInvalidoException;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

public class InteresProcessor implements ItemProcessor<InteresCsv, InteresCalculado> {

    private static final Map<String, BigDecimal> TASAS = Map.of(
            "ahorro", new BigDecimal("0.01"),
            "prestamo", new BigDecimal("0.02"));
    @Override
    public InteresCalculado process(InteresCsv item) {
        if (item.cuentaId() == null || esVacio(item.nombre()) || esVacio(item.saldo())
                || esVacio(item.edad()) || esVacio(item.tipo())) {
            throw new RegistroInvalidoException("Campos obligatorios ausentes", item);
        }

        BigDecimal saldo;
        int edad;
        try {
            saldo = new BigDecimal(item.saldo().trim());
            edad = Integer.parseInt(item.edad().trim());
        } catch (NumberFormatException exception) {
            throw new RegistroInvalidoException("Saldo o edad invalidos", item);
        }

        if (saldo.signum() <= 0 || edad < 18 || edad > 75) {
            throw new RegistroInvalidoException("Saldo o edad fuera de rango", item);
        }

        String tipo = item.tipo().trim().toLowerCase(Locale.ROOT);
        BigDecimal tasa = TASAS.get(tipo);
        if (tasa == null) {
            throw new RegistroInvalidoException("Tipo de cuenta invalido", item);
        }

        String nombre = item.nombre().trim();
        BigDecimal interes = saldo.multiply(tasa).setScale(2, RoundingMode.HALF_UP);
        BigDecimal saldoFinal = saldo.add(interes);
        return new InteresCalculado(item.cuentaId(), nombre, saldo, edad, tipo, tasa,
                interes, saldoFinal.setScale(2, RoundingMode.HALF_UP));
    }

    private boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
    }
}

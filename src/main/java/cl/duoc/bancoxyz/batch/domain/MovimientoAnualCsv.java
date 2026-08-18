package cl.duoc.bancoxyz.batch.domain;

public record MovimientoAnualCsv(
        Long cuentaId,
        String fecha,
        String transaccion,
        String monto,
        String descripcion) {
}

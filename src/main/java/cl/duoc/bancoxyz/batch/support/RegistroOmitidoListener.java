package cl.duoc.bancoxyz.batch.support;

import org.springframework.batch.core.SkipListener;
import org.springframework.jdbc.core.JdbcTemplate;

public class RegistroOmitidoListener<I, O> implements SkipListener<I, O> {

    private final JdbcTemplate jdbcTemplate;
    private final String job;

    public RegistroOmitidoListener(JdbcTemplate jdbcTemplate, String job) {
        this.jdbcTemplate = jdbcTemplate;
        this.job = job;
    }

    @Override
    public void onSkipInRead(Throwable throwable) {
        guardar("LECTURA", "No disponible", throwable);
    }

    @Override
    public void onSkipInProcess(I item, Throwable throwable) {
        String datos = throwable instanceof RegistroInvalidoException error
                ? error.getDatos()
                : String.valueOf(item);
        guardar("PROCESAMIENTO", datos, throwable);
    }

    @Override
    public void onSkipInWrite(O item, Throwable throwable) {
        guardar("ESCRITURA", String.valueOf(item), throwable);
    }

    private void guardar(String etapa, String datos, Throwable throwable) {
        jdbcTemplate.update("""
                INSERT INTO registros_rechazados(job, etapa, datos, motivo)
                VALUES (?, ?, ?, ?)
                """, job, etapa, limitar(datos, 1000), limitar(throwable.getMessage(), 500));
    }

    private String limitar(String valor, int maximo) {
        if (valor == null || valor.length() <= maximo) {
            return valor;
        }
        return valor.substring(0, maximo);
    }
}

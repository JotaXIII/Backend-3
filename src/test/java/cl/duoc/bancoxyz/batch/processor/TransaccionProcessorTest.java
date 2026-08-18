package cl.duoc.bancoxyz.batch.processor;

import cl.duoc.bancoxyz.batch.domain.TransaccionCsv;
import cl.duoc.bancoxyz.batch.support.RegistroInvalidoException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransaccionProcessorTest {

    private final TransaccionProcessor processor = new TransaccionProcessor();

    @Test
    void marcaMontosNoPositivosComoAnomalia() throws Exception {
        var resultado = processor.process(new TransaccionCsv(1L, "2024/01/03", "-200", "DEBITO"));

        assertThat(resultado.anomalia()).isTrue();
        assertThat(resultado.tipo()).isEqualTo("debito");
        assertThat(resultado.fecha()).hasToString("2024-01-03");
    }

    @Test
    void rechazaTiposDesconocidos() {
        assertThatThrownBy(() -> processor.process(
                new TransaccionCsv(2L, "2024-01-05", "700.00", "transferencia")))
                .isInstanceOf(RegistroInvalidoException.class)
                .hasMessageContaining("Tipo de transaccion");
    }
}

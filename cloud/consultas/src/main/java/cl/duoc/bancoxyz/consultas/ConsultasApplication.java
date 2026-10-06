package cl.duoc.bancoxyz.consultas;

import cl.duoc.bancoxyz.comun.SeguridadConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(SeguridadConfiguration.class)
// Inicia el servicio y carga su configuración.
public class ConsultasApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsultasApplication.class, args);
    }
}

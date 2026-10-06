package cl.duoc.bancoxyz.solicitudes;

import cl.duoc.bancoxyz.comun.SeguridadConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Import(SeguridadConfiguration.class)
@EnableScheduling
// Configuración de inicio.
public class SolicitudesApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolicitudesApplication.class, args);
    }
}

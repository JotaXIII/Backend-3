package cl.duoc.bancoxyz.descubrimiento;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
// Inicia el servicio y carga su configuración.
public class DescubrimientoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DescubrimientoApplication.class, args);
    }
}

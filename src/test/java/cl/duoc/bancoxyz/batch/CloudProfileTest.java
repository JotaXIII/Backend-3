package cl.duoc.bancoxyz.batch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"JMS_PASSWORD=test", "spring.jms.listener.auto-startup=false",
        "management.health.jms.enabled=false"})
@ActiveProfiles({"cloud", "test"})
@AutoConfigureMockMvc
class CloudProfileTest {
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired JmsListenerEndpointRegistry registry;
    @Autowired JmsTemplate jms;

    @Test
    void saludPublicaYRestoDenegadoSinDecoder() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/negocio")).andExpect(status().isForbidden());
        mvc.perform(post("/negocio")).andExpect(status().isForbidden());
    }

    @Test
    void configuraWorkerTransaccionalSinLanzamientoAutomatico() {
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("servlet");
        assertThat(environment.getProperty("spring.batch.job.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.artemis.broker-url")).isEqualTo("tcp://mensajeria:61616");
        assertThat(jms.isSessionTransacted()).isTrue();
        assertThat(registry.getListenerContainers()).hasSize(1);
        var container = (DefaultMessageListenerContainer) registry.getListenerContainers().iterator().next();
        assertThat(container.isSessionTransacted()).isTrue();
        assertThat(container.getConcurrentConsumers()).isEqualTo(1);
        assertThat(container.getMaxConcurrentConsumers()).isEqualTo(1);
        assertThat(container.isRunning()).isFalse();
    }
}

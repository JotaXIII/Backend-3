package cl.duoc.bancoxyz.pagos;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name="app.kafka.enabled",havingValue="true",matchIfMissing=true)
public class EventosKafka {
    private final KafkaProducer<String,String> productor;
    private final KafkaConsumer<String,String> consumidor;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(EventosKafka.class);

    public EventosKafka(JdbcTemplate db,ObjectMapper mapper,PlatformTransactionManager manager,
                         @Value("${app.kafka.bootstrap}") String bootstrap) {
        this.db=db;this.mapper=mapper;tx=new TransactionTemplate(manager);
        var props=new Properties();
        props.put("bootstrap.servers",bootstrap);
        props.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks","all");props.put("enable.idempotence","true");
        props.put("max.block.ms","5000");props.put("delivery.timeout.ms","10000");
        props.put("request.timeout.ms","5000");props.put("linger.ms","0");
        productor=new KafkaProducer<>(props);
        var config=new Properties();config.put("bootstrap.servers",bootstrap);
        config.put("group.id","auditoria-bancaria");config.put("enable.auto.commit","false");
        config.put("auto.offset.reset","earliest");config.put("max.poll.records","50");
        config.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        consumidor=new KafkaConsumer<>(config);
        consumidor.subscribe(List.of("transacciones.completadas","seguridad.alertas"));
    }

    @Scheduled(fixedDelayString="${app.eventos.publicacion:1000}")
    public void publicar() {
        try {
            tx.executeWithoutResult(status -> {
                var eventos=db.queryForList("SELECT * FROM outbox WHERE publicada=FALSE ORDER BY creada LIMIT 20 FOR UPDATE SKIP LOCKED");
                for(var evento:eventos) {
                    try {
                        productor.send(new ProducerRecord<>(evento.get("topico").toString(),
                                evento.get("id").toString(),evento.get("contenido").toString())).get(12,TimeUnit.SECONDS);
                        db.update("UPDATE outbox SET publicada=TRUE WHERE id=?",evento.get("id"));
                    } catch(Exception exception) {
                        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                        throw new IllegalStateException("Publicacion pendiente",exception);
                    }
                }
            });
        } catch(Exception exception) { log.warn("Eventos pendientes de publicacion"); }
    }

    @Scheduled(fixedDelayString="${app.eventos.consumo:1000}")
    public synchronized void consumir() {
        try {
            for(var evento:consumidor.poll(Duration.ofMillis(500))) {
                var partition=new TopicPartition(evento.topic(),evento.partition());
                try {
                    var contenido=mapper.readTree(evento.value());
                    String id=contenido.path("id").asText();
                    if (id.isBlank()) throw new IllegalArgumentException("Evento sin identificador");
                    db.update("INSERT INTO eventos_recibidos(id,topico,contenido) VALUES (?,?,?) ON CONFLICT (id) DO NOTHING",
                            id,evento.topic(),evento.value());
                    consumidor.commitSync(Map.of(partition,new OffsetAndMetadata(evento.offset()+1)));
                } catch(Exception exception) {
                    consumidor.seek(partition,evento.offset());
                    for(var pending:consumidor.assignment()) {
                        var committed=consumidor.committed(Set.of(pending)).get(pending);
                        if (committed!=null) consumidor.seek(pending,committed.offset());
                        else consumidor.seekToBeginning(Set.of(pending));
                    }
                    log.warn("Evento pendiente de consumo");
                    return;
                }
            }
        } catch(Exception exception) { log.warn("Consumo de eventos temporalmente no disponible"); }
    }
    @PreDestroy
    public synchronized void cerrar() {
        consumidor.close(Duration.ofSeconds(3));productor.close(Duration.ofSeconds(3));
    }
}

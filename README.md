# Banco XYZ Batch

Proyecto de la Semana 2 para migrar tres procesos legacy a Spring Batch.

## Procesos

- `transaccionesJob`: procesa el reporte de transacciones diarias.
- `interesesJob`: calcula los intereses mensuales.
- `estadosAnualesJob`: genera los estados de cuenta anuales.

Cada proceso lee su archivo CSV, valida y transforma los registros, y guarda el resultado en PostgreSQL.

## Configuración solicitada

- Chunks de 5 registros.
- Procesamiento paralelo con 3 hilos (`Batch-Thread-1` a `Batch-Thread-3`).
- Omisión de registros inválidos mediante tolerancia a fallos.
- Reintento de errores de bloqueo hasta 3 veces.
- Logs de inicio, término, estado y cantidad de registros omitidos.

## Ejecutar

Requisitos: Java 21, Maven y Docker.

1. Iniciar PostgreSQL:

```bash
docker compose up -d
```

2. Ejecutar un Job:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.batch.job.name=transaccionesJob"
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.batch.job.name=interesesJob"
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.batch.job.name=estadosAnualesJob"
```

3. Ejecutar las pruebas:

```bash
mvn test
```

Las pruebas verifican los tres Jobs, los registros omitidos y el informe anual generado en `build/reportes/estados_cuenta_anuales.csv`.

La evidencia comprobable de ejecución se encuentra en `docs/evidencia/ejecucion.pdf`.

## Datos de entrada

Los archivos de prueba están en `src/main/resources/data/`:

- `transacciones.csv`
- `intereses.csv`
- `cuentas_anuales.csv`

La conexión y las rutas pueden cambiarse mediante las variables `DB_URL`, `DB_USER`, `DB_PASSWORD`, `TRANSACCIONES_FILE`, `INTERESES_FILE` y `CUENTAS_ANUALES_FILE`.

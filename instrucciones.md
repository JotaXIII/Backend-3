# Ejecución y pruebas del Banco XYZ

## Preparación

Instalar Docker con soporte de contenedores Linux y Compose 2.24.4 o posterior. Para pruebas locales instalar Java 21 y Maven 3.9. `mvn -version` debe indicar Java 21, aunque `java -version` pueda apuntar a otra instalación. Para los scripts Python se necesita Python 3.10 o posterior; el informe requiere ReportLab y PyMuPDF.

Desde la raíz del proyecto:

```powershell
Copy-Item .env.example .env
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 300
docker compose ps
```

El primer inicio crea los esquemas PostgreSQL y dos perfiles de demostración: clientes 1 y 2. Las cuentas se abren con saldo cero. La preparación funcional deposita fondos mediante una operación trazable. Kafka se inicia en modo KRaft y un contenedor crea los tópicos con tres particiones.

Si PostgreSQL ya tiene un volumen de una configuración antigua, los scripts iniciales no vuelven a ejecutarse. Utilizar los volúmenes propios de este proyecto o aplicar una migración revisada; no eliminar datos para resolver errores de inicio.

## Autenticación

Los secretos siguientes son exclusivamente locales y se reemplazan desde `.env`:

| Cliente OAuth | Secreto local | Permisos |
|---|---|---|
| web-demo | web-local | datos.read, pagos.write, cuentas.write, clientes.write |
| movil-demo | movil-local | datos.read, pagos.write |
| cajeros-demo | cajeros-local | datos.read, retiros.write |
| otro-web | otro-local | datos.read, pagos.write, cuentas.write, clientes.write |
| operador | operador-local | datos.read, procesos.write |
| lector | lector-local | datos.read |
| administrador | admin-local | datos.read, banco.admin, procesos.write |
| pagos-interno | interno-local | operaciones.internal |

```powershell
$secret = 'web-local'
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('web-demo:' + $secret))
$respuesta = Invoke-RestMethod -Method Post -Uri http://localhost:9000/oauth2/token -Headers @{Authorization="Basic $basic"} -Body @{grant_type='client_credentials';scope='datos.read pagos.write cuentas.write clientes.write'}
$headers = @{Authorization='Bearer '+$respuesta.access_token}
Invoke-RestMethod -Uri http://localhost:8090/api/web/resumen -Headers $headers
```

Los tokens duran diez minutos. El canal y el cliente se obtienen del token firmado, no del cuerpo de la solicitud. Sin token válido se obtiene 401; sin permiso o con otro canal se obtiene 403. Los recursos de otro propietario devuelven 404.

## Operaciones por canal

| Canal | Método y ruta | Resultado |
|---|---|---|
| Web | GET /api/web/resumen | Perfil y cuentas completos |
| Web | POST /api/web/cuentas | Apertura con tipo AHORRO, CORRIENTE o PRESTAMO |
| Web | PUT /api/web/cuentas/{id} | Cambio de alias |
| Web | POST /api/web/cuentas/{id}/cierre | Cierre con saldo cero |
| Web | PUT /api/web/perfil | Nombre, email y teléfono |
| Web | POST /api/web/pagos | Transferencia, depósito o pago |
| Web | GET /api/web/pagos/{id} | Estado de una operación propia |
| Móvil | GET /api/movil/resumen | Identificador, saldo y moneda |
| Móvil | POST /api/movil/pagos | Transferencia, depósito o pago |
| Móvil | GET /api/movil/pagos/{id} | Estado compacto |
| Cajeros | GET /api/cajeros/cuentas/{id}/saldo | Saldo propio |
| Cajeros | POST /api/cajeros/retiros | Retiro |
| Cajeros | GET /api/cajeros/retiros/{id} | Estado del retiro |

```powershell
$cuenta = Invoke-RestMethod -Method Post -Uri http://localhost:8090/api/web/cuentas -Headers $headers -ContentType application/json -Body '{"tipo":"AHORRO"}'
$deposito = @{id=[Guid]::NewGuid().ToString('N');tipo='DEPOSITO';destino=$cuenta.id;monto=1000}
Invoke-RestMethod -Method Post -Uri http://localhost:8090/api/web/pagos -Headers $headers -ContentType application/json -Body ($deposito|ConvertTo-Json)
```

Reutilizar el mismo `id` y contenido para reintentar. Un cambio de contenido con el mismo identificador produce 409. PENDIENTE devuelve 202 y debe consultarse hasta finalizar; no representa una operación confirmada. COMPLETADO devuelve 200 y RECHAZADO devuelve 409. Los importes positivos admiten hasta dos decimales y las operaciones utilizan CLP.

Los depósitos representan una entrada de fondos simulada; una integración real debe validar el origen externo antes de acreditar saldo. Las transferencias requieren origen y destino; los pagos y retiros solo origen; los depósitos solo destino.

## Verificación integral

```powershell
powershell -NoProfile -File scripts/verificar-banco.ps1
powershell -NoProfile -File scripts/verificar-banco.ps1 -ProbarEscalabilidad
```

El script verifica autenticación, separación de canales, transferencia atómica, repetición idempotente, respuestas compactas, retiro, rechazo por saldo insuficiente, acceso a recursos ajenos, publicación y consumo Kafka y los tres batch. Guarda `docs/evidencia/verificacion-banco.json`. Deposita fondos y ejecuta operaciones de demostración; cada ejecución utiliza identificadores nuevos.

La opción de escalabilidad deja dos réplicas de clientes, cuentas y pagos. Para volver a una instancia:

```powershell
docker compose up -d --scale clientes=1 --scale cuentas=1 --scale pagos=1
```

## Batch y períodos

Obtener un token de `operador` con `datos.read procesos.write`. Solicitar un proceso:

```powershell
$solicitud = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/procesos/transacciones?periodo=2024-01-03' -Headers $headersOperador
Invoke-RestMethod -Uri ('http://localhost:8080/api/solicitudes/'+$solicitud.id) -Headers $headersOperador
Invoke-RestMethod -Uri 'http://localhost:8080/api/consultas/transacciones?limite=100&pagina=0' -Headers $headersOperador
```

Usar `periodo=2024-01` para intereses y `periodo=2024` para estados anuales. Sin período se procesa el archivo completo. La fecha diaria y el año filtran movimientos; el mes identifica el cálculo aplicado al saldo del archivo. La frecuencia de ejecución se controla externamente o mediante solicitudes programadas. El nombre del job puede seleccionarse en ejecución independiente con `BATCH_JOB`.

Parámetros: `BATCH_CHUNK_SIZE` (100), `BATCH_THREADS` (3), `BATCH_GRID_SIZE` (3), `BATCH_MAX_ATTEMPTS` (3) y `BATCH_RETRY_DELAY` (2000 ms). Los reintentos de un job conservan su instancia y checkpoints. Los archivos anuales llevan el identificador de ejecución en el nombre. El procesamiento rechaza registros inválidos y conserva su motivo.

## Datos del repositorio de entrada

```powershell
powershell -NoProfile -File scripts/importar-datos.ps1
```

El script obtiene `KariVillagran/fin_legacy_data`, identifica los tres CSV por columnas y escribe una adaptación en `build/datos-importados` sin alterar los originales. También acepta `-Origen` con una copia local. Si el contrato de columnas cambia, se detiene indicando el campo faltante.

Para procesar esos archivos en contenedores, montar `./build/datos-importados:/app/entradas:ro` en batch y configurar `TRANSACCIONES_FILE=file:/app/entradas/transacciones.csv`, `INTERESES_FILE=file:/app/entradas/intereses.csv` y `CUENTAS_ANUALES_FILE=file:/app/entradas/cuentas_anuales.csv`. Reconstruir o recrear batch tras configurar las entradas. Revisar las reglas mensuales frente al origen, cuyo README describe un archivo trimestral.

## Pruebas y resultados

```powershell
mvn test
mvn -f cloud/pom.xml test
python scripts/comparar-resultados.py
mvn '-Dbatch.performance=true' '-Dapp.batch.threads=1' '-Dapp.batch.grid-size=1' '-Dtest=RendimientoBatchTest' test
mvn '-Dbatch.performance=true' '-Dapp.batch.threads=3' '-Dapp.batch.grid-size=3' '-Dtest=RendimientoBatchTest' test
```

La comparación utiliza una referencia Python independiente para las reglas documentadas y los CSV incluidos. No sustituye una ejecución del sistema COBOL. Los resultados actuales son 10 transacciones con dos anomalías, cinco cálculos de intereses con tres rechazos y siete estados anuales a partir de ocho movimientos, con un rechazo.

Las pruebas H2 verifican consistencia y concurrencia local; la integración PostgreSQL, Kafka y red se confirma con el script integral. No interpretar un perfil sin Kafka como prueba de la mensajería.

Para detener conservando datos, ejecutar `docker compose down`. Para consultar logs, usar `docker compose logs --tail 100 pagos cuentas batch`. No publicar tokens, secretos ni claves privadas en las evidencias.

# Procesamiento y consultas distribuidas

Tres procesos por lotes con solicitudes asíncronas, consultas autenticadas y tolerancia a fallos.

## Estructura

- `src/`: procesos, validaciones, particionamiento y consumidor de solicitudes.
- `cloud/autorizacion/`: emisión de tokens OAuth2.
- `cloud/configuracion/`: configuración centralizada.
- `cloud/descubrimiento/`: registro y descubrimiento de servicios.
- `cloud/consultas/`: lectura paginada de resultados.
- `cloud/solicitudes/`: entrada HTTP, publicación y seguimiento de solicitudes.
- `cloud/comun/`: validación de tokens y permisos.
- `scripts/verificar.ps1`: comprobación funcional.
- `docs/`: consultas SQL y evidencias de ejecución.

## Ejecutar

Requisitos: Docker con contenedores Linux y Docker Compose v2. Para compilar fuera de contenedores: Java 21 y Maven.

Desde la carpeta del proyecto:

```powershell
Copy-Item .env.example .env
docker compose up -d --build --wait --wait-timeout 180
docker compose ps
```

La primera construcción descarga imágenes y dependencias. El inicio espera que las dependencias estén saludables. Las credenciales de ejemplo sirven para la ejecución local; pueden cambiarse en `.env` antes del primer inicio.

| Servicio | Dirección |
|---|---|
| API de solicitudes | http://localhost:8080 |
| Autorización | http://localhost:9000 |
| Descubrimiento | http://localhost:8761 |
| Configuración | http://localhost:8888 |
| Consola de mensajería | http://localhost:8161 |
| Base de datos | localhost:5433 |

Las consultas y el procesador solo son accesibles dentro de la red de contenedores. La consola, el registro, la configuración y la base de datos se publican únicamente en la interfaz local.

Para detener sin eliminar los datos:

```powershell
docker compose down
```

## Autenticación

Se utiliza `client_credentials` para llamadas autenticadas entre aplicaciones. Los tokens duran diez minutos y se validan por firma RSA, emisor, destinatario y vigencia.

| Cliente | Secreto local | Permisos |
|---|---|---|
| operador | operador-local | datos.read, procesos.write |
| lector | lector-local | datos.read |

Obtener un token:

```powershell
$secret = if ($env:OAUTH_CLIENT_SECRET) { $env:OAUTH_CLIENT_SECRET } else { "operador-local" }
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("operador:" + $secret))
$token = Invoke-RestMethod -Method Post -Uri http://localhost:9000/oauth2/token -Headers @{
    Authorization = "Basic $basic"
} -Body @{
    grant_type = "client_credentials"
    scope = "datos.read procesos.write"
}
$headers = @{ Authorization = "Bearer " + $token.access_token }
```

Si se cambian los secretos en `.env`, utilizar los mismos valores en la terminal. Los endpoints protegidos responden `401` sin token válido y `403` sin el permiso requerido. Las solicitudes ajenas responden `404`.

## Procesos

| Proceso | Operación | Resultado de los datos incluidos |
|---|---|---|
| transacciones | valida y resume transacciones | 9 registros, 2 anomalías, 1 rechazo |
| intereses | calcula intereses y saldos | 4 registros, 4 rechazos |
| estados-anuales | consolida movimientos y genera informe | 8 movimientos, 7 estados, 1 rechazo |

Solicitar una ejecución y consultar su estado:

```powershell
$solicitud = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/procesos/transacciones -Headers $headers
Invoke-RestMethod -Uri ("http://localhost:8080/api/solicitudes/" + $solicitud.id) -Headers $headers
```

La respuesta inicial es `202 Accepted`. Los estados son `PENDIENTE`, `ENVIADA`, `COMPLETED` y `FAILED`.

Cada proceso utiliza chunks de cinco registros, tres particiones, omisión de registros inválidos y reintento de bloqueos. Las ejecuciones se atienden una a una porque cada proceso reemplaza sus resultados anteriores. El informe anual permanece en el volumen `reportes`.

## Endpoints

| Método | Ruta | Permiso |
|---|---|---|
| POST | /api/procesos/{proceso} | procesos.write |
| GET | /api/solicitudes/{id} | datos.read y mismo propietario |
| GET | /api/consultas/transacciones | datos.read |
| GET | /api/consultas/intereses | datos.read |
| GET | /api/consultas/estados-anuales | datos.read |
| GET | /api/consultas/rechazados | datos.read |
| GET | /api/resiliencia | datos.read |
| GET | /actuator/health | público |

Las consultas aceptan `limite` entre 1 y 1000 y `pagina` desde cero. Ejemplo:

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/consultas/transacciones?limite=5&pagina=0" -Headers $headers
```

Devuelven `disponible=true` y los datos. Ante indisponibilidad remota devuelven `disponible=false`, datos vacíos y un mensaje temporal.

## Resiliencia y mensajería

Las consultas se descubren por su nombre en el registro. Resilience4j limita la conexión a dos segundos y la lectura a tres segundos, realiza hasta dos intentos y abre el circuito cuando al menos la mitad de las últimas cuatro llamadas falla. Tras diez segundos admite dos llamadas de prueba para recuperarse. Los errores de permisos o validación no se convierten en respuestas alternativas.

Las solicitudes se guardan antes de publicarlas en `procesos.solicitudes`. Un publicador reintenta las pendientes cada segundo. El procesador consume con sesión transaccional y devuelve el estado por `procesos.resultados`. El identificador de solicitud se conserva como parámetro de ejecución: una redelivery de un proceso completado devuelve el resultado sin ejecutar de nuevo.

La base de datos y el broker tienen volúmenes persistentes. La publicación y la ejecución no forman una transacción distribuida; los duplicados se resuelven por el identificador. Una ejecución fallida se informa como `FAILED` y requiere una solicitud nueva.

## Pruebas y evidencia

```powershell
mvn test
mvn -f cloud/pom.xml test
powershell -NoProfile -File scripts/verificar.ps1 -ProbarInterrupciones
```

El script comprueba permisos, resultados, mensajería, propiedad de solicitudes, repetición de mensajes y recuperación. Detiene temporalmente consultas y mensajería y las inicia nuevamente. Guarda los resultados en `docs/evidencia/verificacion-cloud.json`.

Los informes de pruebas quedan en `target/surefire-reports` de cada módulo.

El informe de ejecución está en `docs/evidencia/ejecucion-cloud.pdf`. Las salidas completas están en `docs/evidencia/salidas-cloud.txt` y los datos operativos en `docs/evidencia/evidencia-operativa.json`. Incluyen inicio de cada aplicación, imágenes, emisión OAuth2 sin secretos y persistencia de resultados.

Para actualizar el informe con los componentes en ejecución:

```powershell
powershell -NoProfile -File scripts/recopilar-evidencia.ps1
python scripts/generar-informe.py
```

La generación del PDF requiere Python con ReportLab. El PDF incluido puede revisarse sin esa dependencia.

La ejecución verificada reúne 65 pruebas sin fallos ni errores; su resumen está en `docs/evidencia/pruebas.json`.

## Ejecución por lotes independiente

```powershell
docker compose up -d postgres
$env:DB_URL = "jdbc:postgresql://localhost:5433/banco_xyz"
mvn spring-boot:run '-Dspring-boot.run.arguments=--spring.batch.job.name=transaccionesJob'
```

Para otros procesos, reemplazar el nombre por `interesesJob` o `estadosAnualesJob`. Esta modalidad utiliza el perfil predeterminado y no inicia el consumidor ni la API.

## Despliegue

Para un servidor remoto, configurar `OAUTH_ISSUER` con la dirección pública del servidor de autorización y mantener el mismo emisor en las APIs. La URL interna de claves permite validar tokens sin depender de la resolución pública dentro de los contenedores.

El entorno incluido es una instancia local reproducible. Un despliegue público requiere HTTPS, secretos externos y claves de firma persistentes. Las claves actuales se regeneran al reiniciar autorización; solicitar tokens nuevos tras ese reinicio. La réplica del procesador requiere coordinación adicional para evitar que sus etapas de limpieza se ejecuten simultáneamente.

## Paquete

El paquete contiene código, documentación y evidencias, sin cachés, credenciales locales ni archivos de compilación.

Para generar el comprimido sin cachés, credenciales locales ni archivos de compilación:

```powershell
powershell -NoProfile -File scripts/empaquetar.ps1
```

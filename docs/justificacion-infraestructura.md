# Justificación de infraestructura AWS

## Alcance

Una EC2 ejecuta los tres microservicios, tres BFF, Spring Batch y sus dependencias mediante Docker Compose. La configuración busca un entorno temporal de demostración con recursos AWS limitados. No representa una infraestructura bancaria de producción ni disponibilidad entre servidores.

## Decisiones

| Elección | Justificación | Límite |
|---|---|---|
| EC2 t3.large con 2 vCPU y 8 GiB | La medición local con réplicas alcanzó 4,52 GiB entre contenedores. El despliegue EC2 completó las operaciones funcionales con 2,6 GiB disponibles después de la prueba. | No se midió carga sostenida. T3 utiliza créditos CPU y requiere seguimiento de su consumo. |
| Amazon Linux 2023 x86_64 | AMI pública mediante SSM y plataforma compatible con las imágenes y plugins Docker. | Cambiar la AMI puede reemplazar EC2. |
| Disco gp3 cifrado de 40 GiB | Almacena sistema, imágenes, volúmenes y resultados; la muestra EC2 registra 5,7 GiB usados, un 15 %. | Vigilar logs y crecimiento de datos; exportar respaldos antes de eliminar EC2. |
| Una VPC y subred pública | La ruta por Internet Gateway permite HTTPS y descarga de dependencias. | Solo el grupo de seguridad y la configuración de puertos determinan la exposición permitida. |
| Sin NAT Gateway | La instancia tiene IPv4 pública y ruta directa a Internet. | Una arquitectura con servidores privados necesitaría otra solución de salida. |
| Caddy y subdominio DuckDNS | HTTPS con certificado público, sin incorporar balanceador AWS ni comprar dominio. | La IP puede cambiar y exigir actualizar DNS. El tráfico interno aún no usa TLS. |
| SSH desde una IPv4 /32 e IMDSv2 | Restringe administración y exige tokens para consultar metadatos. | Actualizar la regla cuando cambie la IP administrativa. |
| PostgreSQL, Kafka y Artemis en contenedores | Mantiene las dependencias necesarias sin RDS, MSK ni otros servicios administrados. | Comparten recursos y dominio de fallo; requieren respaldos y operación manual. |
| Dos réplicas de clientes, cuentas y pagos | Permite demostrar varias instancias y registro en Eureka. | Son contenedores del mismo host; no Auto Scaling ni alta disponibilidad entre máquinas. |
| Terraform | Permite revisar el plan y reproducir ocho recursos y sus dependencias. | Mantener estado y planes privados. No crear roles IAM innecesarios para los servicios actuales. |
| IPv4 pública sin Elastic IP | Simplifica los recursos de un entorno temporal. | La IPv4 pública genera cargos; la dirección puede cambiar al detener e iniciar EC2. |

## Dimensionamiento y resultados

| Escenario | Resultado observado |
|---|---|
| Local con 15 contenedores | Máximo agregado de 3,72 GiB; verificación funcional correcta. |
| Local con 18 contenedores | Máximo agregado de 4,52 GiB; verificación correcta, sin OOM ni reinicios. |
| EC2 con réplicas después de operaciones | 4,7 GiB usados y 2,6 GiB disponibles del host; disco al 15 %. |
| Verificación AWS | 14 comprobaciones correctas de HTTPS, seguridad, pagos, BFF, Kafka y tres batch. |

Las mediciones locales incluyen una prueba funcional breve y periodos mayoritarios de reposo. El host local es ARM64 con 12 CPU; EC2 usa x86_64 con dos vCPU. No se extrapolan tiempos entre plataformas. La capacidad seleccionada respalda la demostración observada, no una carga bancaria real.

Los límites de memoria con réplicas suman 10,125 GiB, más que los 8 GiB del host. No son reservas y permiten sobreasignación, pero un crecimiento simultáneo puede agotar memoria. Cada JVM utiliza heap explícito y memoria directa limitada; también consume memoria nativa. Si aparecen OOM o presión sostenida, ajustar límites o ampliar la instancia.

La captura posterior a operaciones registra Kafka en 166,8 % de CPU, una muestra puntual. Una observación previa de doce muestras estuvo principalmente entre 1,38 % y 3,44 %, con un pico de 23,98 %. No acredita uso sostenido durante operaciones. Vigilar CPU, créditos T3, tiempos de respuesta y memoria antes de aumentar concurrencia.

Construir imágenes antes del arranque evita superponer Maven con los servicios. El empaquetado mantiene rutas y permisos Linux. La inicialización del volumen Kafka asigna su propiedad al UID 1000; el broker se ejecuta sin root.

## Verificación reproducible

En local, ejecutar `scripts/medir-recursos.ps1` con una instancia y luego con `-Iniciar -DosReplicas`. En EC2, ejecutar `python3 scripts/verificar-aws.py`, consultar `docker stats --no-stream`, `free -h`, espacio en disco y registros Eureka. Los resultados se generan en `docs/evidencia`, fuera del repositorio.

Dos registros UP por servicio prueban descubrimiento. Las operaciones pasan con réplicas, pero no se dispone de trazas que demuestren el reparto de cada llamada. No se escala el trabajador batch: el archivo anual compartido requiere coordinación adicional.

## Costos y persistencia

EC2, EBS, IPv4 pública, tráfico y posibles créditos CPU consumen presupuesto según uso. Detener EC2 conserva cargos de disco. El disco raíz se elimina al retirar la instancia; exportar datos, claves y evidencias antes de destruir el entorno. Los volúmenes Docker sobreviven a reinicios de contenedores, pero no sustituyen respaldos externos.

## Referencias

- [Instancias AWS T3](https://aws.amazon.com/ec2/instance-types/t3/).
- [Volúmenes gp3](https://docs.aws.amazon.com/ebs/latest/userguide/general-purpose.html).
- [Internet Gateway](https://docs.aws.amazon.com/vpc/latest/userguide/VPC_Internet_Gateway.html).
- [Parada y arranque de EC2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/Stop_Start.html).
- [HTTPS automático en Caddy](https://caddyserver.com/docs/automatic-https).

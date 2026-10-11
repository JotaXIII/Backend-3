# Despliegue del Banco XYZ en AWS

## Arquitectura de despliegue

Una instancia EC2 ejecuta Docker Compose, los tres servicios bancarios, los BFF, PostgreSQL, Kafka, Artemis y servicios de soporte. Caddy publica los canales mediante HTTPS. Los servicios de negocio pueden tener varias réplicas dentro del host; esto demuestra escalabilidad horizontal de contenedores, pero no alta disponibilidad entre máquinas. Un despliegue con varios hosts requiere ECS y almacenamiento administrado.

## Infraestructura minima

El metodo recomendado es [Terraform](infra/aws/terraform/README.md): una EC2, una VPC y subred publica, reglas de red y 40 GB gp3 cifrados. No crea NAT, balanceador, IP elastica ni servicios administrados. La instancia inicial t3.large debe ajustarse solo despues de medir las JVM y replicas durante la prueba completa.

La guia Terraform incluye credenciales, clave SSH, plan e instalacion desde terminal. Usar su salida `public_ip` y la clave privada `build/banco-aws` en los pasos siguientes. Configurar DNS para HTTPS; la IP puede cambiar al detener e iniciar la instancia. Esperar `sudo cloud-init status --wait` antes de iniciar Compose.


## Transferencia del código

El despliegue puede realizarse sin publicar cambios en GitHub:

```powershell
powershell -NoProfile -File scripts/empaquetar.ps1
scp -i RUTA_CLAVE.pem ../EFT_S9_JuanCarlos_Osega.zip ec2-user@IP_PUBLICA:/opt/banco/
ssh -i RUTA_CLAVE.pem ec2-user@IP_PUBLICA
```

En EC2:

```bash
cd /opt/banco
sudo dnf install -y unzip
unzip EFT_S9_JuanCarlos_Osega.zip
cd EFT_S9_JuanCarlos_Osega
cp .env.ec2.example .env.ec2
chmod 600 .env.ec2
```

Configurar todos los secretos con valores distintos y aleatorios. Establecer `PUBLIC_DOMAIN=SUBDOMINIO` y `OAUTH_ISSUER=https://SUBDOMINIO`. El despliegue verificado utiliza `banco-xyz-eft.duckdns.org`; cada entorno debe controlar su propio nombre DNS y apuntarlo a la IP pública de EC2. No transferir `.env.aws`, que contiene credenciales temporales del laboratorio. Conservar y respaldar el volumen de firmas; las claves no se incluyen en el paquete.

## Inicio y HTTPS

```bash
docker buildx version
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml config --quiet
COMPOSE_PARALLEL_LIMIT=1 docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml build
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml up -d --wait --wait-timeout 600
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml ps
curl --fail https://SUBDOMINIO/health
```

Caddy solicita y renueva el certificado automáticamente cuando el dominio resuelve a la instancia y el acceso ACME está disponible. No desactivar la validación del certificado. El archivo AWS retira la publicación de puertos HTTP de los BFF, solicitudes y autorización; solo Caddy es público. PostgreSQL, Eureka y Config permanecen ligados a localhost y sus puertos no están abiertos en el grupo de seguridad.

La red interna de contenedores utiliza HTTP y Kafka sin TLS. Para proteger también el tránsito interno se requieren certificados de servicio y TLS/SASL del broker; la configuración incluida se limita al perímetro HTTPS y la red privada del host.

## Escalabilidad

```bash
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml up -d --scale clientes=2 --scale cuentas=2 --scale pagos=2 --wait --wait-timeout 600
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml ps
```

Eureka asigna identificadores únicos y Spring Cloud LoadBalancer distribuye las llamadas de los BFF. Cuentas coordina débitos mediante PostgreSQL; pagos usa operaciones idempotentes y una outbox con bloqueo de filas. Los consumidores Kafka comparten un grupo y las particiones se asignan entre instancias.

No escalar batch en esta configuración: Artemis procesa solicitudes secuencialmente por consumidor y el archivo anual compartido requiere coordinación adicional entre procesos. Los resultados históricos ya están aislados por instancia, pero la ejecución distribuida de una misma solicitud debe coordinarse antes de agregar trabajadores.

## Verificación y evidencia

Ejecutar en EC2 `python3 scripts/verificar-aws.py`. Lee `.env.ec2`, valida el certificado HTTPS y comprueba 14 resultados de seguridad, pagos, contratos BFF, recepción Kafka y los tres procesos batch. Genera operaciones sobre datos de demostración. Guarda `docs/evidencia/verificacion-aws.json`, excluido de Git. El comando debe terminar con código cero y resultado `CORRECTO`.

Registrar `docker compose ps`, salud HTTPS, respuestas sin datos sensibles, réplicas registradas en Eureka y recepción de eventos. Conservar evidencia de instancia EC2 y grupo de seguridad. El estado de despliegue solo puede marcarse verificado tras estas comprobaciones.

## Respaldo y operación

```bash
mkdir -p build
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml exec -T postgres pg_dump -U banco banco_xyz > build/respaldo.sql
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml logs --tail 100 pagos cuentas
```

Copiar los respaldos a almacenamiento privado cifrado. Conservar claves, certificados, volúmenes PostgreSQL y Kafka. Probar restauración antes de actualizar imágenes. Para disponibilidad de producción, separar PostgreSQL en RDS, eventos en MSK y contenedores en ECS con balanceador y múltiples zonas; es una evolución posterior a la configuración de un host.

Referencias: https://docs.aws.amazon.com/linux/al2023/ug/ec2.html ; https://docs.docker.com/compose/install/linux/ ; https://caddyserver.com/docs/automatic-https

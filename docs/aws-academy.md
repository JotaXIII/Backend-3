# Despliegue con AWS Academy

## Credenciales y archivos

`.env.aws` contiene las credenciales temporales del laboratorio. No es el archivo de variables Docker y no debe transferirse a EC2. Se aceptan nombres en minúsculas del formato AWS credentials o sus equivalentes en mayúsculas. El token de sesión es obligatorio. Las credenciales caducan y deben renovarse con los valores del laboratorio activo. [AWS credenciales temporales](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_temp_use-resources.html).

Desde la raíz del proyecto:

```powershell
. ./scripts/cargar-aws.ps1
aws sts get-caller-identity --query Account --output text
```

El script carga las variables únicamente en el proceso actual y no muestra secretos. Terraform utiliza estas variables; no necesita incorporar claves al proveedor. Si el archivo incluye `AWS_REGION` o `AWS_DEFAULT_REGION`, se carga también `TF_VAR_region`. De lo contrario, definir la región permitida mediante `TF_VAR_region` o tfvars. [AWS variables de entorno](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-envvars.html).

La aplicación utiliza un archivo diferente, `.env.ec2`, creado a partir de `.env.ec2.example`, con dominio y secretos propios. En EC2:

```bash
cp .env.ec2.example .env.ec2
chmod 600 .env.ec2
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml config --quiet
docker compose --env-file .env.ec2 -f docker-compose.yaml -f docker-compose.aws.yaml up -d --build --wait
```

Reemplazar todos los valores antes del inicio. Mantener `--env-file .env.ec2` en los comandos posteriores de escalado. `.env.aws` y `.env.ec2` están excluidos de Git y del empaquetado basado en archivos no ignorados.

## Compatibilidad de infraestructura

Terraform usa EC2, EBS, red VPC y lectura de una AMI pública mediante SSM. No crea usuarios, roles, políticas ni perfiles IAM; los servicios bancarios actuales no necesitan un rol AWS. Tampoco añade RDS, MSK, ECS, balanceadores o NAT. Esta reducción evita dependencias adicionales, pero no garantiza que las políticas del laboratorio permitan cada operación.

AWS Academy establece restricciones de región, servicios y uso según el entorno. Consultar el apartado de restricciones del laboratorio activo; no asumir que todos los laboratorios tienen las mismas políticas. La guía Academy describe estas restricciones y la duración de las sesiones. [AWS Academy Learner Lab](https://d1.awsstatic.com/AWS%20Academy%20Learner%20Lab%20Educator%20Guide.pdf).

Antes de aplicar: comprobar región permitida, disponibilidad de t3.large, permisos para crear VPC/subred/grupo/clave/EC2 y lectura del parámetro SSM. Un plan exitoso no garantiza permisos de creación. Si SSM está denegado, seleccionar una AMI aprobada mediante un ajuste explícito de Terraform; si la creación de red está denegada, adaptar la configuración para reutilizar una red permitida. No se han confirmado esas restricciones en esta cuenta.

## Capacidad, presupuesto y DNS

La instancia t3.large completó la validación funcional en EC2 con dos réplicas de clientes, cuentas y pagos. Después de las operaciones, la muestra del host registró 4,7 GiB usados y 2,6 GiB disponibles. No se ha probado carga sostenida. Vigilar memoria, CPU y créditos. Conservar evidencia antes de finalizar el laboratorio: detener EC2 puede cambiar la IP pública y obligar a actualizar DNS.

AWS Academy no aporta automáticamente un dominio propio. El despliegue HTTPS actual requiere un dominio que apunte a la IP pública. Un nombre o certificado alternativo debe definirse y validarse; no basta con usar la IP como dominio configurado.

## Estado de revisión

Terraform se inicializó y validó, y creó los ocho recursos en us-east-1. El despliegue verificó HTTPS mediante DuckDNS y completó 14 comprobaciones funcionales, incluidos Kafka y los tres procesos batch. Las credenciales temporales deben renovarse cuando expiren. Estos resultados corresponden al entorno comprobado y no garantizan que otros laboratorios tengan los mismos permisos.

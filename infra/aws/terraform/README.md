# Infraestructura mínima

Para credenciales temporales y restricciones, consultar [AWS Academy](../../../docs/aws-academy.md). `.env.aws` se utiliza para aprovisionamiento; `.env.ec2` configura los contenedores.

Las decisiones, alternativas y límites están documentados en la [justificación de infraestructura](../../../docs/justificacion-infraestructura.md).

Una EC2 ejecuta todos los contenedores. Terraform crea la VPC, una subred pública, acceso a Internet, reglas de red y una clave pública SSH. El disco es gp3 cifrado de 40 GB. No se incluyen servicios administrados de bases de datos, brokers, balanceadores, NAT ni IP elástica.

El tamaño inicial es t3.large para las JVM y las réplicas. Medir memoria durante la validación antes de reducirlo. La escalabilidad consiste en réplicas de contenedores dentro del mismo host; no ofrece alta disponibilidad entre servidores.

## Ejecución

Instalar Terraform y configurar credenciales AWS mediante variables de entorno o AWS CLI. Se requieren permisos EC2 y lectura del parámetro público SSM de la AMI. No guardar credenciales en estos archivos.

Desde la raíz del proyecto:

```powershell
New-Item -ItemType Directory -Force build
ssh-keygen -t ed25519 -f ./build/banco-aws
$env:TF_VAR_ssh_public_key = Get-Content ./build/banco-aws.pub -Raw
$env:TF_VAR_admin_cidr = "IP_ADMIN/32"
terraform -chdir=infra/aws/terraform init
terraform -chdir=infra/aws/terraform fmt
terraform -chdir=infra/aws/terraform validate
terraform '-chdir=infra/aws/terraform' plan '-out=banco.tfplan'
terraform '-chdir=infra/aws/terraform' apply 'banco.tfplan'
terraform -chdir=infra/aws/terraform output
```

Usar `terraform.tfvars.example` como referencia para cambiar región o tipo de instancia. Conservar el archivo de bloqueo de proveedores generado por `init`. El estado, los planes y los archivos tfvars están excluidos de Git.

La instalación inicial prepara Docker y Compose. Esperar `sudo cloud-init status --wait`. Transferir el código y configurar secretos siguiendo `despliegue.md`, usando `build/banco-aws` como clave privada.

Configurar DNS del dominio con la salida `public_ip`. La IP puede cambiar al detener e iniciar EC2. Solo se permite HTTP/HTTPS público y SSH desde la IPv4 administrativa /32. La administración DNS depende del proveedor del dominio.

## Costos y retirada

EC2, EBS, IPv4 pública y tráfico pueden generar cargos. Guardar respaldos y evidencias antes de retirar el entorno. Revisar `terraform plan -destroy` y usar `terraform destroy` desde esta carpeta cuando corresponda. Se elimina también el disco con los datos. Detener EC2 conserva cargos de almacenamiento.

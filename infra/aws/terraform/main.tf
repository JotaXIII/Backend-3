terraform {
  required_version = ">= 1.6.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "banco-xyz" }
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "admin_cidr" {
  type        = string
  description = "IPv4 administrativa con prefijo /32."
  validation {
    condition     = can(cidrnetmask(var.admin_cidr)) && endswith(var.admin_cidr, "/32")
    error_message = "Usar una IPv4 administrativa con prefijo /32."
  }
}

variable "ssh_public_key" {
  type        = string
  description = "Clave publica OpenSSH; conservar la clave privada localmente."
}

variable "instance_type" {
  type    = string
  default = "t3.large"
}

variable "compose_version" {
  type    = string
  default = "v5.6.0"
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ssm_parameter" "ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_vpc" "banco" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
}

resource "aws_subnet" "publica" {
  vpc_id                  = aws_vpc.banco.id
  cidr_block              = "10.42.1.0/24"
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true
}

resource "aws_internet_gateway" "banco" {
  vpc_id = aws_vpc.banco.id
}

resource "aws_route_table" "publica" {
  vpc_id = aws_vpc.banco.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.banco.id
  }
}

resource "aws_route_table_association" "publica" {
  subnet_id      = aws_subnet.publica.id
  route_table_id = aws_route_table.publica.id
}

resource "aws_security_group" "banco" {
  name_prefix = "banco-xyz-"
  description = "HTTPS publico y SSH restringido"
  vpc_id      = aws_vpc.banco.id
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_key_pair" "banco" {
  key_name_prefix = "banco-xyz-"
  public_key      = var.ssh_public_key
}

resource "aws_instance" "banco" {
  ami                         = data.aws_ssm_parameter.ami.value
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.publica.id
  vpc_security_group_ids      = [aws_security_group.banco.id]
  key_name                    = aws_key_pair.banco.key_name
  associate_public_ip_address = true
  user_data                   = templatefile("${path.module}/inicio.sh.tftpl", { compose_version = var.compose_version })
  user_data_replace_on_change = true
  depends_on                  = [aws_route_table_association.publica]
  metadata_options {
    http_tokens = "required"
  }
  root_block_device {
    volume_size           = 40
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }
  tags = { Name = "banco-xyz" }
}

output "instance_id" {
  value = aws_instance.banco.id
}

output "public_ip" {
  value = aws_instance.banco.public_ip
}

output "ssh_command" {
  value = "ssh -i RUTA_CLAVE_PRIVADA ec2-user@${aws_instance.banco.public_ip}"
}

# 🔒 Secure Application Design
**Enterprise Architecture Workshop**

Aplicación web segura con login JWT desplegada en AWS EC2 usando Docker. Apache actúa como reverse proxy con TLS (Let's Encrypt/DuckDNS), Spring Boot maneja la API REST y PostgreSQL almacena contraseñas con BCrypt.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-green)
![Docker](https://img.shields.io/badge/Docker-Compose-blue)
![TLS](https://img.shields.io/badge/TLS-Let's%20Encrypt-brightgreen)
![AWS](https://img.shields.io/badge/AWS-EC2%20AL2023-yellow)

---

## 📐 Arquitectura

```
Browser (HTTPS :443)
       │
       ▼
┌──────────────────────────────────────────────────────┐
│  EC2 — Amazon Linux 2023                             │
│                                                      │
│  ┌──────────────┐  Docker bridge  ┌───────────────┐  │
│  │ login_apache │ ─── /api/* ───► │ login_service │  │
│  │ httpd:2.4    │    (HTTP:8080)  │ Spring Boot   │  │
│  │ Puerto 80/443│                 │ (expose only) │  │
│  │ TLS offload  │                 └───────┬───────┘  │
│  └──────────────┘                         │          │
│         │ sirve                           │ JDBC     │
│   frontend/index.html              ┌──────▼───────┐  │
│                                    │ login_postgres│  │
│                                    │ PostgreSQL 16 │  │
│                                    │ (expose only) │  │
│                                    └──────────────┘  │
└──────────────────────────────────────────────────────┘
```

**Flujo de una petición login:**
1. Browser → HTTPS :443 → Apache (TLS termination)
2. Apache → HTTP :8080 → Spring Boot (`login-service`)
3. Spring → verifica BCrypt hash en PostgreSQL
4. Spring → genera JWT → Apache → Browser

---

## 📁 Estructura del Proyecto

```
securelogin/
├── Dockerfile                           # Multi-stage build (Java 21)
├── docker-compose.yml                   # Dev local (H2 o PostgreSQL)
├── docker-compose.prod.yml              # Producción AWS (TLS activo)
├── pom.xml
├── .env.example                         # Plantilla — copia como .env
├── .gitignore
├── README.md
├── frontend/
│   └── index.html                       # Cliente async HTML+JS
└── src/main/
    ├── java/com/securelogin/
    │   ├── SecureloginApplication.java
    │   ├── config/
    │   │   ├── SecurityConfig.java      # Spring Security + JWT filter + CORS
    │   │   └── GlobalExceptionHandler.java
    │   ├── controller/
    │   │   ├── AuthController.java      # POST /api/auth/register  /login
    │   │   └── UserController.java      # GET  /api/user/me (protegido)
    │   ├── dto/
    │   │   ├── request/
    │   │   │   ├── LoginRequest.java
    │   │   │   └── RegisterRequest.java
    │   │   └── response/
    │   │       ├── AuthResponse.java
    │   │       └── MessageResponse.java
    │   ├── model/User.java              # username, email, password (BCrypt), fullName
    │   ├── repository/UserRepository.java
    │   ├── security/
    │   │   ├── JwtUtils.java            # Genera y valida JWT (HMAC-SHA256)
    │   │   ├── JwtAuthFilter.java       # Filtro por request
    │   │   └── UserDetailsServiceImpl.java
    │   └── service/AuthService.java     # BCrypt + lastLogin
    └── resources/
        ├── application.properties
        └── apache/
            ├── httpd.local.conf         # Dev — sin SSL, proxy a Spring
            └── httpd.prod.conf          # Prod — TLS + security headers
```

---

## 🔑 Características de Seguridad

### BCrypt (cost factor 12)
Las contraseñas **nunca** se guardan en texto plano:
```java
// AuthService.java
password(passwordEncoder.encode(req.getPassword()))  // BCrypt hash
// BCryptPasswordEncoder.matches() lo verifica en login
```

### JWT Stateless
- Firmado con HMAC-SHA256
- Expira en 24 horas (configurable)
- Sin estado en servidor → escalable horizontalmente

### TLS Offloading
Apache termina HTTPS y reenvía a Spring por HTTP interno — la gestión de certificados queda fuera de la capa de aplicación.

### Secretos en variables de entorno
Ningún secreto está en el código fuente o en imágenes Docker. Todo viene del `.env`.

---

## 🚀 Despliegue Local (desarrollo)

### Opción A — Sin Docker (H2 en memoria)
```bash
git clone https://github.com/TU_USUARIO/secure-app.git
cd secure-app
mvn spring-boot:run
# API disponible en http://localhost:8080
# H2 Console: http://localhost:8080/h2-console
```

### Opción B — Con Docker Compose
```bash
# 1. Crear el archivo .env
cp .env.example .env
# Editar .env con tus valores

# 2. Levantar todos los servicios
docker compose up --build -d

# App disponible en http://localhost
# Apache → puerto 80 → Spring (8080 interno) → PostgreSQL (5432 interno)
```

---

## ☁️ Despliegue en AWS EC2

### Paso 1 — Crear instancia EC2

1. En la consola AWS → **EC2 → Launch Instance**
2. Nombre: `secure-login-server`
3. AMI: **Amazon Linux 2023**
4. Tipo: `t2.micro` (Free Tier)
5. Par de claves: crear o seleccionar uno existente
6. **Security Group** — Inbound rules:

| Puerto | Protocolo | Fuente    | Descripción              |
|--------|-----------|-----------|--------------------------|
| 22     | TCP       | Tu IP     | SSH admin                |
| 80     | TCP       | 0.0.0.0/0 | HTTP (redirige a HTTPS)  |
| 443    | TCP       | 0.0.0.0/0 | HTTPS (aplicación)       |

7. **Launch instance** → anotar la IP pública

### Paso 2 — Configurar DuckDNS (dominio gratuito)

1. Ir a [https://www.duckdns.org](https://www.duckdns.org) → Login con GitHub o Google
2. Crear un subdominio: `tu-nombre.duckdns.org`
3. Ingresar la **IP pública** de tu instancia EC2
4. Click en **Update IP**
5. Verificar: `ping tu-nombre.duckdns.org` → debe responder con tu IP EC2

### Paso 3 — Conectar a la instancia y preparar el entorno

```bash
# Conectar por SSH
ssh -i tu-clave.pem ec2-user@<IP_PUBLICA_EC2>

# Actualizar el sistema
sudo dnf update -y

# Instalar Docker
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
newgrp docker   # aplica el grupo sin reconectar

# Instalar Docker Compose plugin
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version   # verificar instalación

# Instalar Git
sudo dnf install -y git

# Instalar Certbot (Let's Encrypt)
sudo dnf install -y python3 augeas-libs
sudo pip3 install certbot
```

### Paso 4 — Obtener certificado TLS con Let's Encrypt

```bash
# Certbot necesita el puerto 80 libre durante el challenge.
# Asegúrate de que ningún proceso lo esté usando.
sudo certbot certonly --standalone \
  -d tu-nombre.duckdns.org \
  --email tu@email.com \
  --agree-tos \
  --non-interactive

# Verificar que el certificado existe:
sudo ls /etc/letsencrypt/live/tu-nombre.duckdns.org/
# fullchain.pem  privkey.pem  chain.pem  cert.pem
```

### Paso 5 — Clonar el repositorio y configurar

```bash
cd ~
git clone https://github.com/TU_USUARIO/secure-app.git
cd secure-app

# Crear el archivo .env con tus valores reales
cp .env.example .env
nano .env
```

Contenido del `.env` en producción:
```bash
DB_NAME=logindb
DB_USER=loginuser
DB_PASSWORD=una_password_muy_segura_aqui

# Genera un secret JWT seguro:
# openssl rand -hex 64
JWT_SECRET=aqui_pega_el_resultado_de_openssl_rand_hex_64

JWT_EXPIRATION_MS=86400000
CORS_ALLOWED_ORIGINS=https://tu-nombre.duckdns.org
```

Editar `httpd.prod.conf` para reemplazar el dominio:
```bash
sed -i 's/yourdomain.duckdns.org/tu-nombre.duckdns.org/g' \
  src/main/resources/apache/httpd.prod.conf
```

### Paso 6 — Levantar en producción

```bash
# Dar permisos al directorio de certificados
sudo chmod 755 /etc/letsencrypt/live
sudo chmod 755 /etc/letsencrypt/archive

# Levantar con docker-compose.prod.yml
docker compose -f docker-compose.prod.yml up --build -d

# Verificar que todos los contenedores están corriendo
docker compose -f docker-compose.prod.yml ps
```

### Paso 7 — Verificar el despliegue

```bash
# Verificar certificado TLS
curl -I https://tu-nombre.duckdns.org

# Probar login via API
curl -X POST https://tu-nombre.duckdns.org/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","email":"test@test.com","password":"Test1234!","fullName":"Test User"}'

curl -X POST https://tu-nombre.duckdns.org/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"Test1234!"}'

# Con el token obtenido:
TOKEN="eyJhbGci..."
curl https://tu-nombre.duckdns.org/api/user/me \
  -H "Authorization: Bearer $TOKEN"
```

---

## 📡 API Endpoints

| Método | Endpoint               | Auth     | Descripción                           |
|--------|------------------------|----------|---------------------------------------|
| POST   | `/api/auth/register`   | No       | Registrar usuario (BCrypt hash)       |
| POST   | `/api/auth/login`      | No       | Login → JWT token                     |
| GET    | `/api/user/me`         | JWT      | Verificar sesión activa               |

### Ejemplos de petición/respuesta

**POST /api/auth/register**
```json
// Request
{ "username": "juan", "email": "juan@example.com",
  "password": "pass1234", "fullName": "Juan García" }

// 200 OK
{ "message": "User registered successfully." }

// 400 Bad Request (validación)
{ "password": "size must be between 8 and 72" }
```

**POST /api/auth/login**
```json
// Request
{ "username": "juan", "password": "pass1234" }

// 200 OK
{ "token": "eyJhbGci...", "tokenType": "Bearer", "expiresIn": 86400000 }

// 401 Unauthorized
{ "message": "Invalid username or password." }
```

**GET /api/user/me**
```
Authorization: Bearer eyJhbGci...

200 OK  → token válido
403     → token ausente, expirado o con firma inválida
```

---

## 🔄 Renovación automática del certificado

```bash
# Prueba de renovación (sin aplicar cambios)
sudo certbot renew --dry-run

# Agregar cron job para renovación automática (cada 12 horas)
echo "0 0,12 * * * root certbot renew --quiet --deploy-hook 'docker compose -f /home/ec2-user/secure-app/docker-compose.prod.yml restart apache'" \
  | sudo tee /etc/cron.d/certbot-renew
```

---

## 🐛 Solución de Problemas

**Ver logs de los contenedores:**
```bash
docker compose -f docker-compose.prod.yml logs -f apache
docker compose -f docker-compose.prod.yml logs -f login-service
docker compose -f docker-compose.prod.yml logs -f postgres
```

**Reiniciar un servicio:**
```bash
docker compose -f docker-compose.prod.yml restart apache
```

**Error "port 80 already in use" al ejecutar Certbot:**
```bash
sudo lsof -i :80   # identificar el proceso
# Si es Apache del host:
sudo systemctl stop httpd
# Volver a ejecutar certbot...
```

**Error de CORS en el navegador:**
- Verificar que `CORS_ALLOWED_ORIGINS` en `.env` tiene la URL exacta con `https://`

---

## 📹 Guía para el Video de Demostración

1. Mostrar el navegador en `https://tu-nombre.duckdns.org` → candado TLS visible
2. Abrir DevTools (F12) → pestaña Network
3. Registrar un nuevo usuario → mostrar petición POST y respuesta 200
4. Iniciar sesión → mostrar el JWT en la respuesta
5. Copiar el token → pegarlo en [jwt.io](https://jwt.io) → mostrar el payload decodificado
6. Hacer clic en **GET /api/user/me** → mostrar header `Authorization: Bearer ...`
7. Cerrar sesión y mostrar que el endpoint falla sin token (403)
8. Mostrar el certificado TLS en el navegador (información del sitio)

---

## 👨‍💻 Créditos

Basado en el tutorial **TDSE_Secure_Login_AWS** de Juan Carlos Leal Cruz.
Adaptado para el *Enterprise Architecture Workshop: Secure Application Design*.

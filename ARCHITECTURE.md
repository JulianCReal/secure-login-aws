# Documento de Arquitectura
## Enterprise Architecture Workshop: Secure Application Design

**Autor:** Julian David Castiblanco Real
**Institución:** Escuela Colombiana de Ingeniería Julio Garavito  
**Fecha:** Marzo 2026  
**URL de la aplicación:** https://apacheecijcr.duckdns.org

---

## 1. Resumen Ejecutivo

Este documento describe el diseño e implementación de una aplicación web segura desplegada en AWS EC2. La aplicación implementa autenticación de usuarios con JWT, almacenamiento seguro de contraseñas con BCrypt, comunicación cifrada mediante TLS (Let's Encrypt) y una arquitectura de tres capas ejecutada en contenedores Docker sobre una sola instancia EC2.

---

## 2. Arquitectura General

La aplicación sigue una arquitectura de tres capas distribuidas en contenedores Docker que se comunican a través de una red bridge interna. Solo Apache está expuesto a internet; Spring Boot y PostgreSQL son completamente internos.

```
┌─────────────────────────────────────────────────────────────────────┐
│                            INTERNET                                  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTPS :443 / HTTP :80
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  EC2 — Amazon Linux 2023  (Apache-Client · 54.221.28.50)            │
│  Dominio: apacheecijcr.duckdns.org                                  │
│                                                                     │
│  ┌─────────────────────┐   Docker bridge    ┌──────────────────┐   │
│  │   login_apache      │ ── /api/* HTTP ──► │  login_service   │   │
│  │   httpd:2.4-alpine  │      :8080          │  Spring Boot 3.2 │   │
│  │   Puertos: 80, 443  │                     │  Java 21         │   │
│  │   TLS Offloading    │                     │  expose: 8080    │   │
│  └─────────────────────┘                     └────────┬─────────┘   │
│          │                                            │             │
│          │ Sirve                                      │ JDBC        │
│   frontend/index.html                       ┌─────────▼──────────┐  │
│   (HTML + JS Async)                         │   login_postgres   │  │
│                                             │   PostgreSQL 16    │  │
│                                             │   expose: 5432     │  │
│                                             │   Volume: persistente│ │
│                                             └────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Componentes del Sistema

### 3.1 Capa de Presentación — Apache HTTP Server

| Atributo | Valor |
|---|---|
| Imagen Docker | `httpd:2.4-alpine` |
| Puertos expuestos | 80 (HTTP), 443 (HTTPS) |
| Certificado TLS | Let's Encrypt vía Certbot |
| Dominio | apacheecijcr.duckdns.org |
| Función principal | Reverse proxy + TLS offloading + servir frontend |

Apache es el único punto de entrada público. Sus responsabilidades son:

- Servir el cliente estático `frontend/index.html` directamente desde su `DocumentRoot`.
- Redirigir todo el tráfico HTTP (puerto 80) a HTTPS (puerto 443) con código 301.
- Terminar el TLS — descifra el tráfico HTTPS y reenvía las peticiones a Spring Boot por HTTP plano en la red Docker interna. Esto se denomina **TLS offloading**.
- Reenviar todas las peticiones bajo `/api/*` al contenedor `login-service` en el puerto 8080 mediante `mod_proxy_http`.
- Aplicar cabeceras de seguridad HTTP en cada respuesta.

Configuración clave (`httpd.prod.conf`):
```apache
# Redirect HTTP → HTTPS
<VirtualHost *:80>
    RewriteEngine On
    RewriteRule ^ https://%{SERVER_NAME}%{REQUEST_URI} [R=301,L]
</VirtualHost>

# TLS Offloading + Reverse Proxy
<VirtualHost *:443>
    SSLEngine on
    SSLCertificateFile    /etc/letsencrypt/live/apacheecijcr.duckdns.org/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/apacheecijcr.duckdns.org/privkey.pem

    <Location /api>
        ProxyPass        http://login-service:8080/api
        ProxyPassReverse http://login-service:8080/api
    </Location>
</VirtualHost>
```

### 3.2 Capa de Aplicación — Spring Boot

| Atributo | Valor |
|---|---|
| Framework | Spring Boot 3.2.3 |
| Lenguaje | Java 21 (Amazon Corretto) |
| Puerto interno | 8080 (solo accesible desde la red Docker) |
| Base de datos | PostgreSQL 16 (producción), H2 (desarrollo local) |
| Autenticación | JWT stateless con HMAC-SHA256 |
| Hash de contraseñas | BCrypt con factor de costo 12 |

Spring Boot es una API REST stateless. No maneja TLS directamente — el certificado lo gestiona Apache. Sus responsabilidades son:

- Exponer los endpoints REST bajo `/api/**`.
- Registrar usuarios con contraseñas hasheadas con BCrypt.
- Autenticar usuarios y emitir tokens JWT firmados.
- Validar el token JWT en cada petición protegida mediante `JwtAuthFilter`.
- Gestionar la persistencia de usuarios en PostgreSQL.

### 3.3 Capa de Datos — PostgreSQL

| Atributo | Valor |
|---|---|
| Imagen Docker | `postgres:16-alpine` |
| Puerto | 5432 (solo accesible internamente) |
| Persistencia | Volumen Docker nombrado `postgres_data` |
| Tabla principal | `users` |

La tabla `users` almacena:

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | BIGSERIAL | Clave primaria |
| `username` | VARCHAR(50) | Único, mínimo 3 caracteres |
| `email` | VARCHAR(100) | Único, formato email válido |
| `password` | VARCHAR(255) | Hash BCrypt — nunca texto plano |
| `full_name` | VARCHAR(100) | Nombre completo |
| `created_at` | TIMESTAMP | Generado automáticamente con `@PrePersist` |
| `last_login` | TIMESTAMP | Actualizado en cada login exitoso |
| `enabled` | BOOLEAN | Activo por defecto |

### 3.4 Cliente Asíncrono — HTML + JavaScript

El frontend es un archivo HTML estático (`frontend/index.html`) servido directamente por Apache. Implementa comunicación asíncrona con el backend usando `fetch()` con `async/await`.

Características clave:
- Todas las peticiones van a `/api/*` en el mismo origen — Apache las intercepta y las reenvía internamente a Spring Boot.
- El token JWT se almacena en `sessionStorage` (se borra al cerrar el tab).
- Cada petición protegida envía el token en el header `Authorization: Bearer <token>`.
- No hay recarga de página — la navegación entre login, registro y dashboard se gestiona con JavaScript puro.

```javascript
// Ejemplo de petición asíncrona al login
async function doLogin() {
    const { data } = await apiFetch('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password })
    });
    token = data.token;
    sessionStorage.setItem('token', token);
}

// Header de autorización en peticiones protegidas
const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`
};
```

---

## 4. Flujo de Autenticación

```
Usuario          Apache (TLS)         Spring Boot          PostgreSQL
   │                  │                    │                    │
   │ POST /api/auth/login (HTTPS)          │                    │
   │─────────────────►│                    │                    │
   │                  │ HTTP interno        │                    │
   │                  │────────────────────►                    │
   │                  │   {username, password}                  │
   │                  │                    │                    │
   │                  │                    │ SELECT * FROM users
   │                  │                    │────────────────────►
   │                  │                    │◄────────────────────
   │                  │                    │   {hash BCrypt}    │
   │                  │                    │                    │
   │                  │              BCrypt.matches()           │
   │                  │              (password, hash)           │
   │                  │                    │                    │
   │                  │                    │ UPDATE last_login  │
   │                  │                    │────────────────────►
   │                  │                    │                    │
   │                  │   JWT firmado       │                    │
   │                  │◄────────────────────                    │
   │ 200 OK + JWT     │                    │                    │
   │◄─────────────────│                    │                    │
   │                  │                    │                    │
   │ [Guarda JWT en sessionStorage]        │                    │
   │                  │                    │                    │
   │ GET /api/user/me │                    │                    │
   │ Authorization: Bearer <JWT>           │                    │
   │─────────────────►│                    │                    │
   │                  │────────────────────►                    │
   │                  │              JwtAuthFilter              │
   │                  │              validateToken()            │
   │                  │              extractUsername()          │
   │                  │                    │                    │
   │ 200 OK           │                    │                    │
   │◄─────────────────│                    │                    │
```

---

## 5. Implementación de Seguridad

### 5.1 TLS con Let's Encrypt

Los certificados se obtienen con Certbot usando el método `--standalone` antes de levantar los contenedores Docker. Los archivos PEM se montan en el contenedor Apache como volúmenes de solo lectura:

```yaml
# docker-compose.prod.yml
volumes:
  - /etc/letsencrypt:/etc/letsencrypt:ro
  - /var/lib/letsencrypt:/var/lib/letsencrypt:ro
```

Configuración TLS en Apache — solo TLS 1.2 y 1.3:
```apache
SSLProtocol     all -SSLv3 -TLSv1 -TLSv1.1
SSLCipherSuite  ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384:...
SSLHonorCipherOrder off
SSLSessionTickets   off
```

### 5.2 BCrypt para Contraseñas

BCrypt con factor de costo 12 (2¹² = 4096 iteraciones). La contraseña nunca viaja en texto plano más allá del endpoint de registro:

```java
// AuthService.java — registro
User user = User.builder()
    .password(passwordEncoder.encode(req.getPassword()))  // BCrypt hash
    .build();

// Spring Security — verificación en login (automático)
// BCryptPasswordEncoder.matches(rawPassword, storedHash)
```

El factor de costo 12 hace que cada verificación tome ~250ms, lo que hace inviable un ataque de fuerza bruta a gran escala. Además, BCrypt incluye salt automáticamente, previniendo ataques de tabla arco iris.

### 5.3 JWT Stateless

Tokens firmados con HMAC-SHA256. El servidor no almacena sesiones — toda la información necesaria está en el token:

```
Header:  { "alg": "HS256", "typ": "JWT" }
Payload: { "sub": "david", "iat": 1234567890, "exp": 1234654290 }
Firma:   HMAC-SHA256(base64(header) + "." + base64(payload), secret)
```

El `JwtAuthFilter` intercepta cada petición, extrae el token del header `Authorization: Bearer`, valida la firma y la expiración, y establece el contexto de seguridad de Spring.

### 5.4 Cabeceras de Seguridad HTTP

Apache agrega estas cabeceras en cada respuesta:

| Cabecera | Valor | Protección |
|---|---|---|
| `Strict-Transport-Security` | `max-age=63072000; includeSubDomains` | Fuerza HTTPS por 2 años |
| `X-Frame-Options` | `DENY` | Previene clickjacking |
| `X-Content-Type-Options` | `nosniff` | Previene MIME sniffing |
| `X-XSS-Protection` | `1; mode=block` | Filtro XSS del browser |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Limita datos en Referer |
| `Permissions-Policy` | `geolocation=(), microphone=(), camera=()` | Deniega APIs sensibles |

### 5.5 Gestión de Secretos

Ningún secreto está en el código fuente ni en las imágenes Docker. Todo se inyecta en tiempo de ejecución desde el archivo `.env`:

```bash
DB_PASSWORD=<password_segura>
JWT_SECRET=<64_bytes_hex_aleatorio>  # openssl rand -hex 64
```

El archivo `.env` está en `.gitignore` y nunca se sube al repositorio.

### 5.6 Aislamiento de Red

Solo Apache está expuesto a internet (puertos 80 y 443). Spring Boot y PostgreSQL usan `expose` en lugar de `ports`, lo que los hace accesibles únicamente dentro de la red Docker bridge interna:

```yaml
login-service:
  expose:
    - "8080"    # Solo accesible internamente

postgres:
  expose:
    - "5432"    # Solo accesible internamente
```

---

## 6. Estrategia de Despliegue en AWS

### 6.1 Infraestructura

| Recurso | Valor |
|---|---|
| Proveedor | Amazon Web Services |
| Servicio | EC2 |
| Instancia | t3.micro |
| AMI | Amazon Linux 2023 |
| Región | us-east-1 |
| IP pública | 54.221.28.50 |
| Dominio | apacheecijcr.duckdns.org (DuckDNS) |

### 6.2 Security Group

| Puerto | Protocolo | Fuente | Propósito |
|---|---|---|---|
| 22 | TCP | IP del administrador | SSH |
| 80 | TCP | 0.0.0.0/0 | HTTP → redirect a HTTPS |
| 443 | TCP | 0.0.0.0/0 | HTTPS (aplicación) |

### 6.3 Proceso de Despliegue

```
1. Instancia EC2 → instalar Docker + Docker Compose
2. Subir código fuente a la instancia (scp)
3. Configurar .env con secretos reales
4. Certbot --standalone → obtener certificado TLS
5. Actualizar dominio en httpd.prod.conf
6. docker compose -f docker-compose.prod.yml up --build -d
```

### 6.4 Dockerfile Multi-stage

El Dockerfile usa dos stages para minimizar el tamaño de la imagen de producción:

- **Stage 1 (builder):** `maven:3.9.6-eclipse-temurin-21` — compila el código y genera el JAR.
- **Stage 2 (runtime):** `eclipse-temurin:21-jre-jammy` — solo el JRE mínimo, sin Maven ni código fuente.

La aplicación corre como usuario no-root (`appuser`) dentro del contenedor.

---

## 7. API REST

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| POST | `/api/auth/register` | No | Registra usuario con BCrypt |
| POST | `/api/auth/login` | No | Autenticación → JWT |
| GET | `/api/user/me` | JWT | Verifica sesión activa |

---

## 8. Tecnologías Utilizadas

| Categoría | Tecnología | Versión |
|---|---|---|
| Cloud | Amazon Web Services EC2 | — |
| SO | Amazon Linux | 2023 |
| Contenedores | Docker + Docker Compose | 25.x / v5.x |
| Web Server | Apache HTTPD | 2.4 (Alpine) |
| Backend | Spring Boot | 3.2.3 |
| Lenguaje | Java (Eclipse Temurin) | 21 |
| Seguridad | Spring Security | 6.x |
| Autenticación | JWT (JJWT) | 0.12.3 |
| Hash contraseñas | BCrypt | cost=12 |
| Base de datos | PostgreSQL | 16 (Alpine) |
| Certificados TLS | Let's Encrypt (Certbot) | 4.2.0 |
| DNS gratuito | DuckDNS | — |
| Frontend | HTML5 + CSS3 + JavaScript ES2022 | — |

---

## 9. Consideraciones para Producción Real

- Reemplazar DuckDNS por un dominio propio registrado.
- Almacenar `JWT_SECRET` y `DB_PASSWORD` en AWS Secrets Manager.
- Agregar un Application Load Balancer con WAF para protección adicional.
- Habilitar Amazon CloudWatch para monitoreo y alertas.
- Configurar renovación automática del certificado con cron:
  ```bash
  echo "0 0,12 * * * root certbot renew --quiet" | sudo tee /etc/cron.d/certbot
  ```
- Implementar rate limiting en los endpoints de autenticación.
- Migrar de `spring.jpa.hibernate.ddl-auto=update` a migraciones con Flyway o Liquibase.

---

*Documento elaborado para el Enterprise Architecture Workshop — Secure Application Design*  
*Escuela Colombiana de Ingeniería Julio Garavito — 2026*

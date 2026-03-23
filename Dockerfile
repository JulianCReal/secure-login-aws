# =============================================================
#  Multi-stage Dockerfile — secure-login-app
#  Build context: raíz del proyecto (mismo folder que pom.xml)
#
#  Stage 1: Build con Maven + Java 21
#  Stage 2: Runtime mínimo, usuario no-root
# =============================================================

# ── Stage 1: Build ────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copia pom.xml primero — la capa de dependencias Maven queda
# en caché independientemente de cambios en el código fuente,
# por lo que rebuilds tras ediciones son mucho más rápidos.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copia el código fuente
COPY src ./src

# Empaqueta — genera target/secure-login-app.jar (finalName en pom.xml)
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# Usuario no-root — nunca ejecutar como root dentro de un contenedor
RUN groupadd --system appgroup && \
    useradd  --system --gid appgroup appuser

WORKDIR /app

COPY --from=builder /app/target/secure-login-app.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# Toda la configuración sensible se inyecta en tiempo de ejecución
# mediante variables de entorno. No hay secretos en la imagen.
ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]

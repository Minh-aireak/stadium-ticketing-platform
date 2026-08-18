# ---- Stage 1: Build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY mvnw mvnw
COPY .mvn .mvn
RUN chmod +x mvnw

COPY pom.xml pom.xml
COPY common/pom.xml common/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY booking-service/pom.xml booking-service/pom.xml
COPY identity-service/pom.xml identity-service/pom.xml
COPY match-catalog-service/pom.xml match-catalog-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY ticket-inventory-service/pom.xml ticket-inventory-service/pom.xml

RUN ./mvnw dependency:go-offline -B -q

COPY . .
RUN ./mvnw package -pl notification-service -am -DskipTests -B -q

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /workspace/notification-service/target/*.jar app.jar

# curl is here only so the container can answer its own docker-compose healthcheck against
# /actuator/health: eclipse-temurin's JRE image ships neither curl nor wget, and without a
# healthcheck the only condition other services can depend on is service_started, which says
# nothing about whether this app can serve a request yet.
RUN apt-get update  && apt-get install -y --no-install-recommends curl  && rm -rf /var/lib/apt/lists/*

# UID/GID pinned: all 7 services share the app_logs volume (docker-compose.yaml), and a named
# volume inherits ownership from whichever image mounts it first — so every image has to agree
# on appuser's numeric id, or the other six lose write access to their own log file.
RUN addgroup --system --gid 10001 appgroup && adduser --system --uid 10001 --ingroup appgroup appuser

# Destination of logging.file.name (ECS JSON, tailed by Logstash). The directory has to exist in
# the image AND be owned by appuser: Docker seeds a fresh named volume from the image path, and
# a path missing from the image is seeded root-owned — unwritable for a non-root app.
RUN mkdir -p /var/log/app && chown appuser:appgroup /var/log/app

USER appuser

EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]

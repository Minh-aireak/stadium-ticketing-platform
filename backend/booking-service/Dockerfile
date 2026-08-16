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
RUN ./mvnw package -pl booking-service -am -DskipTests -B -q

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /workspace/booking-service/target/*.jar app.jar

# curl is here only so the container can answer its own docker-compose healthcheck against
# /actuator/health: eclipse-temurin's JRE image ships neither curl nor wget, and without a
# healthcheck the only condition other services can depend on is service_started, which says
# nothing about whether this app can serve a request yet.
RUN apt-get update  && apt-get install -y --no-install-recommends curl  && rm -rf /var/lib/apt/lists/*

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
USER appuser

EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]

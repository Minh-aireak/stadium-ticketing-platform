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

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
USER appuser

EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]

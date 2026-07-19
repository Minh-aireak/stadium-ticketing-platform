# VERSIONS.md — Stadium Ticketing Platform
# Ground-truth dependency versions verified by actual Maven build on 2026-07-19

## Stack (verified by running `mvnw install -DskipTests=true` — all 8 modules BUILD SUCCESS)

| Component                    | Artifact                                              | Version  | Source                           |
|------------------------------|-------------------------------------------------------|----------|----------------------------------|
| JDK                          | Eclipse Temurin / GraalVM                             | **25**   | java.version in root pom         |
| Spring Boot                  | `org.springframework.boot:spring-boot-starter-parent` | **4.1.0**| repo1.maven.org (2026-06-09)     |
| Spring Framework             | (managed by SB BOM)                                   | 7.x      | SB 4.1.0 BOM                     |
| Spring Kafka                 | `org.springframework.kafka:spring-kafka`              | **4.1.0**| SB 4.1.0 BOM                     |
| Resilience4j                 | `io.github.resilience4j:resilience4j-spring-boot4`    | **2.4.0**| repo1.maven.org (2026-03-14)     |
| Redisson                     | `org.redisson:redisson-spring-boot-starter`           | **4.6.1**| repo1.maven.org (2026-06-18); SB 4.1 support from 4.6.0 |
| Redisson Spring Data module  | `org.redisson:redisson-spring-data-41`                | **4.6.1**| auto-selected by Redisson starter|
| Lombok                       | `org.projectlombok:lombok`                            | **1.18.38** | root pom                      |
| MapStruct                    | `org.mapstruct:mapstruct`                             | **1.6.3**| root pom                         |
| JJWT API                     | `io.jsonwebtoken:jjwt-api`                            | **0.12.6**| root pom                        |
| Testcontainers BOM           | `org.testcontainers:testcontainers-bom`               | **1.21.0**| root pom                        |
| PostgreSQL driver            | `org.postgresql:postgresql`                           | (SB BOM) | runtime scope                    |
| Elasticsearch Java client    | `co.elastic.clients:elasticsearch-java`               | **8.18.3**| root pom (match-catalog-service) |
| Jacoco                       | `org.jacoco:jacoco-maven-plugin`                      | **0.8.13**| root pom                        |

## Notes

### Kafka deserialization warning
`org.springframework.kafka.support.serializer.JsonDeserializer` is deprecated in spring-kafka 4.1.0
and marked for removal. Replace with `org.springframework.kafka.support.serializer.JsonDeserializer`
from the new API or use application.yml property-based configuration when the replacement API is stable.
This is a **WARNING only** — build and runtime are not affected.

### api-gateway
Spring Cloud Gateway (spring-cloud-starter-gateway) was removed from root BOM when Spring Cloud 2024.x
was dropped in favor of Boot 4.1.x. The api-gateway currently uses `spring-boot-starter-webflux` for
manual reactive routing. Re-introduce Spring Cloud Gateway when a Boot 4.1.x compatible release ships.

### Build command (verified 2026-07-19)
```
.\mvnw.cmd install -DskipTests=true   # all 8 modules BUILD SUCCESS (59.954 s + 5.952 s)
```

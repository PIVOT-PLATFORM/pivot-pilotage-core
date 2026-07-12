# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# sharing=locked : le dev compose (pivot-core/compose.yml) build ce module EN PARALLÈLE des autres
# services Java, qui partagent ce même cache BuildKit /root/.m2 (le Maven Wrapper y télécharge la
# distribution Maven). Sans verrou, plusieurs `mvnw` concurrents corrompent le zip partagé
# ("zip END header not found"). Sérialise l'accès. Sans effet en CI (cache non partagé entre runners).
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw dependency:go-offline -B -q
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw package -DskipTests -B -q

# Runtime Alpine : surface OS minimale, CVE réduits. Builder jeté à la fin.
FROM eclipse-temurin:25-jre-alpine
RUN apk upgrade --no-cache
WORKDIR /app
RUN addgroup -S pivot && adduser -S -G pivot pivot
COPY --from=builder /workspace/target/*.jar app.jar
USER pivot
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

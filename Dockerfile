# ---- Build stage: compile and package (tests run separately: they need Docker themselves) ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Runtime stage: small JRE image, non-root user ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S portal && adduser -S portal -G portal \
    && mkdir -p /data/imports && chown -R portal:portal /data
USER portal
WORKDIR /app
COPY --from=build /workspace/target/partnership-portal-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

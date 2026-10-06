# Build the React UI and package it inside Spring Boot for a single public origin.
FROM node:24-bookworm-slim AS ui
WORKDIR /ui
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run lint && node --test tests/currency.test.mjs && npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
COPY --from=ui /ui/dist ./src/main/resources/static
# Database integration tests run separately with MySQL; a build has no database.
RUN mvn -B -Dmaven.test.skip=true package
COPY deployment/certs/aiven-ca.pem ./aiven-ca.pem
# This store contains a public CA certificate only; its integrity password is not a credential.
RUN keytool -importcert -noprompt -alias aiven-project-ca -file aiven-ca.pem -keystore db-truststore.p12 -storetype PKCS12 -storepass changeit

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --gid 10001 portfolio && useradd --uid 10001 --gid portfolio --no-create-home portfolio
COPY --from=build --chown=portfolio:portfolio /build/target/portfolio-0.0.1-SNAPSHOT.jar ./app.jar
COPY --from=build --chown=portfolio:portfolio /build/db-truststore.p12 ./db-truststore.p12
USER portfolio
ENV SERVER_ADDRESS=0.0.0.0
ENV SPRING_PROFILES_ACTIVE=container
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=160m"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

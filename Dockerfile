FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY legacy-desktop/pom.xml legacy-desktop/pom.xml
COPY game-engine/pom.xml game-engine/pom.xml
COPY backend/pom.xml backend/pom.xml
COPY game-engine/src game-engine/src
COPY backend/src backend/src

RUN mvn --batch-mode --no-transfer-progress -pl backend -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system morris && useradd --system --gid morris --home-dir /app morris
COPY --from=build --chown=morris:morris /workspace/backend/target/backend-*.jar app.jar

USER morris
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

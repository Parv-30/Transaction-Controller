FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY transaction-processor/pom.xml transaction-processor/pom.xml
COPY holds-service/pom.xml holds-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY fx-service/pom.xml fx-service/pom.xml
RUN mvn -q -pl fx-service -am dependency:go-offline
COPY fx-service fx-service
RUN mvn -q -pl fx-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/fx-service/target/fx-service-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

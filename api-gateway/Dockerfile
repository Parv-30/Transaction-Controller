FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY transaction-processor/pom.xml transaction-processor/pom.xml
COPY holds-service/pom.xml holds-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY fx-service/pom.xml fx-service/pom.xml
COPY gateway-simulator/pom.xml gateway-simulator/pom.xml
RUN mvn -q -pl api-gateway -am dependency:go-offline
COPY api-gateway api-gateway
RUN mvn -q -pl api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/api-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

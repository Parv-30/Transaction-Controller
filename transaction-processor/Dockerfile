FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY transaction-processor/pom.xml transaction-processor/pom.xml
COPY holds-service/pom.xml holds-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY fx-service/pom.xml fx-service/pom.xml
COPY gateway-simulator/pom.xml gateway-simulator/pom.xml
RUN mvn -q -pl transaction-processor -am dependency:go-offline
COPY transaction-processor transaction-processor
RUN mvn -q -pl transaction-processor -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/transaction-processor/target/transaction-processor-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

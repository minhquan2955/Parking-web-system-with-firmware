FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21.0.8_9-jre-alpine
WORKDIR /app
RUN addgroup -S parking && adduser -S parking -G parking
COPY --from=build /build/target/parking-api-1.0.0.jar app.jar
USER parking
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]

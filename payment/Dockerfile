FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY . .

RUN mvn clean package -DskipTests -q -pl payment -am

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/payment/target/*.jar app.jar
USER appuser
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
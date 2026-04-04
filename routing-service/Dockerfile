FROM eclipse-temurin:23-jre-alpine

WORKDIR /app

COPY build/libs/*-SNAPSHOT.jar app.jar

RUN mkdir -p /app/graph-data /app/graph-cache

ENTRYPOINT ["java", "-jar", "app.jar"]
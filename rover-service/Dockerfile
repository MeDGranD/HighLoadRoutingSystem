FROM eclipse-temurin:24-jre

WORKDIR /app

COPY build/libs/*-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
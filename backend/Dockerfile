# -- build stage --
FROM gradle:8.14-jdk17 AS build
WORKDIR /home/gradle/project
COPY . .
RUN gradle :app:bootJar --no-daemon

# -- runtime stage --
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /home/gradle/project/app/build/libs/app.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=5 CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

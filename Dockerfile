# syntax=docker/dockerfile:1
# Сборка из корня репозитория. Тесты здесь не запускаем: их уже прогнал CI.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests && cp target/gateway-*.jar /app.jar

FROM eclipse-temurin:21-jre
# Не root: у процесса в контейнере нет лишних прав
RUN useradd --system --uid 10001 app
USER app
COPY --from=build /app.jar /app.jar
# Куча — доля лимита памяти контейнера; при нехватке памяти процесс падает, и его перезапускают
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]

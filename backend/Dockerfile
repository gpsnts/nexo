FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew bootJar -x test --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin nexo
COPY --from=build /src/build/libs/*.jar /app.jar
EXPOSE 8080
USER nexo
ENTRYPOINT ["java", "-jar", "/app.jar"]

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew bootJar -x test --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin nexo
COPY --from=build /src/build/libs/*.jar /app.jar
EXPOSE 8080
# Pasta dos arquivos enviados (volume do compose); criada com o dono certo para o volume herdar a permissão.
RUN mkdir -p /dados/arquivos && chown -R nexo /dados
USER nexo
ENTRYPOINT ["java", "-jar", "/app.jar"]

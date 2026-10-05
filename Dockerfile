FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 appuser
COPY --from=build /app/target/hemocare.jar app.jar
USER appuser
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["sh","-c","exec java -XX:MaxRAMPercentage=75.0 -Dserver.port=${PORT:-8080} -Dserver.address=0.0.0.0 -jar /app/app.jar"]

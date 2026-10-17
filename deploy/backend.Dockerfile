FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY parking/parking/.mvn .mvn
COPY parking/parking/pom.xml pom.xml
RUN mvn -q -B dependency:go-offline
COPY parking/parking/src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17.0.14_7-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --create-home --uid 10001 parking
WORKDIR /app
COPY --from=build /build/target/parking-1.0-SNAPSHOT.jar app.jar
RUN mkdir uploads && chown parking:parking uploads
USER parking
ENV SPRING_CONFIG_NAME=application-example TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

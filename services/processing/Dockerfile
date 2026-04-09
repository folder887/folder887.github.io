FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml parent-pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn install -N -f parent-pom.xml -q
COPY services/processing/pom.xml pom.xml
COPY services/processing/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S paycore && adduser -S paycore -G paycore
WORKDIR /app
COPY --from=build /app/target/paycore-processing-*.jar app.jar
USER paycore

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+UseStringDeduplication \
               -Djava.security.egd=file:/dev/./urandom --enable-preview"

EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

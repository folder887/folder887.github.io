FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml parent-pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn install -N -f parent-pom.xml -q
COPY services/settlement/pom.xml pom.xml
COPY services/settlement/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S paycore && adduser -S paycore -G paycore
WORKDIR /app
COPY --from=build /app/target/paycore-settlement-*.jar app.jar
USER paycore

ENV JAVA_OPTS="-XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom --enable-preview"

EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
# Copy parent pom first (build context = repo root)
COPY pom.xml parent-pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn install -N -f parent-pom.xml -q
# Copy module
COPY services/gateway/pom.xml pom.xml
COPY services/gateway/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S paycore && adduser -S paycore -G paycore
WORKDIR /app
COPY --from=build /app/target/paycore-gateway-*.jar app.jar
USER paycore

# Virtual Threads + G1GC tuning for low-latency payment processing
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+UseStringDeduplication \
               -Djava.security.egd=file:/dev/./urandom \
               --enable-preview"

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Runtime-only image: the jar is built OUTSIDE docker (connect-library codegen
# needs the sibling Connect-API-Code checkout, which this build context lacks).
#   1. mvn -pl demo-ui -am package -DskipTests
#   2. docker build --platform linux/amd64 -t connect-sdk-demo .
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S demo && adduser -S demo -G demo
USER demo
WORKDIR /app

COPY demo-ui/target/demo-ui-0.0.1-SNAPSHOT.jar app.jar

# Per-env credentials + sqlite caches land here; ephemeral on App Runner by design.
ENV TSANET_DEMO_DATA_DIR=/tmp/tsanet-demo

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]

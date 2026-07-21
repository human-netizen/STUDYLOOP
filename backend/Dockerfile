# syntax=docker/dockerfile:1

# --- Build stage: compile and package the fat jar with Maven ------------------------------
# Uses the Maven image (not the ./mvnw wrapper) so the build never depends on the wrapper jar,
# which is git-ignored and absent in the Docker context.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Resolve dependencies first, in their own layer, so edits to src/ don't re-download the world.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# Compile and package. Tests need a live DB (Supabase) + Cohere, so they run in CI, not here.
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Runtime stage: a slim JRE that just runs the jar -------------------------------------
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

# Run as an unprivileged user, and give it a writable home for uploaded document bytes.
# In the cloud, mount a persistent disk at /data so uploads survive restarts.
RUN useradd -r -u 1001 -m -d /home/spring spring \
    && mkdir -p /data/documents \
    && chown -R spring:spring /data
ENV DOCUMENTS_DIR=/data/documents
USER spring

COPY --from=build /app/target/*.jar app.jar

# MaxRAMPercentage keeps the heap inside the container's memory limit; JAVA_OPTS lets the
# platform tune it further without rebuilding the image.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

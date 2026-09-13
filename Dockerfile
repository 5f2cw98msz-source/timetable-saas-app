# =============================================================================
#  Builds the app into a single container image.
#  Most cloud platforms (Render, Railway, Fly.io, Azure Container Apps, Google
#  Cloud Run) can build this file straight from a Git repository.
#
#  Build and run locally:
#      docker build -t chalkline .
#      docker run -p 8080:8080 \
#        -e DEMO_ORG_EMAIL=you@example.edu \
#        -e DEMO_ORG_PASSWORD=change_this_password \
#        -e SESSION_COOKIE_SECURE=false \
#        chalkline
# =============================================================================

# ---- Stage 1: compile ----
# Maven and a JDK are only needed to build, so they are thrown away afterwards.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies are copied and resolved first, so that editing source code does
# not re-download the whole internet on every rebuild.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Stage 2: run ----
# A JRE only. Smaller image, smaller attack surface.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run as root inside a container.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/target/chalkline.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# The platform sets PORT; application.yml reads it.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

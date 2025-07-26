FROM clojure:temurin-21-alpine AS builder

# Create directory for project code (working directory)
RUN mkdir -p /build

# Set Docker working directory
WORKDIR /build

# Install Node.js and bun first
RUN apk add --no-cache nodejs npm
RUN npm install -g bun

# Cache and install Clojure dependencies
# Add before copying code to cache the layer even if code changes
COPY deps.edn Makefile shadow-cljs.edn package.json /build/
RUN make deps

# Copy project to working directory
# .dockerignore file excludes all but essential files
COPY ./ /build

# `dist` task packages Clojure service as an uberjar
# - creates: /build/practicalli-gameboard-api-service.jar
# - uses command `clojure -T:build uberjar`
RUN make dist


# End of Docker builder image
# ------------------------------------------


# ------------------------------------------
# Docker container to run Practicalli Gameboard API Service
# run locally using: docker-compose up --build

# ------------------------
# Setup Run-time Container

# Official OpenJDK Image
FROM eclipse-temurin:21-alpine

# Example labels for runtime docker image
# LABEL org.opencontainers.image.authors="nospam+dockerfile@possible.space"
# LABEL net.clojars.tools.ifs.parts="tools ifs parts service"
# LABEL version="0.1.0-SNAPSHOT"
# LABEL description="parts.ifs.tools service"

# Add operating system packages
# - dumb-init to ensure SIGTERM sent to java process running Clojure service
# - Curl and jq binaries for manual running of system integration scripts
# check for newer package versions: https://pkgs.alpinelinux.org/
RUN apk add --no-cache \
    dumb-init~=1.2.5 \
    curl~=8 \
    jq~=1.7

# Create Non-root group and user to run service securely
RUN addgroup -g 1001 clojure && adduser -u 1001 -S clojure -G clojure

# Create directories to contain service archive and database, owned by non-root user
RUN mkdir -p /app /app/db && chown -R clojure:clojure /app

# Tell docker that all future commands should run as the appuser user
USER clojure

# Copy service archive file from Builder image
WORKDIR /app
COPY --from=builder /build/target/parts-standalone.jar /app/

# Optional: Add System Integration testing scripts
# RUN mkdir -p /app/test-scripts
# COPY --from=builder /build/test-scripts/curl--* /app/test-scripts/


# ------------------------
# Set Service Environment variables

# optional over-rides for Integrant configuration
# ENV HTTP_SERVER_PORT=
# ENV MYSQL_DATABASE=
ENV PARTS_ENV="production"
ENV PARTS_PROTOCOL="https"
ENV PARTS_HOSTNAME="parts.ifs.tools"
# TODO: Figure out a way to store PARTS_JWT_SECRET without exposing it in the
# Dockerfile

# Expose port of HTTP Server
EXPOSE 3000

# Expose nREPL port (only accessible within container network by default)
EXPOSE 7888

# ------------------------
# Run service

# Docker Service heathcheck
# docker inspect --format='{{json .State.Health}}' container-name
# - local heathcheck defined in `compose.yaml` service definition
# Heathchck options:
# --interval=30s --timeout=30s --start-period=10s --retries=3
# Shell:
HEALTHCHECK \
  CMD curl --fail http://localhost:3000/up || exit 1
# Exec array:
# HEALTHCHECK \
#     CMD ["curl", "--fail", "http://localhost:3000/up"]


# JDK_JAVA_OPTIONS environment variable for setting JVM options
# Use JVM options that optomise running in a container
# For very low latency, use the Z Garbage collector "-XX:+UseZGC"
ENV JDK_JAVA_OPTIONS="-XshowSettings:system -XX:+UseContainerSupport -XX:MaxRAMPercentage=90"

# Start service using dumb-init and java run-time
# (overrides `jshell` entrypoint - default in eclipse-temuring image)
ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD ["java", "-jar", "/app/parts-standalone.jar"]


# Docker Entrypoint documentation
# https://docs.docker.com/engine/reference/builder/#entrypoint

# $kill PID For Graceful Shutdown(SIGTERM) - can be caught for graceful shutdown
# $kill -9 PID For Forceful Shutdown(SIGKILL) - process ends immeciately
# SIGSTOP cannot be intercepted, process ends immediately

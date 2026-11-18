FROM maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /build
ENV MAVEN_OPTS=-Xmx768m
COPY server/ ./
RUN mvn --batch-mode --no-transfer-progress package

FROM eclipse-temurin@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55
LABEL org.opencontainers.image.title="D.CATALOG API"
WORKDIR /app
COPY --from=build /build/target/datacatalog-server-0.1.0-SNAPSHOT.jar /app/server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/server.jar"]

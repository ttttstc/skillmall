# Build the architecture-neutral JAR on the Buildx host. This avoids emulating
# the complete Maven build when the target image is linux/riscv64.
# 基础镜像可用 --build-arg BASE_REGISTRY=... 覆盖，便于从 SWR 拉取（国内构建机常拉不到 docker.io）。
# 这里刻意**不使用 BuildKit 专有语法**（FROM --platform / RUN --mount），
# 以便华为云 CodeArts 的经典 docker builder 直接构建；代价是不再支持跨架构构建
# （构建机架构即镜像架构，CCE x86 节点只出 linux/amd64）。
ARG BASE_REGISTRY=docker.io/library
FROM ${BASE_REGISTRY}/eclipse-temurin:21-jdk-alpine AS build
# 同上：Maven 下载依赖时优先 IPv4，避免双栈环境下 IPv6 不可达导致超时。
ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"
WORKDIR /app

# Cache dependencies
COPY pom.xml .
COPY skillhub-domain/pom.xml skillhub-domain/
COPY skillhub-auth/pom.xml skillhub-auth/
COPY skillhub-search/pom.xml skillhub-search/
COPY skillhub-infra/pom.xml skillhub-infra/
COPY skillhub-storage/pom.xml skillhub-storage/
COPY skillhub-notification/pom.xml skillhub-notification/
COPY skillhub-github-sync/pom.xml skillhub-github-sync/
COPY skillhub-app/pom.xml skillhub-app/
COPY mvnw .
COPY .mvn .mvn
# 防御：Windows 检出/ZIP 导出可能给 mvnw 带来 CRLF 或丢失可执行位。
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY . .
# The shared build context permits target/ for Dockerfile.dev. Recompile
# from source here instead of reusing host build outputs copied above.
# COPY 会把上面修过的 mvnw 覆盖回上下文里的版本（Windows 检出为 CRLF），这里再修一次。
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw clean package -DskipTests -B

# ---- Runtime Stage ----
# The Noble variant publishes a linux/riscv64 image; the Alpine JRE currently
# used by this project is limited to amd64 and arm64.
FROM ${BASE_REGISTRY}/eclipse-temurin:21-jre-noble
# Keep the Alpine image's numeric IDs so existing storage volumes remain writable.
RUN apt-get update && \
    apt-get install -y --no-install-recommends wget && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 101 app && \
    useradd --system --uid 100 --gid app --create-home app
WORKDIR /app

COPY --from=build /app/skillhub-app/target/*.jar app.jar

# Create storage directory and set permissions
RUN mkdir -p /var/lib/skillhub/storage && \
    chown -R app:app /app /var/lib/skillhub/storage

USER app

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

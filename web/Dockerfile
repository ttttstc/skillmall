# The frontend output is static and architecture-neutral. Build it on the
# Buildx host so linux/riscv64 does not depend on a target-architecture Node.js
# image; only the multi-architecture Nginx runtime is target-specific.
# 与 server/Dockerfile 一致：不使用 BuildKit 专有语法（经典 builder 可直接构建），
# 基础镜像可用 --build-arg BASE_REGISTRY=... 覆盖以从 SWR 拉取。
ARG BASE_REGISTRY=docker.io/library
FROM ${BASE_REGISTRY}/node:22-alpine AS build
RUN corepack enable
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
COPY patches ./patches
# Corepack uses Node fetch; explicitly honor the build proxy for its download.
# 容器内 DNS 可能只返回 IPv6（而构建机无 IPv6 出口），导致 registry 解析失败：EAI_AGAIN。
# 让 Node 在双栈环境优先 IPv4；纯 IPv6 环境下该选项会被自动忽略。
ENV NODE_OPTIONS=--dns-result-order=ipv4first
RUN NODE_USE_ENV_PROXY=1 pnpm install --frozen-lockfile
COPY . .
ARG VITE_BASE_PATH=/__SKILLHUB_WEB_BASE_PATH__/
ENV VITE_BASE_PATH=$VITE_BASE_PATH
RUN pnpm build

FROM ${BASE_REGISTRY}/nginx:alpine
ENV SKILLHUB_TRUST_FORWARDED_PROTO=false
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
# Record a fixed build-time base so the entrypoint defaults SKILLHUB_WEB_BASE_PATH
# to it and generates matching Nginx routing without repeating the value at runtime.
# Placeholder builds (the default) intentionally write no file and default to '/'.
ARG VITE_BASE_PATH=/__SKILLHUB_WEB_BASE_PATH__/
RUN if [ "$VITE_BASE_PATH" != "/__SKILLHUB_WEB_BASE_PATH__/" ]; then \
      mkdir -p /etc/skillhub && printf '%s' "$VITE_BASE_PATH" > /etc/skillhub/baked-base-path; \
    fi
COPY --from=build /app/dist /usr/share/nginx/html
COPY --from=build /app/src/docs/skill.md.template /usr/share/nginx/html/registry/skill.md.template
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY runtime-config.js.template /usr/share/nginx/html/runtime-config.js.template
COPY docker-entrypoint.d/20-base-path.sh /docker-entrypoint.d/20-base-path.sh
COPY docker-entrypoint.d/30-runtime-config.sh /docker-entrypoint.d/30-runtime-config.sh
COPY analytics-config.awk /etc/skillhub/analytics-config.awk
COPY docker-entrypoint.d/40-analytics.sh /docker-entrypoint.d/40-analytics.sh
# 防御：入口脚本若带 CRLF 会在运行时失效，先去掉 CR 再赋可执行位。
RUN sed -i 's/\r$//' /docker-entrypoint.d/*.sh && chmod +x /docker-entrypoint.d/20-base-path.sh /docker-entrypoint.d/30-runtime-config.sh /docker-entrypoint.d/40-analytics.sh
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s \
  CMD wget -qO- http://127.0.0.1/nginx-health || exit 1

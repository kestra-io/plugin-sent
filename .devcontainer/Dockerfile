FROM eclipse-temurin:25-jdk-noble

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl git zip unzip less zsh net-tools iputils-ping jq lsof \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspaces/plugin-sent
RUN git config --global --add safe.directory /workspaces/plugin-sent
ENV SHELL=/bin/zsh
ENV MICRONAUT_ENVIRONMENTS=local,override
ENV KESTRA_PLUGINS_PATH=/workspaces/plugin-sent/local/plugins

# Keep the JDK/base filesystem reproducible. Update this digest deliberately.
FROM eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        fontconfig \
        git \
        gh \
        fonts-dejavu-core \
        fonts-jetbrains-mono \
        fonts-liberation \
        libasound2t64 \
        libfreetype6 \
        libglib2.0-bin \
        libx11-6 \
        libxext6 \
        libxi6 \
        libxrender1 \
        libxtst6 \
        make \
        xauth \
        xvfb \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    GRADLE_USER_HOME=/workspace/.cache/gradle

WORKDIR /workspace

# The launcher overrides this with the host UID/GID so bind-mounted outputs
# belong to the developer. This default also avoids running as root directly.
USER 1000:1000
ENTRYPOINT []
CMD ["./gradlew", "--no-daemon", "build", "installDist"]

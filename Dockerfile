# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build
# Pakai eclipse-temurin:21-jdk-jammy karena punya file 'release' yang dibutuhkan
# Gradle toolchain scanner. Beda dengan apt-installed JDK yang sering gagal.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Copy Gradle wrapper + build scripts dulu (cache layer terpisah dari source)
COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/

# Download Gradle distribution & resolve dependencies tanpa source code dulu.
# Layer ini di-cache oleh Docker selama build.gradle.kts tidak berubah —
# sehingga ./gradlew dependencies tidak dijalankan ulang tiap build.
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

# Copy source code
COPY src/ src/

# Build fat JAR (shadowJar / application distribution)
# --no-daemon penting di container: daemon Gradle tidak berguna dan makan RAM
RUN ./gradlew installDist --no-daemon -q

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime
# Pakai JRE (bukan JDK) untuk image lebih kecil di production
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# Metadata
LABEL org.opencontainers.image.title="api-manga"
LABEL org.opencontainers.image.version="2.0.0"
LABEL org.opencontainers.image.description="Universal manga REST API powered by kotatsu-parsers"

WORKDIR /app

# Copy distribusi yang sudah di-build dari stage builder
COPY --from=builder /build/build/install/api-manga/ ./

# Buat direktori data untuk penyimpanan API key
# Volume di-mount ke sini saat docker compose up
RUN mkdir -p /app/data

# Expose port (sesuai env PORT, default 8080)
EXPOSE 8080

# Jalankan sebagai non-root user untuk keamanan
RUN groupadd --gid 1001 appgroup && \
    useradd  --uid 1001 --gid appgroup --no-create-home appuser && \
    chown -R appuser:appgroup /app
USER appuser

# Health check — Docker akan restart container jika /health tidak merespons
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:${PORT:-8080}/health || exit 1

ENTRYPOINT ["./bin/api-manga"]

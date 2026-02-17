import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // IMPORTANT: Must exactly match the Kotlin version used by kotatsu-parsers.
    // Mismatching causes: "binary version of metadata is 2.2.0, expected 2.0.0"
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "com.mangaapi"
version = "2.0.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io") {
        name = "JitPack"
        // SNAPSHOT pinning strategy: Gradle caches the SNAPSHOT for 24 hours by default.
        // This means `./gradlew run` won't hit JitPack on every build — only once per day.
        // To force a fresh download manually: ./gradlew run --refresh-dependencies
        content { includeGroup("com.github.YakaTeam") }
    }
    maven("https://maven.google.com") { name = "Google" }  // androidx.collection-jvm
}

val ktorVersion       = "3.1.3"   // Ktor 3.x required for Kotlin 2.2+ compatibility
val coroutinesVersion = "1.10.2"  // matches kotatsu-parsers libs.versions.toml
val okhttpVersion     = "5.1.0"   // matches kotatsu-parsers libs.versions.toml

dependencies {
    // ── Ktor server (Netty engine) ───────────────────────────────────────────
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // FIX: Added Ktor's built-in RateLimit plugin.
    // Replaces the hand-rolled ConcurrentHashMap rate limiter which had a
    // subtle race condition between compute() and the limit check.
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")

    // ── kotatsu-parsers (YakaTeam fork) via JitPack ──────────────────────────
    // Uses master-SNAPSHOT — Gradle caches this for 24h by default so it won't
    // re-download on every build. To force a refresh: ./gradlew run --refresh-dependencies
    //
    // NOTE: Pinning to a raw commit hash (e.g. :5ebde38d...) requires JitPack to have
    // previously built that exact commit. If JitPack hasn't built it yet the resolution
    // times out. master-SNAPSHOT always resolves because JitPack builds it continuously.
    implementation("com.github.YakaTeam:kotatsu-parsers:master-SNAPSHOT")

    // ── Network ──────────────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")

    // ── Coroutines & serialization ───────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // ── Logging ──────────────────────────────────────────────────────────────
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // ── Parser dependencies ──────────────────────────────────────────────────
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("androidx.collection:collection-jvm:1.5.0")  // no Android runtime needed
}

application {
    mainClass.set("com.mangaapi.ApplicationKt")
}

// NOTE: Do NOT use kotlin { jvmToolchain(N) } here.
// Gradle toolchain auto-detection fails on Debian/Ubuntu when JDK is installed
// via apt because apt-installed JDKs lack the 'release' file Gradle looks for.
// Direct sourceCompatibility + jvmTarget works with any JDK on PATH / JAVA_HOME.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi",
        )
    }
}

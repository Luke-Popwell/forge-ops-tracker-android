import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "io.github.lukepopwell.tracker.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 21 // covers HttpURLConnection, Thread.UncaughtExceptionHandler, and everything
                     // else this library touches: all present since API 1, so 21 is chosen for
                     // broad real-world app compatibility, not because anything here needs it
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Zero runtime dependencies: see README.md's "Dependencies" section. HttpURLConnection
    // (java.net, present since API 1) covers HTTP delivery; Kotlin's own Regex covers PII
    // scrubbing; org.json.JSONObject (bundled with the Android platform itself, not a Maven
    // dependency) covers JSON encoding.

    testImplementation("junit:junit:4.13.2")
    // Robolectric runs Android-framework-dependent code (Context, SharedPreferences-adjacent
    // file APIs, Looper/Handler for the ANR watchdog) on a plain JVM, without an emulator or
    // device: this environment has no emulator set up, so real instrumented (androidTest)
    // coverage isn't possible here; Robolectric is the standard, well-established way to still
    // get real test coverage of Android-API-touching code under those constraints.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    // Mockito: only for AnrWatchdogTest, the same reason sdks/java's own README cites for its
    // own Mockito use: Reporter (a concrete class, not an interface) is impractical to hand-write
    // a fake for just to verify AnrWatchdog calls into it correctly. Mockito 5's default inline
    // mock maker handles Kotlin's final-by-default classes with no extra setup.
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // A real local HTTP server for Client/Reporter/ForgeOpsTracker tests, the same "test against
    // something real, not a mock" approach sdks/java/sdks/kotlin use for their own Client tests,
    // just via MockWebServer here instead of com.sun.net.httpserver.HttpServer, which AGP's
    // unit-test compile classpath doesn't expose (confirmed directly: a hard "unresolved
    // reference" compile error, not a runtime issue) the way a plain-JVM Kotlin/Maven project's
    // own classpath does.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Maven Central publishing, via com.vanniktech.maven.publish: Gradle has no direct equivalent
// of central-publishing-maven-plugin (the plugin sdks/java/sdks/kotlin use in their own pom.xml
// for the same job), so this well-established, purpose-built plugin fills that role here
// instead: it handles POM generation, sources/javadoc jar packaging, GPG signing, and the actual
// Central Portal upload all in one place.
//
// publishToMavenCentral(automaticRelease = false) mirrors autoPublish=false on the Maven side:
// a real `./gradlew publish` only uploads and validates the bundle, it does not release it to
// Central automatically, that still needs a deliberate follow-up through the Central Portal
// website. signAllPublications() wires up GPG signing for every publication, but only fails a
// build that actually runs a Sign task (i.e. a real publish): neither credentials nor a signing
// key are configured in this environment, and none of that is needed for `./gradlew test` (or
// any other non-publishing task) to keep working.
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()

    // The groupId here is "io.github.luke-popwell.tracker.android", not
    // "io.github.lukepopwell.tracker.android" the Android `namespace` above uses: Sonatype
    // Central's namespace verification is tied to the exact GitHub username string, hyphen
    // included: io.github.luke-popwell, confirmed directly against the account's own Namespaces
    // page in Central Portal rather than assumed. This groupId is just Maven publishing metadata,
    // not a real Kotlin package (unlike `namespace` above, which can't contain a hyphen at all),
    // so the two are free to diverge and deliberately do here. See that field's own comment in
    // README.md for why this client's coordinates carry the ".tracker.android" suffix at all.
    coordinates("io.github.luke-popwell.tracker.android", "forgeopstracker", "0.15.1")

    pom {
        name.set("ForgeOps Tracker (Android)")
        description.set(
            "Kotlin error/crash reporting client for ForgeOps, for Android apps. Crash/ANR reports are written to durable on-disk " +
                "storage first and delivered on the next launch, not from the dying process itself."
        )
        inceptionYear.set("2026")
        url.set("https://getforgeops.net")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("forgeops")
                name.set("ForgeOps")
                url.set("https://getforgeops.net")
            }
        }

        // The public read-only mirror of sdks/android (see script/release_sdks_lib/mirror.rb).
        scm {
            url.set("https://github.com/Luke-Popwell/forge-ops-tracker-android")
            connection.set("scm:git:https://github.com/Luke-Popwell/forge-ops-tracker-android.git")
            developerConnection.set(
                "scm:git:https://github.com/Luke-Popwell/forge-ops-tracker-android.git"
            )
        }
    }
}

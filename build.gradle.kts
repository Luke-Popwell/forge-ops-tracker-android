// Root build script: deliberately empty besides plugin declarations (applied `false` here,
// actually applied in :forgeopstracker's own build.gradle.kts). Standard multi-module Gradle
// convention, even though this project only has the one module today.
plugins {
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("com.vanniktech.maven.publish") version "0.33.0" apply false
}

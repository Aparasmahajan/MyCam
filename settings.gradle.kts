pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FRAME"

// Pure-Kotlin modules: build and test with only a JDK.
include(":core:model", ":core:retention", ":core:evidence")

// Android modules need an Android SDK. Without one (e.g. a laptop with only a JDK),
// the JVM modules above can still be built and tested; CI always has the SDK.
val localProps = file("local.properties")
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))

if (hasAndroidSdk) {
    include(":core:data", ":core:recording", ":app")
} else {
    println("FRAME: no Android SDK found - only the pure-Kotlin :core modules are included.")
}

// ProxAI app shell (checklist Phase 15). Every app ProxAI generates becomes its own APK made of
// this shell's compiled code + the app's own www/ files, manifest and icon. The shell is compiled
// once here; `./gradlew :appshell:shellKit` puts its classes.dex in build/shell-kit/, which the
// Hermes sandbox image copies in for `proxai-app-build`. No AndroidX: plain framework classes keep
// the APKs small and the code free of resource ids (the per-app build has its own resources).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxai.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.proxai.shell.template"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            // Class names must stay as they are: the per-app manifest names them.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val shellKitVersion = "1"

tasks.register<Copy>("shellKit") {
    description = "Extracts the shell's classes.dex into build/shell-kit for the Hermes sandbox image."
    dependsOn("assembleRelease")
    from(zipTree(layout.buildDirectory.file("outputs/apk/release/appshell-release.apk"))) { include("classes*.dex") }
    into(layout.buildDirectory.dir("shell-kit"))
    doLast { layout.buildDirectory.file("shell-kit/VERSION").get().asFile.writeText(shellKitVersion + "\n") }
}

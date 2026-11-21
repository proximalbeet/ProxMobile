import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// WAKE-PHONE: "Hey Prox" needs the sherpa-onnx engine (app/libs/sherpa-onnx.aar) and its keyword model
// (app/wake/assets/wake/), which Nathan adds with scripts/wake-engine.sh (README, "Hey Prox"). Neither is in git.
// Without the AAR the app builds as before (src/nowake: no engine; the Settings switch says so). With it, the
// build refuses unless the AAR matches its pin in app/wake-engine.sha256, both licences there are Apache-2.0 or
// MIT, each of the 5 model files matches its sha256 in app/wake-model.sha256 (written by the install), and the
// keywords line matches keywords_sha256 and carries its own threshold.
val wakeAar = file("libs/sherpa-onnx.aar")
val wakeOn = wakeAar.exists()
fun wakeSha(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
    .joinToString("") { "%02x".format(it) }
if (wakeOn) {
    val pins = file("wake-engine.sha256").readLines()
        .map { it.substringBefore("#").trim() }
        .filter { "=" in it }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    // The engine (AAR): Apache-2.0 or MIT only. The keyword model may also be "unstated-own-phone" (Nathan, 11-18):
    // then the bundled NOTICE must say so, and that this APK stays on his own phone.
    val engineLicence = pins["engine_licence"].orEmpty()
    if (engineLicence !in setOf("Apache-2.0", "MIT")) throw GradleException(
        "app/wake-engine.sha256: engine_licence must be Apache-2.0 or MIT (is '$engineLicence'). See README, Hey Prox.")
    val modelLicence = pins["model_licence"].orEmpty()
    if (modelLicence !in setOf("Apache-2.0", "MIT", "unstated-own-phone")) throw GradleException(
        "app/wake-engine.sha256: model_licence must be Apache-2.0, MIT or unstated-own-phone (is '$modelLicence').")
    if (modelLicence == "unstated-own-phone") {
        val notice = file("wake/assets/wake/NOTICE.txt")
        val says = if (notice.isFile) notice.readText() else ""
        if ("unstated-own-phone" !in says || "own phone only" !in says) throw GradleException(
            "app/wake/assets/wake/NOTICE.txt must repeat unstated-own-phone and that this APK is for his own phone " +
                "only: run scripts/wake-engine.sh install")
    }
    val want = pins["aar_sha256"].orEmpty().lowercase()
    val got = wakeSha(wakeAar)
    if (want.length != 64 || got != want) throw GradleException(
        "app/libs/sherpa-onnx.aar doesn't match aar_sha256 in app/wake-engine.sha256 (it is $got). Remove it or pin it.")
    val sumsFile = file("wake-model.sha256")
    if (!sumsFile.isFile) throw GradleException("app/wake-model.sha256 is missing: run scripts/wake-engine.sh install")
    val sums = sumsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { it.substringAfter("  ").trim() to it.substringBefore("  ").trim().lowercase() }
    for (f in listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt")) {
        val model = file("wake/assets/wake/$f")
        if (!model.isFile) throw GradleException(
            "The wake model isn't installed (app/wake/assets/wake/$f is missing): run scripts/wake-engine.sh install")
        val pinned = sums[f].orEmpty()
        if (pinned.length != 64 || wakeSha(model) != pinned) throw GradleException(
            "app/wake/assets/wake/$f doesn't match its sha256 in app/wake-model.sha256: run scripts/wake-engine.sh install")
    }
    val keywords = file("wake/assets/wake/keywords.txt")
    if (wakeSha(keywords) != pins["keywords_sha256"].orEmpty().lowercase()) throw GradleException(
        "The installed keywords line doesn't match keywords_sha256 in app/wake-engine.sha256")
    val line = keywords.readText().trim()
    if ("\n" in line || !Regex("""(^|\s)#0\.([1-8]\d*|90*)(\s|$)""").containsMatchIn(line) ||
        !Regex("""(^|\s)@HEY_PROX(\s|$)""").containsMatchIn(line)) throw GradleException(
        "The keywords line must be one line with its own #threshold and @HEY_PROX (is: $line)")
}

android {
    namespace = "com.proxai.hermes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.proxai.hermes"
        minSdk = 29
        targetSdk = 35
        versionCode = 50
        versionName = "0.9.19"
        // WAKE-PHONE: the engine's native code only for the phone's own CPU (keeps the APK ~30 MB smaller).
        if (wakeOn) ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets {
        getByName("main") {
            java.srcDir(if (wakeOn) "src/wake/java" else "src/nowake/java")
            if (wakeOn) assets.srcDir("wake/assets")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so `assembleRelease` gives an installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // v0.5: PC temperature alerts checked in the background (~every 15 min)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // WAKE-PHONE: sherpa-onnx keyword spotting (Apache-2.0), only when Nathan has added the pinned AAR
    if (wakeOn) implementation(files("libs/sherpa-onnx.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

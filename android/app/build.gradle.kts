plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}

// Only these reviewed modules belong in the APK. Never use runtime/ as a Python
// source directory: it also contains private config, databases and local reports.
val phonePythonDir = layout.buildDirectory.dir("generated/phonePython")
val preparePhonePython by tasks.registering(Sync::class) {
    from(rootProject.file("../server")) {
        include("__init__.py", "core.py", "provider.py", "model_gateway.py", "mobile_bridge.py")
        into("server")
    }
    into(phonePythonDir)
}

val phoneAssetsDir = layout.buildDirectory.dir("generated/phoneAssets")
val preparePhoneAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../fixtures")) {
        include("resource_graph_v1.txt", "resource_graph_v1.pdf", "resource_graph_v2.txt")
        into("fixtures")
    }
    into(phoneAssetsDir)
}

android {
    namespace = "org.wenji.study"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.wenji.study"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(phoneAssetsDir)
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // Override on machines without Python 3.12 on PATH; keep local paths out
        // of the project and all build configuration out of the packaged source.
        providers.gradleProperty("wenjiBuildPython")
            .orElse(providers.environmentVariable("WENJI_BUILD_PYTHON"))
            .orNull?.let { buildPython(it) }
        pip { install("pypdf==6.21.4") }
        pyc { src = true }
    }
    sourceSets {
        configureEach { setSrcDirs(emptyList<String>()) }
        getByName("main") { setSrcDirs(listOf(phonePythonDir)) }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("PythonSources") }
    .configureEach { dependsOn(preparePhonePython) }
tasks.named("preBuild") { dependsOn(preparePhoneAssets) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}
android {
    namespace = "com.rawwave.shell"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.rawwave.rebuild"
        minSdk = 30
        targetSdk = 36
        versionCode = 109
        versionName = "0.12.35-step"
        buildConfigField("String", "DEVICE_ROLE", "\"phone\"")
        val runId = providers.gradleProperty("buildRun").getOrElse("local-unpackaged")
        require(runId.matches(Regex("[A-Za-z0-9._-]+")))
        buildConfigField("String", "BUILD_RUN", "\"" + runId + "\"")
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main") {
        kotlin.directories += "../app-common/java"
        res.directories += "../app-common/res"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = false
        disable += "InvalidFragmentVersionForActivityResult"
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(project(":shared"))
    implementation(project(":designsystem"))
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("samsung-health-data-api-*.aar"))))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.2.10")
    implementation("com.google.code.gson:gson:2.11.0")
}




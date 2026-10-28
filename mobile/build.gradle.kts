plugins { id("com.android.application") }
android {
    namespace = "com.rawwave.shell"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.rawwave.rebuild"
        minSdk = 30
        targetSdk = 36
        versionCode = 57
        versionName = "0.8.2-eda-end-boundary"
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
    implementation("androidx.activity:activity:1.9.3")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}



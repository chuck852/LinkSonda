import java.util.Properties

plugins {
    id("com.android.application") version "9.3.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.14"
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionCode = versionProperties.getProperty("versionCode")?.toIntOrNull()
    ?: error("version.properties must define an integer versionCode")
val appVersionName = versionProperties.getProperty("versionName")?.takeIf { it.isNotBlank() }
    ?: error("version.properties must define a non-empty versionName")
val updaterTestUrl = providers.gradleProperty("updaterTestUrl").getOrElse("")
val robolectricAndroidAll = configurations.create("robolectricAndroidAll")
val robolectricDependencyDir = layout.buildDirectory.dir("robolectric-dependencies")
val copyRobolectricDependencies = tasks.register<Copy>("copyRobolectricDependencies") {
    from(robolectricAndroidAll)
    into(robolectricDependencyDir)
}
val escapedUpdaterTestUrl = updaterTestUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "io.github.chuck852.linksonda"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.chuck852.linksonda"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATER_TEST_URL", "\"$escapedUpdaterTestUrl\"")
        buildConfigField("boolean", "UPDATER_TEST_SOURCE", updaterTestUrl.isNotBlank().toString())
        manifestPlaceholders["usesCleartextTraffic"] = updaterTestUrl.isNotBlank().toString()
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    robolectricAndroidAll("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("androidx.test:core:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}


tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricDependencies)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDependencyDir.get().asFile.absolutePath)
}

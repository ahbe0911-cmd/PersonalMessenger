import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.personal.messenger"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.personal.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/vazirmatn/res"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/vazirmatn/assets"))
}
val fontRoot = layout.buildDirectory.dir("generated/vazirmatn")
val fetchFonts by tasks.registering {
    outputs.dir(fontRoot)
    doLast {
        val root = fontRoot.get().asFile
        val fontDir = root.resolve("res/font").apply { mkdirs() }
        val assets = root.resolve("assets").apply { mkdirs() }
        val base = "https://raw.githubusercontent.com/rastikerdar/vazirmatn/v33.003/"
        mapOf(
            "fonts/ttf/Vazirmatn-Regular.ttf" to fontDir.resolve("vazirmatn_regular.ttf"),
            "fonts/ttf/Vazirmatn-Bold.ttf" to fontDir.resolve("vazirmatn_bold.ttf"),
            "OFL.txt" to assets.resolve("Vazirmatn-OFL.txt")
        ).forEach { (path, dest) ->
            val connection = URI.create(base + path).toURL().openConnection()
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.getInputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            check(dest.length() > 100) { "Invalid font resource: " + path }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchFonts) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
}

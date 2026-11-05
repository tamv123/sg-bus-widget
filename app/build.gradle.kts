import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.github.triplet.play")
}

// Upload key lives outside the repo (~/.android-keys); override with -PsigningProps=/path/to.properties
val signingProps = Properties().apply {
    val f = file((findProperty("signingProps") as String?) ?: "${System.getProperty("user.home")}/.android-keys/sg-bus-upload.properties")
    if (f.exists()) f.inputStream().use(::load)
}

android {
    namespace = "sg.bus.widget"
    compileSdk = 36
    defaultConfig {
        applicationId = "sg.bus.widget"
        minSdk = 34
        targetSdk = 36
        versionCode = 8
        versionName = "0.6.0"
    }
    signingConfigs {
        if (signingProps.isNotEmpty()) create("upload") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")   // null → unsigned, Play upload will refuse it
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// `scripts/publish.sh` → Play internal testing. Same play-publisher service account as sg-pm25-widget.
// Only on publish runs: AUTO versioning asks Play for its version codes, which a plain build shouldn't need.
val playKey = file("${System.getProperty("user.home")}/.android-keys/sg-pm25-play.json")
play {
    enabled.set(playKey.exists() && gradle.startParameter.taskNames.any { "publish" in it || "promote" in it })
    serviceAccountCredentials.set(playKey)
    track.set((findProperty("playTrack") as String?) ?: "internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(if (findProperty("playDraft") == "true") ReleaseStatus.DRAFT else ReleaseStatus.COMPLETED)
    // versionCode already on Play → use Play's highest + 1, so a release is never rejected as a duplicate
    resolutionStrategy.set(ResolutionStrategy.AUTO)
}

dependencies {
    implementation(project(":core"))
}

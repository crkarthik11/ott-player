plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fullPlaylistUrl = providers.gradleProperty("fullPlaylistUrl")
    .getOrElse("https://jiotv.example.lan/playlist.m3u")
val guideUrl = providers.gradleProperty("guideUrl")
    .getOrElse("https://jiotv.example.lan/epg.xml.gz")
val musicListName = providers.gradleProperty("musicListName").getOrElse("Music mix")
val keystorePassword: String? = System.getenv("OTT_KEYSTORE_PASSWORD")

android {
    namespace = "io.github.crkarthik11.ottplayer"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.crkarthik11.ottplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "GUIDE_URL", "\"$guideUrl\"")
        buildConfigField("String", "FULL_PLAYLIST_URL", "\"$fullPlaylistUrl\"")
        buildConfigField("String", "MUSIC_LIST_NAME", "\"$musicListName\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = keystorePassword
            keyAlias = "ottplayer"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        // Lint adds minutes to every build and has nothing to say about a
        // single-screen sideloaded app; run `gradle lint` by hand if needed.
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}

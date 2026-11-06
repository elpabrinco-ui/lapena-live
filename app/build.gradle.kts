plugins {
  id("com.android.application")
}

android {
  namespace = "com.lapena.live"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.lapena.live"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "0.1-etapa1"
  }

  signingConfigs {
    getByName("debug") {
      // Fixed debug key so new versions install over old ones.
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
  implementation("androidx.appcompat:appcompat:1.6.1")
}

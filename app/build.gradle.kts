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
    // En GitHub, cada compilación tiene un número que siempre sube: se usa como versión.
    val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
    versionCode = build
    versionName = "1.$build"
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
  implementation("com.google.android.gms:play-services-auth:21.5.1")
}

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
    // Llave privada: vive en los secretos de GitHub, nunca en el código.
    create("release") {
      storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
      storePassword = System.getenv("KEYSTORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS")
      keyPassword = System.getenv("KEYSTORE_PASSWORD")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
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

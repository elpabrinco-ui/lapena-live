import java.net.URI

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

    // Ramas de prueba: se instalan como OTRA app ("La Peña Live PRUEBA"), sin pisar la del equipo.
    val branch = System.getenv("GITHUB_REF_NAME") ?: "main"
    if (branch != "main") {
      applicationIdSuffix = ".prueba"
      versionNameSuffix = "-prueba"
      manifestPlaceholders["appLabel"] = "La Peña Live PRUEBA"
    } else {
      manifestPlaceholders["appLabel"] = "La Peña Live"
    }
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
  // El modelo de IA se lee directo del APK: no hay que comprimirlo.
  androidResources {
    noCompress += "tflite"
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
  // IA para el seguimiento automático (detecta jugadores y pelota dentro del celular).
  implementation("com.google.mediapipe:tasks-vision:0.10.26")
}

// Modelo de IA (EfficientDet-Lite0, de Google, licencia Apache 2.0). Se descarga al compilar
// para no guardar un archivo binario grande en el repositorio.
val modelFile = layout.projectDirectory.file("src/main/assets/efficientdet_lite0.tflite")
val downloadModel = tasks.register("downloadModel") {
  val target = modelFile.asFile
  outputs.file(target)
  doLast {
    if (!target.exists() || target.length() < 1000) {
      target.parentFile.mkdirs()
      val url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite"
      URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    }
  }
}
tasks.named("preBuild") { dependsOn(downloadModel) }

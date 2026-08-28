plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
}

import java.util.Base64
import java.io.File

// Decode google-services.json from Base64 if present in environment
val googleServicesBase64 = System.getenv("GOOGLE_SERVICES_JSON_BASE64") ?: ""
if (googleServicesBase64.isNotBlank()) {
    try {
        val cleanBase64 = googleServicesBase64.replace("\\s+".toRegex(), "")
        val bytes = try { Base64.getDecoder().decode(cleanBase64) } catch(e: Exception) { Base64.getMimeDecoder().decode(cleanBase64) }
        file("google-services.json").writeBytes(bytes)
        println("Successfully generated google-services.json from GOOGLE_SERVICES_JSON_BASE64 secret.")
    } catch (e: Exception) {
        println("Warning: Failed to decode GOOGLE_SERVICES_JSON_BASE64. ${e.message}")
    }
}

// Decode release keystore from Base64 if present in environment
val releaseKeystoreBase64 = System.getenv("RELEASE_KEYSTORE_BASE64") ?: ""
if (releaseKeystoreBase64.isNotBlank()) {
    try {
        val cleanBase64 = releaseKeystoreBase64.replace("\\s+".toRegex(), "")
        val bytes = try { Base64.getDecoder().decode(cleanBase64) } catch(e: Exception) { Base64.getMimeDecoder().decode(cleanBase64) }
        val ksFile = rootProject.file("earthlink_reseller_release.jks")
        ksFile.writeBytes(bytes)
        println("Successfully generated earthlink_reseller_release.jks from RELEASE_KEYSTORE_BASE64 secret.")
    } catch (e: Exception) {
        println("Warning: Failed to decode RELEASE_KEYSTORE_BASE64. ${e.message}")
    }
}

android {
  namespace = "com.alamiry.earthlinkreseller"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.alamiry.earthlinkreseller"
    minSdk = 24
    targetSdk = 36
    versionCode = 68
    versionName = "1.68.0"
    
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val envKs = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        ?.removeSurrounding("\"")?.removeSurrounding("'")
      val ksFile = sequenceOf(
        envKs?.let { file(it) },
        envKs?.let { rootProject.file(it.removePrefix("/")) },
        rootProject.file("earthlink_reseller_release.jks"),
        file("earthlink_reseller_release.jks")
      ).filterNotNull().firstOrNull { it.exists() } ?: rootProject.file("earthlink_reseller_release.jks")

      val storePwd = System.getenv("STORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
      val kAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?.removeSurrounding("\"")?.removeSurrounding("'") ?: "alamiry.earthlink.reseller"
      val kPwd = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""

      storeFile = ksFile
      storePassword = storePwd
      keyAlias = kAlias
      keyPassword = kPwd
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
    resValues = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all { testTask ->
        val gradleUserHome = project.gradle.gradleUserHomeDir.absolutePath
        val byteBuddyJar = file("$gradleUserHome/caches/modules-2/files-2.1/net.bytebuddy/byte-buddy-agent/1.14.12/be4984cb6fd1ef1d11f218a648889dfda44b8a15/byte-buddy-agent-1.14.12.jar")
        if (byteBuddyJar.exists()) {
          testTask.jvmArgs(
            "-XX:+EnableDynamicAgentLoading",
            "-Dnet.bytebuddy.experimental=true",
            "-javaagent:${byteBuddyJar.absolutePath}"
          )
        } else {
          testTask.jvmArgs(
            "-XX:+EnableDynamicAgentLoading",
            "-Dnet.bytebuddy.experimental=true"
          )
        }
      }
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("STORE_PASSWORD")
  ignoreList.add("KEY_PASSWORD")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
    implementation(libs.commons.compress)
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.crashlytics)
  implementation(libs.play.services.auth)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.security.crypto)
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.commons.compress)
  testImplementation(libs.commons.compress)
  testImplementation("org.mockito:mockito-core:5.11.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
  implementation(libs.sqlcipher)
  implementation(libs.sqlite)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  testImplementation(libs.androidx.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

ksp {
  arg("room.schemaLocation", "${projectDir}/src/main/assets")
}

tasks.register<Exec>("checkIoUseBlocks") {
    group = "verification"
    description = "Checks that specific IO classes and Cursor are properly wrapped in .use {} blocks."
    
    workingDir = rootDir
    commandLine("bash", "scripts/check_use_blocks.sh")
}

afterEvaluate {
    tasks.named("check") {
        dependsOn("checkIoUseBlocks")
    }
}

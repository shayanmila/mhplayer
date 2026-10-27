plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "com.masoomdev.mhplayer"
 compileSdk = 36
 defaultConfig { applicationId = if (System.getenv("MH_CLONE_BUILD") == "true") "com.masoomdev.mhplayer.clone" else "com.masoomdev.mhplayer"; minSdk = 24; targetSdk = 36; versionCode = (System.getenv("MH_VERSION_CODE")?.toIntOrNull() ?: 27); versionName = "1.0.27" }
 val releaseStoreFile = System.getenv("MH_KEYSTORE_PATH")
 signingConfigs {
  if (!releaseStoreFile.isNullOrBlank()) {
   create("release") {
    storeFile = file(releaseStoreFile)
    storePassword = System.getenv("MH_KEYSTORE_PASSWORD")
    keyAlias = System.getenv("MH_KEY_ALIAS")
    keyPassword = System.getenv("MH_KEY_PASSWORD")
   }
  }
 }
 buildTypes { release {
  isMinifyEnabled = true
  isShrinkResources = true
  proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  if (!releaseStoreFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
 } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
 implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
 implementation("androidx.media3:media3-ui:1.5.1")
 implementation("androidx.media3:media3-common:1.5.1")
 implementation("androidx.media3:media3-session:1.5.1")
 implementation("com.google.android.play:app-update:2.1.0")
 implementation("androidx.fragment:fragment:1.8.9")
}
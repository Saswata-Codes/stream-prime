plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin)
}

android {
  namespace = "com.stream.prime"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.stream.prime"
    minSdk = 21  // Updated to Android 5.0 for better compatibility with modern features
    targetSdk = 36
    versionCode = libs.versions.versionCode.get().toInt()
    versionName = libs.versions.versionName.get()
    multiDexEnabled = true
    
    // NDK configuration for 16 KB page size support
    ndk {
      // Use the latest NDK version for 16 KB page size support
      version = "26.1.10909125"
    }
    
    // Android 16 compatibility flags
    manifestPlaceholders["android:extractNativeLibs"] = "true"
    manifestPlaceholders["android:appComponentFactory"] = "androidx.core.app.CoreComponentFactory"
  }
  
  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
    }
  }
  
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  
  kotlin {
    jvmToolchain(17)
  }
  
  buildFeatures {
    buildConfig = true
    viewBinding = true
  }
  
  lint {
    abortOnError = false
  }
  
  // Enhanced packaging configuration for 16 KB page size support
  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
    
    // Ensure native libraries are properly aligned
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
  
  // NDK build configuration
  ndkVersion = "26.1.10909125"
}

dependencies {
  implementation(project(":library"))
  implementation(project(":extra-sources"))
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.multidex)
  implementation("com.google.android.material:material:1.14.0")
  implementation("com.google.code.gson:gson:2.10.1")
  testImplementation(libs.junit)
}

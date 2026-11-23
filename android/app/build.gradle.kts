plugins {
    id("com.android.application")
}

android {
    namespace = "app.bittu"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.bittu"
        minSdk = 31 // direct call audio needs Android 12
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" } // one phone architecture keeps the app small
    }

    // The interface is the plain web folder next to this project. It is packed into the app as-is.
    sourceSets["main"].assets.srcDir("../../ui")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Pairs with the phone's own Wireless debugging, the way Shizuku does, so Bittu can start its helper without a cable.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1") // makes the certificate the pairing needs
    implementation("org.conscrypt:conscrypt-android:2.5.3") // the secure connection the pairing needs
}

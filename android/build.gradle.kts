plugins {
    id("com.android.application") version "9.5.0" apply false
    // Not applied anywhere: listing it pins the Kotlin compiler to 2.4.10, the version already downloaded on this PC.
    kotlin("plugin.compose") version "2.4.10" apply false
}

plugins {
    id("com.android.application") version "8.11.0" apply false
    // 2.2.21: the newest Kotlin line whose Gradle plugin lists AGP 8.11 and Gradle 8.14 as supported,
    // and the first that reads the kotlin-stdlib 2.2 metadata Media3 1.11 (sideload) pulls in.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}

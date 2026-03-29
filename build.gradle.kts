// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitty org, but org.jetbrains.kotlin:kotlin-gradle-plugin is needed
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:7.0.4")
        // Kotlin gradle plugin
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
        // CloudStream gradle plugin which makes everything work
        classpath("com.github.recloudstream.gradle:-SNAPSHOT")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun com.lagradost.cloudstream3.gradle.CloudstreamExtension.setRepo() {
    setRepo(rootProject.projectDir.absolutePath)
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}

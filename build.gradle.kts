plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// OneDrive locks build folders, so build outside it when tvLauncherBuildRoot is set
// in ~/.gradle/gradle.properties. GitHub builds do not set it and use the normal folder.
providers.gradleProperty("tvLauncherBuildRoot").orNull?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${rootProject.name}/${project.name}"))
    }
}

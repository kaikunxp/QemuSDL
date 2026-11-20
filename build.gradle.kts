// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

// Keep Gradle build outputs in a .nosync folder so macOS iCloud Drive never syncs
// or creates dataless conflict stubs inside build intermediates when hosted under ~/Documents.
subprojects {
  layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build.nosync/${project.name}"))
}

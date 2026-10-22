// Top-level build file. Plugins are declared here (but not applied) so that every
// module can enable them with `alias(libs.plugins.…)`.
//
// Note: AGP 9 ships built-in Kotlin support, so `org.jetbrains.kotlin.android` is
// deliberately absent. Serialization and Compose still need their own plugins.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
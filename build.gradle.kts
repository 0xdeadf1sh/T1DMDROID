// Versions declared once (apply false) so build-logic's convention plugins apply them by id.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

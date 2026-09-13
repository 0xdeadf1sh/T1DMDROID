import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

// Apply AFTER t1dm.android.application or t1dm.android.library.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

// Not CommonExtension: its generic arity is version-sensitive.
extensions.findByType(ApplicationExtension::class.java)?.apply {
    buildFeatures.compose = true
}
extensions.findByType(LibraryExtension::class.java)?.apply {
    buildFeatures.compose = true
}

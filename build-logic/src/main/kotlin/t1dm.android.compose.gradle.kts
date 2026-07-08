import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

// Apply AFTER t1dm.android.application or t1dm.android.library.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

// Enable the Compose build feature on whichever android extension this module has,
// without touching CommonExtension's version-sensitive generic arity.
extensions.findByType(ApplicationExtension::class.java)?.apply {
    buildFeatures.compose = true
}
extensions.findByType(LibraryExtension::class.java)?.apply {
    buildFeatures.compose = true
}

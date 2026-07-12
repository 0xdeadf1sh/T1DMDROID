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

// Pin androidx.compose.foundation to the Compose BOM's version (2024.12.01 → 1.7.6). Coil 3.x is a
// Compose-Multiplatform library and transitively requires org.jetbrains.compose.foundation:1.8.2,
// which relocates to androidx.compose.foundation and OUTRANKS the BOM — bumping the packaged
// foundation to 1.8.2 while every module still compiles against 1.7.6. FlowRow's overload signature
// differs between those releases, so 1.7.6-compiled call sites hit `NoSuchMethodError: FlowRow(…)`
// against the 1.8.2 foundation the instant a wrapping-chip FlowRow composes (Meals, Stats, several
// Settings sub-screens). Force the whole foundation group back to the BOM version so the Compose
// stack stays internally consistent; nothing about R8 / optimization is touched.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.compose.foundation") {
            useVersion("1.7.6")
            because("align androidx.compose.foundation with the Compose BOM (Coil3 pulls 1.8.2)")
        }
    }
}

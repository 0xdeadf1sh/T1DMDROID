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

// 1.7.6 is the Compose BOM's version. Coil 3.x pulls org.jetbrains.compose.foundation:1.8.2, which
// relocates to androidx and outranks the BOM; FlowRow's signature differs between the two, so
// modules compiled against 1.7.6 hit NoSuchMethodError at the first wrapping FlowRow.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.compose.foundation") {
            useVersion("1.7.6")
            because("align androidx.compose.foundation with the Compose BOM (Coil3 pulls 1.8.2)")
        }
    }
}

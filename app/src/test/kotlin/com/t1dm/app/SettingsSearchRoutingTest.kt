package com.t1dm.app

import com.t1dm.feature.settings.SettingsIndex
import com.t1dm.feature.settings.SettingsScreenKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Index knows only an opaque key, not routes; :app is where both halves are checked. */
class SettingsSearchRoutingTest {

    @Test
    fun `every index entry resolves to a route with a real breadcrumb`() {
        SettingsIndex.ALL.forEach { knob ->
            val route = settingsRouteFor(knob.screen)
            assertTrue("${knob.id}: empty route", route.isNotBlank())
            val crumbs = crumbsFor(route, null)
            // `crumbsFor` falls through to a single crumb labelled with the raw route.
            assertNotEquals(
                "${knob.id}: route \"$route\" has no breadcrumb entry",
                listOf(Crumb(route, null)),
                crumbs,
            )
        }
    }

    @Test
    fun `the advertised breadcrumb is the one the trail will render`() {
        SettingsScreenKey.entries.forEach { screen ->
            val rendered = crumbsFor(settingsRouteFor(screen), null)
                .map { it.label }
                .filterNot { it == "Settings" }
                .joinToString(" › ")
                .ifEmpty { "Settings" }
            assertEquals("$screen advertises a stale path", rendered, screen.breadcrumb)
        }
    }

    @Test
    fun `no settings destination is orphaned`() {
        val registered = registeredSettingsRoutes()
        assertTrue("found no composable() routes — did Navigation.kt move?", registered.size > 15)
        val indexed = SettingsScreenKey.entries.map { settingsRouteFor(it) }.toSet()
        assertEquals(
            "settings destinations reachable by hand but invisible to search",
            emptySet<String>(),
            registered - indexed,
        )
    }

    @Test
    fun `every mapped route is registered`() {
        val registered = registeredSettingsRoutes() + OFF_MODULE_ROUTES
        SettingsScreenKey.entries.forEach { screen ->
            val route = settingsRouteFor(screen)
            assertTrue("$screen maps to \"$route\", which no composable() registers", route in registered)
        }
    }

    @Test
    fun `screen keys map to distinct routes`() {
        val routes = SettingsScreenKey.entries.map { settingsRouteFor(it) }
        assertEquals("two screen keys share a route", routes.size, routes.distinct().size)
    }

    private companion object {
        /** Outside :feature:settings, missed by the prefix filter; listed explicitly. */
        val OFF_MODULE_ROUTES = setOf("models", "backup")
    }

    private fun registeredSettingsRoutes(): Set<String> {
        val nav = File(System.getProperty("user.dir"), "src/main/kotlin/com/t1dm/app/Navigation.kt")
        assertTrue("cannot find Navigation.kt at ${nav.absolutePath}", nav.isFile)
        return Regex("""composable\("([^"]+)"\)""")
            .findAll(nav.readText())
            .map { it.groupValues[1] }
            .filter { it == "settings" || it == "about" || it.startsWith("settings/") }
            .toSet()
    }
}

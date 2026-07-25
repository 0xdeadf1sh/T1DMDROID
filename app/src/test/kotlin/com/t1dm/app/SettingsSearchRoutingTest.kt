package com.t1dm.app

import com.t1dm.feature.settings.SettingsIndex
import com.t1dm.feature.settings.SettingsScreenKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:app`'s half of the settings search index: that every entry resolves to a route this NavHost
 * actually registers, that no settings destination is orphaned (reachable by hand but invisible to
 * search), and that the breadcrumb a result advertises is the one the trail renders on arrival.
 *
 * The index deliberately knows nothing of routes — `:feature:settings` names a screen by an opaque
 * key, exactly as theme ids, font ids and vibration presets cross that seam — so this is the only
 * place the two halves can be held against each other, and [crumbsFor] is the authority for both.
 */
class SettingsSearchRoutingTest {

    @Test
    fun `every index entry resolves to a route with a real breadcrumb`() {
        SettingsIndex.ALL.forEach { knob ->
            val route = settingsRouteFor(knob.screen)
            assertTrue("${knob.id}: empty route", route.isNotBlank())
            val crumbs = crumbsFor(route, null)
            // `crumbsFor` falls through to a single crumb whose label IS the raw route when it does
            // not recognise it — the exact silent failure a route-shaped focus argument would cause.
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
        val registered = registeredSettingsRoutes() + "models"
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

    /** The routes the NavHost actually registers, read from the source rather than assumed. */
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

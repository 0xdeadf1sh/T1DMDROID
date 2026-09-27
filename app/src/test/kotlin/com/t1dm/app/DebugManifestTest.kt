package com.t1dm.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The debug build is the one on the phone; an unguarded export there is open to every app. */
class DebugManifestTest {

    @Test
    fun `every exported debug component demands a permission only adb holds`() {
        val file = File(System.getProperty("user.dir"), "src/debug/AndroidManifest.xml")
        assertTrue("cannot find the debug manifest at ${file.absolutePath}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
        val exported = COMPONENTS.flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.filter { it.getAttributeNS(ANDROID_NS, "exported") == "true" }
        assertTrue("found no exported component — did the manifest move?", exported.isNotEmpty())
        for (component in exported) {
            assertEquals(
                component.getAttributeNS(ANDROID_NS, "name"),
                "android.permission.DUMP",
                component.getAttributeNS(ANDROID_NS, "permission"),
            )
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val COMPONENTS = listOf("activity", "activity-alias", "receiver", "service", "provider")
    }
}

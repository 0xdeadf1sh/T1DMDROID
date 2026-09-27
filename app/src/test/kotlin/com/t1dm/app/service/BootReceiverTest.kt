package com.t1dm.app.service

import android.content.Intent
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BootReceiverTest {

    @Test
    fun `boot, unlock and an app update resume monitoring`() {
        assertTrue(resumesMonitoring(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(resumesMonitoring(Intent.ACTION_USER_UNLOCKED))
        assertTrue(resumesMonitoring(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(resumesMonitoring(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFalse(resumesMonitoring(null))
    }

    @Test
    fun `every action the manifest routes to BootReceiver is handled`() {
        val actions = manifestActions(".service.BootReceiver")
        assertTrue(Intent.ACTION_MY_PACKAGE_REPLACED in actions)
        for (action in actions) {
            assertTrue(
                "$action reaches BootReceiver and is dropped",
                resumesMonitoring(action) || action == Intent.ACTION_LOCKED_BOOT_COMPLETED,
            )
        }
    }

    private fun manifestActions(receiver: String): List<String> {
        val manifest = File(System.getProperty("user.dir"), "src/main/AndroidManifest.xml")
        assertTrue("cannot find AndroidManifest.xml at ${manifest.absolutePath}", manifest.isFile)
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val receivers = doc.getElementsByTagName("receiver")
        val node = (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "name") == receiver }
        val actions = node.getElementsByTagName("action")
        return (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

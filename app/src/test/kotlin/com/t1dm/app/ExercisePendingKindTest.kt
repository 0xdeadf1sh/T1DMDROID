package com.t1dm.app

import com.t1dm.core.model.ExerciseKind
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * The kind Start holds while the location dialog is up.
 *
 * That dialog is where a low-memory kill lands, and `MainActivity` declares no `configChanges`, so a
 * dark-mode, font-scale or locale change recreates the Activity under it as well. The result registry
 * survives all of it and re-delivers the grant to the new instance — so the kind has to survive it too,
 * or the callback meets a null and starts nothing while saying nothing. That is why the route holds it
 * in `rememberSaveable`, whose default saver can store only what a `Bundle` takes: `Serializable`,
 * `Parcelable`, `String` and a short list of others.
 *
 * The recreation itself is not reachable from the host JVM — there is no Robolectric here and
 * `android.os.Bundle` is not mocked — so what is pinned instead is the property the saver leans on:
 * [ExerciseKind] is `Serializable`, and a round trip hands back the SAME constant, which is what keeps
 * the launcher's `when` matching on the far side. Swapped for a data class or a value class it would
 * still compile and would then throw the first time a bout was started through the dialog.
 */
class ExercisePendingKindTest {

    @Test
    fun `the kind is storable in saved instance state`() {
        assertTrue(
            "ExerciseKind is not Serializable, so rememberSaveable cannot hold it",
            Serializable::class.java.isAssignableFrom(ExerciseKind::class.java),
        )
    }

    @Test
    fun `a saved kind comes back as the same constant`() {
        for (kind in ExerciseKind.entries) {
            val bytes = ByteArrayOutputStream()
            ObjectOutputStream(bytes).use { it.writeObject(kind) }
            val back = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
            assertSame(kind, back)
        }
    }
}

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

/** The route holds the pending kind in `rememberSaveable` across Activity recreation, so
 *  [ExerciseKind] must stay `Serializable` and round-trip to the same constant. */
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

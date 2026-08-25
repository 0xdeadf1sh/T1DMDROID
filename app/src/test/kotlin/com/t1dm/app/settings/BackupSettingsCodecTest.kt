package com.t1dm.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSettingsCodecTest {

    @Test
    fun `every cadence stop round-trips`() {
        for (stop in SettingsStore.BACKUP_CADENCE_STOPS) {
            assertEquals(stop, SettingsStore.decodeBackupCadence(SettingsStore.encodeBackupCadence(stop)))
        }
    }

    @Test
    fun `a cadence between stops snaps to one rather than being honoured`() {
        // 20 h is nearer daily than 6-hourly; 4 h nearer 6 than off.
        assertEquals(24, SettingsStore.decodeBackupCadence(SettingsStore.encodeBackupCadence(20)))
        assertEquals(6, SettingsStore.decodeBackupCadence(SettingsStore.encodeBackupCadence(4)))
        assertEquals(24 * 7, SettingsStore.decodeBackupCadence(SettingsStore.encodeBackupCadence(1000)))
    }

    @Test
    fun `an unset or unreadable cadence falls back to off`() {
        // A phone never configured must not start writing backups to a folder nobody chose.
        assertEquals(SettingsStore.BACKUP_CADENCE_OFF, SettingsStore.decodeBackupCadence(null))
        assertEquals(SettingsStore.BACKUP_CADENCE_OFF, SettingsStore.decodeBackupCadence(""))
        assertEquals(SettingsStore.BACKUP_CADENCE_OFF, SettingsStore.decodeBackupCadence("hourly"))
        assertEquals(SettingsStore.BACKUP_CADENCE_OFF, SettingsStore.decodeBackupCadence("-1"))
        // A period no stepper offers: refused, not run.
        assertEquals(SettingsStore.BACKUP_CADENCE_OFF, SettingsStore.decodeBackupCadence("3"))
    }

    @Test
    fun `off is a cadence stop in its own right`() {
        assertEquals(
            SettingsStore.BACKUP_CADENCE_OFF,
            SettingsStore.decodeBackupCadence(SettingsStore.encodeBackupCadence(SettingsStore.BACKUP_CADENCE_OFF)),
        )
    }

    @Test
    fun `every retention stop round-trips and a strange one falls back`() {
        for (stop in SettingsStore.BACKUP_KEEP_STOPS) {
            assertEquals(stop, SettingsStore.decodeBackupKeep(SettingsStore.encodeBackupKeep(stop)))
        }
        assertEquals(SettingsStore.DEFAULT_BACKUP_KEEP, SettingsStore.decodeBackupKeep(null))
        assertEquals(SettingsStore.DEFAULT_BACKUP_KEEP, SettingsStore.decodeBackupKeep("nonsense"))
        // Zero would prune every backup the moment one was written.
        assertEquals(SettingsStore.DEFAULT_BACKUP_KEEP, SettingsStore.decodeBackupKeep("0"))
    }

    @Test
    fun `retention never encodes to zero however it is asked`() {
        for (asked in listOf(-5, 0, 1)) {
            val kept = SettingsStore.decodeBackupKeep(SettingsStore.encodeBackupKeep(asked))
            assertEquals("keep $asked would erase every backup", SettingsStore.BACKUP_KEEP_STOPS.first(), kept)
        }
    }

    @Test
    fun `a successful run round-trips`() {
        val back = SettingsStore.decodeBackupOk(SettingsStore.encodeBackupOk(1_712_345_400_000L, 1_234_567L, 105_120))
        assertEquals(1_712_345_400_000L, back!!.atMs)
        assertEquals(1_234_567L, back.bytes)
        assertEquals(105_120, back.rows)
    }

    @Test
    fun `a failed run round-trips with its message`() {
        val back = SettingsStore.decodeBackupError(SettingsStore.encodeBackupError(42L, "could not create a file"))
        assertEquals(42L, back!!.atMs)
        assertEquals("could not create a file", back.message)
    }

    @Test
    fun `an error message cannot forge a field boundary`() {
        // The separator is the row's only structure; a message carrying one could fabricate a timestamp.
        val encoded = SettingsStore.encodeBackupError(7L, "broke|at|17\nline two\rline three")
        assertEquals(1, encoded.count { it == '|' })
        val back = SettingsStore.decodeBackupError(encoded)!!
        assertEquals(7L, back.atMs)
        assertFalse(back.message.contains('\n'))
        assertFalse(back.message.contains('\r'))
    }

    @Test
    fun `a long error message is truncated rather than filling the row`() {
        val encoded = SettingsStore.encodeBackupError(1L, "x".repeat(5_000))
        assertEquals(true, encoded.length < 400)
    }

    @Test
    fun `an unreadable last-run row reads as no run at all`() {
        // A status panel must never be the thing that throws on a corrupt row.
        for (raw in listOf(null, "", "not-a-number", "1|2", "1|2|3|4", "abc|def|ghi")) {
            assertNull("decoded $raw", SettingsStore.decodeBackupOk(raw))
        }
        for (raw in listOf(null, "", "1", "1|", "nope|nope")) {
            assertNull("decoded $raw", SettingsStore.decodeBackupError(raw))
        }
    }

    @Test
    fun `no backup key is exportable`() {
        // A folder grant belongs to this install; on a fresh phone it is a URI with no permission.
        // The last-run rows are state, not configuration.
        for (key in listOf(
            SettingsStore.K_BACKUP_CADENCE_H,
            SettingsStore.K_BACKUP_KEEP,
            SettingsStore.K_BACKUP_TREE,
            SettingsStore.K_BACKUP_LABEL,
            SettingsStore.K_BACKUP_LAST_OK,
            SettingsStore.K_BACKUP_LAST_ERR,
        )) {
            assertFalse("$key is exportable", SettingsStore.isConfigKey(key))
        }
    }
}

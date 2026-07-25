package com.t1dm.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The hand-rolled **FTS5** full-text index over the `food` dictionary (Phase 4
 * deliverable 3 — "seed it into Room with FTS5 search"). Room has no `@Fts5` annotation, so the
 * virtual table is not a Room entity; it is an **external-content** FTS5 table (`content='food'`)
 * kept in lockstep with `food` by insert/update/delete triggers. Because it is Room-invisible, the
 * exact same DDL must run on BOTH code paths so a fresh install and a migrated install converge:
 *  - a fresh install: the [AppDatabase] `onCreate` callback (Room's `createAllTables` only builds
 *    entity tables);
 *  - an upgrade: [MigrationRunner.MIGRATION_4_5], which is where `food` itself arrives.
 *
 * The shadow tables (`food_fts_data`, `_idx`, `_docsize`, `_config`) are auto-created by SQLite and
 * are not tracked by Room — hence the v5 migration test relaxes `validateDroppedTables`.
 */
internal object FoodFts {

    /** Idempotent DDL: the FTS5 virtual table + the three sync triggers. */
    val DDL: List<String> = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `food_fts` USING fts5(" +
            "name, brand, content='food', content_rowid='id', tokenize='unicode61')",
        "CREATE TRIGGER IF NOT EXISTS `food_ai` AFTER INSERT ON `food` BEGIN " +
            "INSERT INTO food_fts(rowid, name, brand) VALUES (new.id, new.name, new.brand); END",
        "CREATE TRIGGER IF NOT EXISTS `food_ad` AFTER DELETE ON `food` BEGIN " +
            "INSERT INTO food_fts(food_fts, rowid, name, brand) VALUES('delete', old.id, old.name, old.brand); END",
        "CREATE TRIGGER IF NOT EXISTS `food_au` AFTER UPDATE ON `food` BEGIN " +
            "INSERT INTO food_fts(food_fts, rowid, name, brand) VALUES('delete', old.id, old.name, old.brand); " +
            "INSERT INTO food_fts(rowid, name, brand) VALUES (new.id, new.name, new.brand); END",
    )

    fun create(connection: SQLiteConnection) = DDL.forEach { connection.execSQL(it) }
}

package com.t1dm.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Room-invisible FTS5 over `food`; same DDL must run in [AppDatabase] onCreate and M4_5. */
internal object FoodFts {

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

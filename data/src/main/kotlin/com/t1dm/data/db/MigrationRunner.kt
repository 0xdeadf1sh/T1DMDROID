package com.t1dm.data.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The single registry of schema migrations (PLAN.private.md Phase 1). Keep-forever storage
 * FORBIDS destructive migration: every future change is a new [Migration] whose body is
 * append-only DDL — `ALTER TABLE … ADD COLUMN`, `CREATE TABLE`, `CREATE INDEX` — never a
 * `DROP`/`RENAME`-that-loses-data and never `fallbackToDestructiveMigration`.
 *
 * v1 is the genesis schema, so [ALL] is empty; each subsequent version appends exactly one
 * `Migration(n-1, n)` here and its exported `schemas/<db>/n.json` gates it in CI.
 */
object MigrationRunner {
    val ALL: Array<Migration> = emptyArray()

    /** Apply every registered migration to a builder; the sole path that wires migrations. */
    fun <T : RoomDatabase> configure(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> =
        builder.addMigrations(*ALL)
    // NOTE: no `.fallbackToDestructiveMigration(...)` — a missing migration must fail loudly,
    // never silently discard the user's keep-forever history.
}

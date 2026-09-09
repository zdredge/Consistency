package com.zdredge.consistency.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations, in order. Every one gets a test (`MigrationTest`).
 *
 * Migrations are hand-written and easy to get wrong, and getting one wrong on this app means either
 * a crash on open or, worse, history that survives in a subtly altered form. There is deliberately
 * no `fallbackToDestructiveMigration` to catch a mistake by wiping the database — for a product whose
 * entire value is an undeniable record, a crash is the better failure.
 */
val ALL_MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

/**
 * **v1 → v2: `items.ordinal`.**
 *
 * Nothing in the spec said what order questions are asked in, and with no ordinal the only stable
 * order was by id — which put "got out of bed at" before "woke at" in the morning check-in. Order
 * belongs in data rather than in a rule inferred from the item, so a question created later gets a
 * position and reordering never means editing code.
 *
 * An `ALTER TABLE ... ADD COLUMN` rather than the create-copy-drop-rename dance, because adding a
 * NOT NULL column with a default is one of the few things SQLite alters in place. The default is
 * what makes it safe: every row already in the database gets 0, so an existing install keeps all its
 * items and simply orders them arbitrarily until they are given positions.
 *
 * The `DEFAULT 0` here must match `@ColumnInfo(defaultValue = "0")` on the entity exactly, or Room's
 * post-migration validation fails — it compares the migrated schema against the compiled one column
 * for column, including defaults.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `items` ADD COLUMN `ordinal` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * **v2 → v3: `rollover_runs`.**
 *
 * The 04:00 job's record of its own executions. Architecture §8 rates a silently failing rollover as
 * high-impact and invisible, and asks for last-successful-rollover to be recorded and staleness
 * surfaced in the app; this is the table that makes that possible.
 *
 * A new table with no foreign keys, so nothing existing is read, rewritten or at risk — the whole
 * migration is one CREATE plus its index. That is worth stating because it is the *cheap* kind of
 * schema change, and the expensive kind (rebuilding a table to alter a constraint) is the one this
 * project has so far avoided.
 *
 * **The column definitions must match the compiled entity exactly** — types, nullability and the
 * index — or Room's post-migration validation fails on open. That validation is the reason the
 * generated SQL is copied from the exported schema rather than written from memory.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `rollover_runs` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `ran_at` INTEGER NOT NULL,
                `for_day` TEXT NOT NULL,
                `outcome` TEXT NOT NULL,
                `checkins_created` INTEGER NOT NULL,
                `checkins_missed` INTEGER NOT NULL,
                `values_frozen` INTEGER NOT NULL,
                `error` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_rollover_runs_ran_at` ON `rollover_runs` (`ran_at`)")
    }
}

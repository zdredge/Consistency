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
val ALL_MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2)

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

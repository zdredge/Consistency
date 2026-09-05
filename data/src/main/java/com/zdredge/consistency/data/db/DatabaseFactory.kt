package com.zdredge.consistency.data.db

import android.content.Context
import androidx.room.Room

/** The production database file. Named rather than inlined so the fixture and export can find it. */
const val DATABASE_NAME: String = "consistency.db"

/**
 * Builds the real, on-disk database.
 *
 * This exists so that Room stays an implementation detail of `:data`. Without it, `:app` would have
 * to call `Room.databaseBuilder` itself and would therefore need Room on its own compile classpath
 * -- which quietly turns the module boundary in architecture section 5 into a suggestion, and makes
 * it possible for a ViewModel to reach past the repository and hold a DAO.
 *
 * There is deliberately no `fallbackToDestructiveMigration`. It is the convenient default and it
 * silently deletes the user's history when a migration is missing -- for an app whose entire value
 * is an undeniable record, a crash is the better failure.
 */
fun createConsistencyDatabase(
    context: Context,
    name: String = DATABASE_NAME,
): ConsistencyDatabase =
    Room.databaseBuilder(context.applicationContext, ConsistencyDatabase::class.java, name)
        .addMigrations(*ALL_MIGRATIONS)
        .build()

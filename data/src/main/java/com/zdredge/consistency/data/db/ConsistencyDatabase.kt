package com.zdredge.consistency.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.zdredge.consistency.data.db.entity.ItemEntity

/**
 * The single Room database. Schema per docs/architecture.md section 5.
 *
 * `exportSchema` is left at its default of true and the JSON lands in `data/schemas/`, committed.
 * That export is not documentation: a Room migration test rebuilds the *old* schema from it, so
 * without it there is nothing to migrate from and a schema change becomes unverifiable after the
 * fact.
 */
@Database(
    entities = [
        ItemEntity::class,
    ],
    version = 1,
)
@TypeConverters(Converters::class)
abstract class ConsistencyDatabase : RoomDatabase()

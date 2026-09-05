package com.zdredge.consistency.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.zdredge.consistency.data.db.dao.AnswerDao
import com.zdredge.consistency.data.db.dao.CheckInDao
import com.zdredge.consistency.data.db.dao.ItemDao
import com.zdredge.consistency.data.db.dao.MeasuredDao
import com.zdredge.consistency.data.db.dao.TargetDao
import com.zdredge.consistency.data.db.entity.AnswerEntity
import com.zdredge.consistency.data.db.entity.AnswerSelectionEntity
import com.zdredge.consistency.data.db.entity.CheckInEntity
import com.zdredge.consistency.data.db.entity.ContainerSizeEntity
import com.zdredge.consistency.data.db.entity.ItemEntity
import com.zdredge.consistency.data.db.entity.ItemVersionEntity
import com.zdredge.consistency.data.db.entity.MeasuredOriginEntity
import com.zdredge.consistency.data.db.entity.MeasuredValueEntity
import com.zdredge.consistency.data.db.entity.RollUpSpecEntity
import com.zdredge.consistency.data.db.entity.SelectOptionEntity
import com.zdredge.consistency.data.db.entity.TargetEntity

/**
 * The single Room database. Schema per docs/architecture.md section 5.
 *
 * **v2 (M4)** added `items.ordinal`. See `Migrations.kt` for why it is an ALTER rather than a
 * rebuild, and `SchemaVersionPinTest` for the pins that make a version bump deliberate.
 *
 * `exportSchema` is left at its default of true and the JSON lands in `data/schemas/`, committed.
 * That export is not documentation: a Room migration test rebuilds the *old* schema from it, so
 * without it there is nothing to migrate from and a schema change becomes unverifiable after the
 * fact.
 */
@Database(
    entities = [
        ItemEntity::class,
        ItemVersionEntity::class,
        SelectOptionEntity::class,
        TargetEntity::class,
        ContainerSizeEntity::class,
        RollUpSpecEntity::class,
        CheckInEntity::class,
        AnswerEntity::class,
        AnswerSelectionEntity::class,
        MeasuredValueEntity::class,
        MeasuredOriginEntity::class,
    ],
    version = 2,
)
@TypeConverters(Converters::class)
abstract class ConsistencyDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

    abstract fun targetDao(): TargetDao

    abstract fun checkInDao(): CheckInDao

    abstract fun answerDao(): AnswerDao

    abstract fun measuredDao(): MeasuredDao
}

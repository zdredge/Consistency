package com.zdredge.consistency.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
)
@TypeConverters(Converters::class)
abstract class ConsistencyDatabase : RoomDatabase()

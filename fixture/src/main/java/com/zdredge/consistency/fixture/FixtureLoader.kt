package com.zdredge.consistency.fixture

import androidx.room.withTransaction
import com.zdredge.consistency.data.ROLLOVER_SUCCEEDED
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.data.db.LOCAL_USER_ID
import com.zdredge.consistency.data.db.entity.CheckInEntity
import com.zdredge.consistency.data.db.entity.RolloverRunEntity
import com.zdredge.consistency.data.mapper.originEntities
import com.zdredge.consistency.data.mapper.selectionEntities
import com.zdredge.consistency.data.mapper.toEntity
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Replaces a database's whole contents with a [FixtureDataset].
 *
 * **Straight to the tables, and only in the fixture install.** The repository has no "delete
 * everything" and must never gain one: the real app holds a record whose whole value is that it
 * cannot be quietly rewritten. This lives in `:fixture`, which only the fixture build links.
 *
 * Writes go down through `:data`'s own mappers -- the inverse of what the repository reads with -- so
 * a scenario read back through `ConsistencyRepository` is the dataset it was generated as. The
 * instrumented test proves exactly that round trip.
 *
 * Ids are derived from what makes a row unique (an item and a day, a day and a slot), so the same
 * scenario loads identically every time and a database dump is legible.
 */
class FixtureLoader(
    private val database: ConsistencyDatabase,
    private val dayResolver: DayResolver,
) {

    /** What a load wrote, so the screen reports evidence rather than "Done". */
    data class Loaded(val checkIns: Int, val answers: Int, val measuredValues: Int)

    suspend fun load(dataset: FixtureDataset): Loaded {
        // Room will not clear inside a transaction, so this is two steps. A load that dies between
        // them leaves an empty fixture database, which loading again repairs -- acceptable for a
        // test tool, and the reason this is not a pattern to copy anywhere near real data.
        withContext(Dispatchers.IO) { database.clearAllTables() }

        database.withTransaction {
            val items = database.itemDao()
            items.insertItems(dataset.items.map { it.toEntity(dayResolver) })
            items.insertVersions(dataset.versions.map { it.toEntity() })
            items.insertOptions(dataset.options.map { it.toEntity(dayResolver) })

            val targets = database.targetDao()
            targets.insertTargets(
                dataset.targets.map { it.toEntity("${it.itemId.value}.${it.period.name.lowercase()}.${it.effectiveFrom}") },
            )
            targets.insertContainerSizes(
                dataset.containerSizes.map { it.toEntity("${it.itemId.value}.size.${it.effectiveFrom}") },
            )
            targets.insertRollUpSpecs(dataset.rollUpSpecs.map { it.toEntity("${it.itemId.value}.rollup") })

            val checkInIds = dataset.checkIns.associate { (it.day to it.slot) to "${it.day}.${it.slot.name.lowercase()}" }
            database.checkInDao().insert(
                dataset.checkIns.map {
                    CheckInEntity(
                        id = checkInIds.getValue(it.day to it.slot),
                        userId = LOCAL_USER_ID,
                        dayDate = it.day,
                        slot = it.slot,
                        scheduledAt = it.scheduledAt,
                        state = it.state,
                        answeredAt = it.answeredAt,
                    )
                },
            )

            val answers = database.answerDao()
            dataset.answers.forEach { given ->
                val id = "${given.answer.itemId.value}.${given.answer.day}"
                answers.replace(
                    given.answer.toEntity(id, viaCheckInId = checkInIds[given.checkInDay to given.slot]),
                    given.answer.selectionEntities(id),
                )
            }

            val measured = database.measuredDao()
            dataset.measuredValues.forEach { value ->
                val id = "${value.itemId.value}.${value.day}"
                measured.upsertValue(value.toEntity(id))
                measured.replaceOrigins(id, value.originEntities(id))
            }

            // One successful run for today. Without it Home reports the rollover overdue on any
            // scenario longer than two days, which is true of the fixture and noise to the check.
            database.rolloverDao().insert(
                RolloverRunEntity(
                    id = "fixture.${dayResolver.today()}",
                    userId = LOCAL_USER_ID,
                    ranAt = dayResolver.now(),
                    forDay = dayResolver.today(),
                    outcome = ROLLOVER_SUCCEEDED,
                ),
            )
        }

        return Loaded(dataset.checkIns.size, dataset.answers.size, dataset.measuredValues.size)
    }
}

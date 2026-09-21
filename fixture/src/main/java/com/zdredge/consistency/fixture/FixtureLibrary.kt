package com.zdredge.consistency.fixture

import com.zdredge.consistency.data.SeedLibrary
import com.zdredge.consistency.data.mapper.toDomain
import com.zdredge.consistency.domain.model.ContainerSize
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.time.DayResolver
import java.time.LocalDate

/**
 * The real seed library, effective from a given day, as domain models.
 *
 * `SeedLibrary` itself rather than a copy: a fixture whose items drifted from the app's would verify
 * a library nobody uses. It is mapped up through the same mappers the repository reads with, so the
 * loader's write back down is the exact inverse.
 */
internal data class FixtureLibrary(
    val items: List<Item>,
    val versions: List<ItemVersion>,
    val options: List<SelectOption>,
    val targets: List<Target>,
    val containerSizes: List<ContainerSize>,
    val rollUpSpecs: List<RollUpSpec>,
) {
    companion object {
        fun effectiveFrom(on: LocalDate, dayResolver: DayResolver) = FixtureLibrary(
            items = SeedLibrary.items(dayResolver.startOfDay(on)).map { it.toDomain(dayResolver) },
            versions = SeedLibrary.versions(on).map { it.toDomain() },
            options = SeedLibrary.options().map { it.toDomain(dayResolver) },
            targets = SeedLibrary.targets(on).map { it.toDomain() },
            containerSizes = SeedLibrary.containerSizes(on).map { it.toDomain() },
            rollUpSpecs = SeedLibrary.rollUpSpecs().map { it.toDomain() },
        )
    }
}

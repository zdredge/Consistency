package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.scoring.ItemLifecycle
import com.zdredge.consistency.domain.scoring.effectiveOn
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One question in a check-in, with everything the screen needs to render it and nothing more.
 *
 * [carriedOverFrom] is set when this is a deferral being resolved rather than a fresh question. The
 * screen must label it as belonging to that earlier day, which is the same requirement the morning
 * check-in already has for the sleep items — spec §3.1 and §5.6 note that one mechanism serves both.
 *
 * [readOnly] marks a measured item: steps appears in the night check-in so the user sees it in the
 * moment, but is never asked (spec §3.3).
 */
data class CheckInEntry(
    val item: Item,
    val version: ItemVersion,
    val carriedOverFrom: LocalDate? = null,
    val readOnly: Boolean = false,
) {
    val isCarriedOver: Boolean get() = carriedOverFrom != null
}

/**
 * Which questions a check-in asks.
 *
 * The rules are small individually and easy to get subtly wrong together, which is why they are here
 * rather than in a ViewModel: asking an item that was retired last month, or silently dropping a
 * deferral the user was promised would come back, are both quiet failures rather than crashes.
 *
 * **Activity is judged against the day being answered, not the day of the check-in.** They differ
 * for the morning check-in, which writes to yesterday (spec §3.1). An item retired yesterday should
 * still be asked this morning about yesterday; an item created this morning should not.
 */
object CheckInContent {

    /**
     * The questions for the [slot] check-in held on [checkInDay].
     *
     * [deferrals] are unresolved "not yet" answers from earlier check-ins. They are carried into the
     * next morning and no further: the promise made by offering "not yet" is that the question comes
     * back *once*, and an unresolved deferral converts to a missed goal at rollover
     * (scoring-cases A2.1) rather than reappearing indefinitely.
     */
    fun forCheckIn(
        checkInDay: LocalDate,
        slot: Slot,
        items: List<Item>,
        versions: List<ItemVersion>,
        deferrals: List<Answer> = emptyList(),
    ): List<CheckInEntry> {
        val answersDay = AnswerDay.forCheckIn(checkInDay, slot)
        val byItem = items.associateBy { it.id }

        val asked = items
            .filter { it.kind == ItemKind.ASKED }
            .filter { ItemLifecycle.isActiveOn(it, answersDay) }
            .mapNotNull { item ->
                val version = versions.versionFor(item, answersDay) ?: return@mapNotNull null
                if (version.slot !in slotsAskedIn(slot, checkInDay)) return@mapNotNull null
                CheckInEntry(item, version)
            }

        // Measured items are shown, never asked, and only alongside the day they measure.
        val measured = if (slot == Slot.NIGHT) {
            items
                .filter { it.kind == ItemKind.MEASURED && ItemLifecycle.isActiveOn(it, answersDay) }
                .mapNotNull { item ->
                    versions.versionFor(item, answersDay)?.let { CheckInEntry(item, it, readOnly = true) }
                }
        } else {
            emptyList()
        }

        val carried = if (slot == Slot.MORNING) {
            deferrals
                .filter { it.capture == Capture.PENDING }
                .filter { it.day == answersDay }
                .mapNotNull { deferral ->
                    val item = byItem[deferral.itemId] ?: return@mapNotNull null
                    val version = versions.firstOrNull { it.id == deferral.itemVersionId }
                        ?: return@mapNotNull null
                    CheckInEntry(item, version, carriedOverFrom = deferral.day)
                }
        } else {
            emptyList()
        }

        // Carried-over questions lead: they are the ones the user was promised would come back, and
        // burying them under fresh questions is how a deferral quietly becomes a miss.
        return (carried + asked + measured).sortedWith(
            compareByDescending<CheckInEntry> { it.isCarriedOver }.thenBy { it.item.ordinal },
        )
    }

    /**
     * Which item slots this check-in covers.
     *
     * The weekly questions are appended to **Sunday night's** check-in rather than forming their own
     * (spec §1), which closes the Monday–Sunday week the moment it ends.
     */
    private fun slotsAskedIn(slot: Slot, checkInDay: LocalDate): Set<Slot> = when {
        slot == Slot.NIGHT && checkInDay.dayOfWeek == DayOfWeek.SUNDAY -> setOf(Slot.NIGHT, Slot.WEEKLY)
        else -> setOf(slot)
    }

    /**
     * The version in force on the day being answered — never simply the latest. Spec constraint 6:
     * rewording a question must not retroactively change what an older answer meant, and backfilling
     * yesterday must ask yesterday's wording.
     */
    private fun List<ItemVersion>.versionFor(item: Item, on: LocalDate): ItemVersion? =
        filter { it.itemId == item.id }.effectiveOn(on) { it.effectiveFrom }
}

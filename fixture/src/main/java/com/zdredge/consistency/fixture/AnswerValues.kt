package com.zdredge.consistency.fixture

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.SelectOption
import java.time.LocalTime
import kotlin.random.Random

/**
 * Plausible values for the seeded items, falling back on the answer type for anything else.
 *
 * Mostly-kept habits with real misses, because a history of perfect answers would put every goal at
 * 100% and exercise nothing, and a random one would put every goal near 50%.
 */
internal object AnswerValues {

    fun fill(base: Answer, type: AnswerType, options: List<SelectOption>, random: Random): Answer =
        when (base.itemId.value) {
            "bedtime" -> base.copy(valueTime = LocalTime.of(22, 15).plusMinutes(random.nextLong(0, 150)))
            "woke_at" -> base.copy(valueTime = LocalTime.of(6, 30).plusMinutes(random.nextLong(0, 60)))
            "got_up_at" -> base.copy(valueTime = LocalTime.of(7, 30).plusMinutes(random.nextLong(0, 45)))
            "meals" -> base.copy(valueNumber = weighted(random, 2.0 to 0.2, 3.0 to 0.65, 4.0 to 0.15))
            "water" -> base.copy(valueNumber = weighted(random, 1.0 to 0.2, 2.0 to 0.5, 3.0 to 0.3))
            "coffee" -> base.copy(valueNumber = weighted(random, 0.0 to 0.1, 1.0 to 0.35, 2.0 to 0.4, 3.0 to 0.15))
            "vitamins" -> base.copy(valueBool = random.nextDouble() < 0.8)
            "worked_out" -> base.copy(valueBool = random.nextDouble() < 0.45)
            "stretched" -> base.copy(valueBool = random.nextDouble() < 0.75)
            "mindset" -> base.copy(valueScale = weighted(random, 2 to 0.1, 3 to 0.35, 4 to 0.4, 5 to 0.15))
            else -> byType(base, type, options.sortedBy { it.ordinal }, random)
        }

    /**
     * The answer with its value changed the way a correction changes it, or null for a select, whose
     * correction would need a second option and proves nothing the numeric ones do not.
     */
    fun corrected(answer: Answer): Answer? = when {
        answer.valueNumber != null -> answer.copy(valueNumber = answer.valueNumber!! + 1)
        answer.valueBool != null -> answer.copy(valueBool = !answer.valueBool!!)
        answer.valueScale != null -> answer.copy(valueScale = if (answer.valueScale!! >= 5) 4 else answer.valueScale!! + 1)
        answer.valueTime != null -> answer.copy(valueTime = answer.valueTime!!.plusMinutes(15))
        else -> null
    }

    private fun byType(base: Answer, type: AnswerType, options: List<SelectOption>, random: Random): Answer =
        when (type) {
            AnswerType.BOOL -> base.copy(valueBool = random.nextBoolean())
            AnswerType.NUMBER -> base.copy(valueNumber = random.nextInt(0, 5).toDouble())
            AnswerType.TIME -> base.copy(valueTime = LocalTime.of(12, 0))
            AnswerType.SCALE -> base.copy(valueScale = random.nextInt(1, 6))
            AnswerType.SINGLE_SELECT -> {
                // Yes most of the time and no-opportunity rarely: a history that is mostly neutral
                // answers would exercise the exclusion rule and nothing else.
                val yes = options.firstOrNull { it.id.value.endsWith(".yes") }
                val noOpportunity = options.firstOrNull { it.isNoOpportunity }
                val other = options.firstOrNull { it != yes && !it.isNoOpportunity }
                val roll = random.nextDouble()
                val pick = when {
                    roll < 0.65 && yes != null -> yes
                    roll < 0.75 && noOpportunity != null -> noOpportunity
                    else -> other ?: options.first()
                }
                base.copy(selections = setOf(pick.id))
            }
            AnswerType.MULTI_SELECT -> base.copy(
                selections = options.shuffled(random).take(random.nextInt(1, 3)).map { it.id }.toSet(),
            )
        }

    private fun <T> weighted(random: Random, vararg choices: Pair<T, Double>): T {
        var roll = random.nextDouble() * choices.sumOf { it.second }
        for ((value, weight) in choices) {
            roll -= weight
            if (roll < 0) return value
        }
        return choices.last().first
    }
}

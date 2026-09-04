package com.zdredge.consistency.domain.model

/**
 * Typed ids. These are value classes rather than raw Strings because a target carries BOTH an item
 * id and (for include/exclude directions) an option id, and answers carry option ids too. Swapping
 * one for the other would compile fine as Strings and produce a plausible wrong score -- exactly the
 * silent-failure mode this module exists to prevent (docs/architecture.md section 4).
 */
@JvmInline
value class ItemId(val value: String)

@JvmInline
value class OptionId(val value: String)

@JvmInline
value class ItemVersionId(val value: String)

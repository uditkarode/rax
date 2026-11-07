package de.uditkaro.rax.rex

import kotlinx.serialization.json.Json

/** Lenient where Rex may grow: unknown fields are skipped and unknown enum strings stay strings. */
val RexJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

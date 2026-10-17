package com.designprototype.workshop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.time.LocalDate

/**
 * `CalendarField` — the ONE date control on the handset. The web's twin is `CalendarField`
 * (frontend/components/ui/CalendarField.tsx).
 *
 * It is [FieldDateField] (ui/DateTimeFields.kt) — type dd/mm/yyyy or open the themed calendar, min/max
 * greyed out in the grid and refused with a reason when typed — with Clear ON by default, which every
 * optional date wanted and few call sites remembered. The value is a LOCAL [LocalDate]; on the wire it
 * is the `yyyy-mm-dd` [toIsoLocalDate] writes, never a UTC instant (the web's `parseDateInput` rule).
 */
@Composable
fun CalendarField(
    label: String,
    value: LocalDate?,
    onValueChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    minimum: LocalDate? = null,
    maximum: LocalDate? = null,
    clearable: Boolean = true,
    supportingText: String? = null,
    /** What the empty box says — "Any date" on a filter, "No due date" on an optional deadline. */
    placeholder: String = FIELD_DATE_HINT,
) {
    FieldDateField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        minimum = minimum,
        maximum = maximum,
        clearable = clearable,
        supportingText = supportingText,
    )
}

/** `yyyy-mm-dd`, from the LOCAL calendar day — what the API stores for a date-only field. */
fun toIsoLocalDate(date: LocalDate?): String = date?.toString().orEmpty()

/** A `yyyy-mm-dd` (or the start of an ISO instant) back to a LOCAL day; null when it is not one. */
fun fromIsoLocalDate(text: String?): LocalDate? =
    text?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

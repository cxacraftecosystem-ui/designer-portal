package com.designprototype.workshop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.time.LocalDate

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

fun toIsoLocalDate(date: LocalDate?): String = date?.toString().orEmpty()

fun fromIsoLocalDate(text: String?): LocalDate? =
    text?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

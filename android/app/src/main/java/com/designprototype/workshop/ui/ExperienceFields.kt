package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val NOT_STATED: String = "Not stated"

internal fun experienceOptions(range: IntRange, current: String): List<SelectOption> {
    val rows = range.map { SelectOption(it.toString(), it.toString()) }
    val kept = current.trim()
    return if (kept.isNotEmpty() && rows.none { it.value == kept }) {
        listOf(SelectOption(kept, kept)) + rows
    } else {
        rows
    }
}

@Composable
fun ExperienceFields(
    years: String,
    months: String,
    enabled: Boolean,
    maxYears: Int,
    onYearsChange: (String) -> Unit,
    onMonthsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Experience",
    help: String? = DEFAULT_EXPERIENCE_HELP,
    error: String? = null,
) {
    val yearOptions = remember(years, maxYears) { experienceOptions(0..maxYears, years) }
    val monthOptions = remember(months) { experienceOptions(0..MAX_EXPERIENCE_MONTHS, months) }
    val stacked = LocalDensity.current.fontScale > 1.3f

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { heading() }
        )
        val yearsField = @Composable { fieldModifier: Modifier ->
            SearchableSelectField(
                label = "Years",
                options = yearOptions,
                selectedValue = years,
                enabled = enabled,
                placeholder = NOT_STATED,
                onSelect = onYearsChange,
                modifier = fieldModifier
            )
        }
        val monthsField = @Composable { fieldModifier: Modifier ->
            SearchableSelectField(
                label = "Months",
                options = monthOptions,
                selectedValue = months,
                enabled = enabled,
                placeholder = NOT_STATED,
                onSelect = onMonthsChange,
                modifier = fieldModifier
            )
        }
        if (stacked) {
            yearsField(Modifier.fillMaxWidth())
            monthsField(Modifier.fillMaxWidth())
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                yearsField(Modifier.weight(1f))
                monthsField(Modifier.weight(1f))
            }
        }
        help?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

const val MAX_EXPERIENCE_MONTHS: Int = 11

const val DEFAULT_EXPERIENCE_HELP: String =
    "Years and months. Use \" " + NOT_STATED + "\" to skip — that differs from 0."

package com.designprototype.workshop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

const val DW_REQUIRED_MARK: String = " *"

fun dwWithoutRequiredMark(label: String): String = label.removeSuffix(DW_REQUIRED_MARK)

@Composable
fun requiredMarked(label: String): AnnotatedString {
    val markColor = MaterialTheme.colorScheme.error
    return dwRequiredMarked(label, markColor)
}

fun dwRequiredMarked(label: String, markColor: Color): AnnotatedString {
    val stem = label.removeSuffix(DW_REQUIRED_MARK)
    if (stem.length == label.length) return AnnotatedString(label)
    return buildAnnotatedString {
        append(stem)
        withStyle(SpanStyle(color = markColor)) { append(DW_REQUIRED_MARK) }
    }
}

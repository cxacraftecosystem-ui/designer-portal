package com.designprototype.workshop.explorer.portal

import androidx.compose.runtime.Composable
import com.designprototype.workshop.ui.DesignWorkshopTheme

@Composable
fun DesignTheme(dark: Boolean, content: @Composable () -> Unit) = DesignWorkshopTheme(darkTheme = dark, content = content)

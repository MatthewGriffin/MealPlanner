package com.example.chatgpt_mealplanner.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.ui.theme.AppThemeState

/** Choose the app's colours: the standard palette, or one colour of your own (plus an optional background). */
@Composable
fun AppearanceSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(4.dp))
        ChoiceDropdown(
            label = "Colours",
            options = listOf("Pantry Pal standard", "My colours"),
            selectedIndex = if (AppThemeState.useCustom) 1 else 0,
            onSelect = { AppThemeState.chooseCustom(it == 1) }
        )
        if (AppThemeState.useCustom) {
            ColourPicker("Main colour", AppThemeState.accent) { AppThemeState.chooseAccent(it) }
            Text(
                "Text, buttons and highlights lighten or darken on their own so they stay easy to read.",
                style = MaterialTheme.typography.bodySmall
            )
            ChoiceDropdown(
                label = "Background",
                options = listOf("Automatic (matches the main colour)", "Choose my own"),
                selectedIndex = if (AppThemeState.background == null) 0 else 1,
                onSelect = { AppThemeState.chooseBackground(if (it == 0) null else (AppThemeState.background ?: 0xFFFFF4E0.toInt())) }
            )
            AppThemeState.background?.let { bg -> ColourPicker("Background colour", bg) { AppThemeState.chooseBackground(it) } }
            TextButton(onClick = { AppThemeState.reset() }) { Text("Back to standard colours") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceDropdown(label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options[selectedIndex],
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(index); expanded = false })
            }
        }
    }
}

/** Hue, saturation and brightness sliders with a live swatch. */
@Composable
fun ColourPicker(label: String, argb: Int, onChange: (Int) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var hue by remember { mutableStateOf(start[0]) }
    var sat by remember { mutableStateOf(start[1]) }
    var value by remember { mutableStateOf(start[2]) }
    fun push() = onChange(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    val current = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun hsv(h: Float, sa: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sa, v)))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(Color(current), RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            )
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text("#%06X".format(current and 0xFFFFFF), style = MaterialTheme.typography.bodySmall)
            }
        }
        SliderRow("Colour", hue, 0f..360f, (0..6).map { hsv(it * 60f, 1f, 1f) }) { hue = it; push() }
        SliderRow("Strength", sat, 0f..1f, listOf(hsv(hue, 0f, value), hsv(hue, 1f, value))) { sat = it; push() }
        SliderRow("Lightness", value, 0f..1f, listOf(Color.Black, hsv(hue, sat, 1f))) { value = it; push() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(name: String, value: Float, range: ClosedFloatingPointRange<Float>, track: List<Color>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.size(width = 72.dp, height = 20.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            // The coloured strip is the track, so it shows what moving the control does.
            track = {
                Box(
                    Modifier.fillMaxWidth().height(14.dp)
                        .background(Brush.horizontalGradient(track), RoundedCornerShape(7.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(7.dp))
                )
            }
        )
    }
}

package com.serein.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.serein.reader.data.ReadingMode
import com.serein.reader.data.ReaderFont
import com.serein.reader.data.ReaderPreferences
import com.serein.reader.data.ReaderOrientation
import com.serein.reader.data.ReaderTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(
    preferences: ReaderPreferences,
    onDismiss: () -> Unit,
    onChange: ((ReaderPreferences) -> ReaderPreferences) -> Unit,
    inline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSereinPalette.current
    var fontMenuOpen by remember { mutableStateOf(false) }
    var orientationMenuOpen by remember { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp)
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(4.dp))
                    .background(palette.mutedInk.copy(alpha = 0.4f))
            )
            SettingsLabel("Appearance")
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = Color.Transparent, border = BorderStroke(1.dp, palette.line),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    ReaderTheme.entries.forEach { theme ->
                        ThemeChoice(
                            theme, theme == preferences.theme,
                            { onChange { it.copy(theme = theme) } }, Modifier.weight(1f),
                        )
                    }
                }
            }
            SettingsDivider()
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsLabel("Reading style", Modifier.weight(1f))
                ReadingMode.entries.forEach { mode ->
                    Surface(
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(12.dp))
                            .clickable { onChange { it.copy(readingMode = mode) } },
                        color = if (preferences.readingMode == mode) palette.selectedControl else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            if (mode == ReadingMode.PAGED) "Pages" else "Scroll",
                            color = if (preferences.readingMode == mode) palette.sage else palette.mutedInk,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            SettingsDivider()
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsLabel("Font", Modifier.weight(1f))
                Box {
                    Row(
                        modifier = Modifier.clickable { fontMenuOpen = true }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(preferences.font.label, fontFamily = preferences.font.toFontFamily(), fontSize = 16.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.size(19.dp))
                    }
                    DropdownMenu(fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                        ReaderFont.entries.forEach { font ->
                            DropdownMenuItem(
                                text = { Text(font.label, fontFamily = font.toFontFamily()) },
                                onClick = { onChange { it.copy(font = font) }; fontMenuOpen = false },
                            )
                        }
                    }
                }
            }
            SettingsDivider()
            SettingStepper(
                "Text size", preferences.textSize.toString(), preferences.textSize > 14,
                preferences.textSize < 30,
                { onChange { it.copy(textSize = (it.textSize - 1).coerceAtLeast(14)) } },
                { onChange { it.copy(textSize = (it.textSize + 1).coerceAtMost(30)) } },
            )
            SettingsDivider()
            SettingStepper(
                "Line spacing", String.format(Locale.ROOT, "%.1f", preferences.lineHeight), preferences.lineHeight > 1.3f,
                preferences.lineHeight < 2f,
                { onChange { it.copy(lineHeight = (it.lineHeight - 0.1f).coerceAtLeast(1.3f)) } },
                { onChange { it.copy(lineHeight = (it.lineHeight + 0.1f).coerceAtMost(2f)) } },
            )
            SettingsDivider()
            SettingStepper(
                "Margins", "${preferences.marginWidth} dp", preferences.marginWidth > 16,
                preferences.marginWidth < 48,
                { onChange { it.copy(marginWidth = (it.marginWidth - 4).coerceAtLeast(16)) } },
                { onChange { it.copy(marginWidth = (it.marginWidth + 4).coerceAtMost(48)) } },
            )
            SettingsDivider()
            SettingsSwitch(
                label = "Justify text",
                checked = preferences.justifyText,
                onCheckedChange = { enabled -> onChange { it.copy(justifyText = enabled) } },
            )
            SettingsDivider()
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsLabel("Bionic Reading", Modifier.weight(1f))
                Text("50%", color = palette.mutedInk, fontSize = 12.sp)
                Switch(
                    checked = preferences.bionicReading, modifier = Modifier.scale(0.82f),
                    onCheckedChange = { enabled -> onChange { it.copy(bionicReading = enabled) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.sheet, checkedTrackColor = palette.sage,
                        uncheckedThumbColor = palette.mutedInk, uncheckedTrackColor = palette.control,
                    ),
                )
            }
            SettingsDivider()
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsLabel("Brightness", Modifier.weight(1f))
                TextButton(onClick = { onChange { it.copy(brightness = -1f) } }) {
                    Text(if (preferences.brightness < 0f) "System" else "Reset", fontSize = 12.sp)
                }
                Slider(
                    value = if (preferences.brightness < 0f) 0.5f else preferences.brightness,
                    onValueChange = { value -> onChange { it.copy(brightness = value.coerceIn(0.05f, 1f)) } },
                    valueRange = 0.05f..1f,
                    modifier = Modifier.width(112.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = palette.sage,
                        activeTrackColor = palette.sage,
                        inactiveTrackColor = palette.line,
                    ),
                )
            }
            SettingsDivider()
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsLabel("Orientation", Modifier.weight(1f))
                Box {
                    TextButton(onClick = { orientationMenuOpen = true }) {
                        Text(preferences.orientation.label, fontSize = 12.sp)
                    }
                    DropdownMenu(orientationMenuOpen, onDismissRequest = { orientationMenuOpen = false }) {
                        ReaderOrientation.entries.forEach { orientation ->
                            DropdownMenuItem(
                                text = { Text(orientation.label) },
                                onClick = {
                                    orientationMenuOpen = false
                                    onChange { it.copy(orientation = orientation) }
                                },
                            )
                        }
                    }
                }
            }
            SettingsDivider()
            SettingsSwitch(
                label = "Volume button turns",
                checked = preferences.volumePageTurns,
                onCheckedChange = { enabled -> onChange { it.copy(volumePageTurns = enabled) } },
            )
            SettingsDivider()
            SettingsSwitch(
                label = "Wide tap zones",
                checked = preferences.wideTapZones,
                onCheckedChange = { enabled -> onChange { it.copy(wideTapZones = enabled) } },
            )
            SettingsDivider()
            SettingsSwitch(
                label = "Keep screen awake",
                checked = preferences.keepScreenAwake,
                onCheckedChange = { enabled -> onChange { it.copy(keepScreenAwake = enabled) } },
            )
        }
    }
    if (inline) {
        Surface(
            modifier = modifier.fillMaxWidth(), color = palette.sheet, contentColor = palette.ink,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), shadowElevation = 18.dp,
        ) { content() }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss, containerColor = palette.sheet, contentColor = palette.ink,
            dragHandle = null, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            scrimColor = palette.ink.copy(alpha = 0.08f),
        ) { content() }
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val palette = LocalSereinPalette.current
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsLabel(label, Modifier.weight(1f))
        Switch(
            checked = checked,
            modifier = Modifier.scale(0.82f),
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.sheet,
                checkedTrackColor = palette.sage,
                uncheckedThumbColor = palette.mutedInk,
                uncheckedTrackColor = palette.control,
            ),
        )
    }
}

@Composable
private fun SettingStepper(
    label: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsLabel(label, Modifier.weight(1f))
        IconButton(onClick = onDecrease, enabled = canDecrease) { Icon(Icons.Rounded.Remove, "Decrease $label") }
        Text(value, fontFamily = LiterataFamily, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 5.dp))
        IconButton(onClick = onIncrease, enabled = canIncrease) { Icon(Icons.Rounded.Add, "Increase $label") }
    }
}

@Composable
private fun SettingsLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalSereinPalette.current.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(vertical = 3.dp), color = LocalSereinPalette.current.line)
}

@Composable
private fun ThemeChoice(
    theme: ReaderTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSereinPalette.current
    val icon: ImageVector = when (theme) {
        ReaderTheme.LIGHT -> Icons.Outlined.LightMode
        ReaderTheme.SEPIA -> Icons.Outlined.Tonality
        ReaderTheme.DARK -> Icons.Outlined.DarkMode
    }
    Column(
        modifier = modifier.background(if (selected) palette.selectedControl else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) palette.sage else palette.ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                theme.name.lowercase().replaceFirstChar(Char::uppercase),
                color = if (selected) palette.sage else palette.ink, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

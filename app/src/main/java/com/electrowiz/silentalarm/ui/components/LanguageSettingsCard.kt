package com.electrowiz.silentalarm.ui.components

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.electrowiz.silentalarm.R

/**
 * In-app language selector backed by the system per-app locale APIs.
 */
@Composable
fun LanguageSettingsCard(modifier: Modifier = Modifier) {
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val currentTag = currentLocales.get(0)?.toLanguageTag() ?: ""
    val tags = stringArrayResource(R.array.language_tags)
    val names = stringArrayResource(R.array.language_names)
    val options = tags.zip(names).map { (tag, name) -> LanguageOption(tag, name) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.language_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            options.forEach { option ->
                val selected = currentTag == option.tag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = { applyLanguage(option.tag) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private data class LanguageOption(val tag: String, val label: String)

private fun applyLanguage(tag: String) {
    val locales = if (tag.isEmpty()) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}

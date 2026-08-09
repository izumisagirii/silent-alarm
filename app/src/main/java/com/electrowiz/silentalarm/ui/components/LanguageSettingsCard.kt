package com.electrowiz.silentalarm.ui.components

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.electrowiz.silentalarm.R

private data class LanguageOption(val tag: String, val displayName: String)

/*
 * Curated display names keep the language list readable and consistent on
 * every Android system locale.
 */
private val supportedLanguages = listOf(
    LanguageOption("en", "English"),
    LanguageOption("zh-CN", "简体中文"),
    LanguageOption("zh-TW", "繁體中文"),
    LanguageOption("es", "Español"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("pt", "Português"),
    LanguageOption("ru", "Русский"),
    LanguageOption("ja", "日本語"),
    LanguageOption("ko", "한국어"),
    LanguageOption("it", "Italiano"),
    LanguageOption("ar", "العربية"),
    LanguageOption("hi", "हिन्दी"),
    LanguageOption("in", "Bahasa Indonesia"),
    LanguageOption("vi", "Tiếng Việt"),
    LanguageOption("tr", "Türkçe"),
    LanguageOption("pl", "Polski"),
    LanguageOption("nl", "Nederlands"),
    LanguageOption("th", "ไทย"),
    LanguageOption("uk", "Українська"),
    LanguageOption("sv", "Svenska"),
    LanguageOption("ms", "Bahasa Melayu"),
    LanguageOption("fil", "Filipino"),
    LanguageOption("cs", "Čeština"),
    LanguageOption("ro", "Română"),
    LanguageOption("hu", "Magyar"),
    LanguageOption("el", "Ελληνικά"),
    LanguageOption("iw", "עברית"),
    LanguageOption("da", "Dansk"),
    LanguageOption("nb", "Norsk"),
    LanguageOption("fi", "Suomi"),
    LanguageOption("bn", "বাংলা"),
    LanguageOption("ta", "தமிழ்"),
    LanguageOption("fa", "فارسی")
)

private fun displayNameFor(tag: String): String? {
    val normalized = when (tag.lowercase()) {
        "id" -> "in"
        "he" -> "iw"
        "zh-hans" -> "zh-CN"
        "zh-hant" -> "zh-TW"
        else -> tag
    }
    return supportedLanguages.find { it.tag == normalized }?.displayName
}

private fun applyLanguage(tag: String) {
    val locales = if (tag.isEmpty()) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}

/**
 * Language selector card. Opens an MD3 bottom sheet with search and a
 * scrollable list so a large number of supported languages stays manageable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsCard(modifier: Modifier = Modifier) {
    val followSystemLabel = stringResource(R.string.follow_system)
    val searchLabel = stringResource(R.string.search_languages)
    val noResultsLabel = stringResource(R.string.no_languages_found)
    val currentTag = AppCompatDelegate.getApplicationLocales()
        .get(0)?.toLanguageTag() ?: ""
    val selectedLabel = displayNameFor(currentTag) ?: followSystemLabel
    var showSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredLanguages = remember(searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            supportedLanguages
        } else {
            supportedLanguages.filter {
                it.displayName.contains(query, ignoreCase = true)
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsCardHeader(
                icon = Icons.Outlined.Translate,
                title = stringResource(R.string.language_title)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        searchQuery = ""
                        showSheet = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = selectedLabel,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(R.string.language_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel)
                                )
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = { Text(searchLabel) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    item(key = "follow-system") {
                        LanguageSheetRow(
                            label = followSystemLabel,
                            selected = currentTag.isEmpty(),
                            onClick = {
                                showSheet = false
                                applyLanguage("")
                            }
                        )
                    }
                    items(filteredLanguages, key = { it.tag }) { option ->
                        LanguageSheetRow(
                            label = option.displayName,
                            selected = option.tag == currentTag,
                            onClick = {
                                showSheet = false
                                applyLanguage(option.tag)
                            }
                        )
                    }
                    if (filteredLanguages.isEmpty()) {
                        item(key = "no-results") {
                            Text(
                                text = noResultsLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSheetRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

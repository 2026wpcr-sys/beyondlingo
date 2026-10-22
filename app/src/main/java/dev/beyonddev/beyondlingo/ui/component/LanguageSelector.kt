package dev.beyonddev.beyondlingo.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beyonddev.beyondlingo.data.engine.LangEnum

/**
 * 语言选择器：点击弹出底部选单，常见语言置顶，其余按字母排列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelector(
    selected: LangEnum,
    onSelect: (LangEnum) -> Unit,
    includeAuto: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled) { showSheet = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = selected.labelZh,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 0.7f else 0.3f,
                ),
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            LanguageSheetContent(
                selected = selected,
                includeAuto = includeAuto,
                onSelect = {
                    onSelect(it)
                    showSheet = false
                },
            )
        }
    }
}

@Composable
private fun LanguageSheetContent(
    selected: LangEnum,
    includeAuto: Boolean,
    onSelect: (LangEnum) -> Unit,
) {
    val source = remember(includeAuto) {
        LangEnum.entries.filter { includeAuto || it != LangEnum.Auto }
    }
    val common = source.filter { it.common }
    val others = source.filter { !it.common }

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(
            text = "选择语言",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
        ) {
            items(common, key = { "c_${it.name}" }) { lang ->
                LanguageRow(lang, lang == selected) { onSelect(lang) }
            }

            if (others.isNotEmpty()) {
                item(key = "divider") {
                    Column {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Text(
                            text = "其他语言",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                items(others, key = { "o_${it.name}" }) { lang ->
                    LanguageRow(lang, lang == selected) { onSelect(lang) }
                }
            }

            item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LanguageRow(
    lang: LangEnum,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lang.labelZh,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (lang.labelEn != lang.labelZh) {
                Text(
                    text = lang.labelEn,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

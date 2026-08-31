@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.tenderbase.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tenderbase.app.ApiClient
import com.tenderbase.app.DateFilter
import com.tenderbase.app.DocumentFilter
import com.tenderbase.app.R
import com.tenderbase.app.SearchFilters
import com.tenderbase.app.StatusFilter
import com.tenderbase.app.TenderTaxonomy
import com.tenderbase.app.ui.theme.TbDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Full filter sheet (spec §6): status, dates (+ custom closing window),
 * province, category, source, organisation and documents — with live facet
 * counts and per-section flow-wrap chips that can never clip.
 */
@Composable
fun TbFilterSheet(
    current: SearchFilters,
    facets: ApiClient.Facets?,
    onDismiss: () -> Unit,
    onApply: (SearchFilters) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TbDimens.spaceLg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
                Text(
                    stringResource(R.string.filter_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    draft = draft.copy(
                        provinces = emptyList(), categories = emptyList(), sources = emptyList(),
                        status = null, dateFilter = DateFilter.ANY, closingAfter = null,
                        closingBefore = null, organisation = null, docs = DocumentFilter.ANY,
                    )
                }) {
                    Text(stringResource(R.string.clear_all), style = MaterialTheme.typography.labelLarge)
                }
            }

            SheetLabel(stringResource(R.string.section_status))
            TbChipFlow {
                listOf(
                    null to stringResource(R.string.status_any),
                    StatusFilter.OPEN to stringResource(R.string.status_open),
                    StatusFilter.CLOSING_SOON to stringResource(R.string.status_closing_soon),
                    StatusFilter.CLOSED to stringResource(R.string.status_closed),
                ).forEach { (value, label) ->
                    TbFilterChip(
                        label = label,
                        selected = draft.status == value,
                        onClick = { draft = draft.copy(status = value) },
                    )
                }
            }

            SheetLabel(stringResource(R.string.section_date))
            TbChipFlow {
                listOf(
                    DateFilter.ANY to stringResource(R.string.date_any),
                    DateFilter.PUBLISHED_TODAY to stringResource(R.string.published_today),
                    DateFilter.PUBLISHED_7D to stringResource(R.string.published_7d),
                    DateFilter.CLOSING_7D to stringResource(R.string.closing_7d),
                    DateFilter.CLOSING_14D to stringResource(R.string.closing_14d),
                    DateFilter.CLOSING_30D to stringResource(R.string.closing_30d),
                    DateFilter.CLOSING_CUSTOM to stringResource(R.string.closing_custom),
                ).forEach { (value, label) ->
                    TbFilterChip(
                        label = label,
                        selected = draft.dateFilter == value,
                        onClick = {
                            draft = draft.copy(
                                dateFilter = value,
                                closingAfter = if (value == DateFilter.CLOSING_CUSTOM) draft.closingAfter else null,
                                closingBefore = if (value == DateFilter.CLOSING_CUSTOM) draft.closingBefore else null,
                            )
                        },
                    )
                }
            }
            if (draft.dateFilter == DateFilter.CLOSING_CUSTOM) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TbDimens.spaceSm),
                    modifier = Modifier.padding(top = TbDimens.spaceSm),
                ) {
                    TbOutlinedButton(
                        text = draft.closingAfter ?: stringResource(R.string.start_date),
                        onClick = { showStartPicker = true },
                        modifier = Modifier.weight(1f),
                    )
                    TbOutlinedButton(
                        text = draft.closingBefore ?: stringResource(R.string.end_date),
                        onClick = { showEndPicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SheetLabel(stringResource(R.string.section_province))
            facetChips(
                items = facets?.provinces?.map { it.name to it.count }
                    ?: TenderTaxonomy.PROVINCES.map { it to 0 },
                selected = draft.provinces,
                onToggle = { name ->
                    draft = draft.copy(
                        provinces = if (name in draft.provinces) draft.provinces - name
                        else draft.provinces + name
                    )
                },
            )

            SheetLabel(stringResource(R.string.section_category))
            facetChips(
                items = facets?.categories?.map { it.name to it.count }
                    ?: TenderTaxonomy.CATEGORIES.map { it to 0 },
                selected = draft.categories,
                onToggle = { name ->
                    draft = draft.copy(
                        categories = if (name in draft.categories) draft.categories - name
                        else draft.categories + name
                    )
                },
            )

            val sources = facets?.sources.orEmpty()
            if (sources.size >= 2) {
                SheetLabel(stringResource(R.string.section_source))
                facetChips(
                    items = sources.map { it.name to it.count },
                    selected = draft.sources,
                    onToggle = { name ->
                        draft = draft.copy(
                            sources = if (name in draft.sources) draft.sources - name
                            else draft.sources + name
                        )
                    },
                )
            }

            SheetLabel(stringResource(R.string.filter_organisation))
            OutlinedTextField(
                value = draft.organisation.orEmpty(),
                onValueChange = { draft = draft.copy(organisation = it.ifBlank { null }) },
                placeholder = {
                    Text(stringResource(R.string.organisation_hint))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )

            SheetLabel(stringResource(R.string.section_documents))
            TbChipFlow {
                listOf(
                    DocumentFilter.ANY to stringResource(R.string.docs_any),
                    DocumentFilter.HAS_DOCS to stringResource(R.string.docs_has_documents),
                    DocumentFilter.HAS_NOTICE to stringResource(R.string.docs_has_notice),
                    DocumentFilter.HAS_SPEC to stringResource(R.string.docs_has_spec),
                ).forEach { (value, label) ->
                    TbFilterChip(
                        label = label,
                        selected = draft.docs == value,
                        onClick = { draft = draft.copy(docs = value) },
                    )
                }
            }

            Spacer(Modifier.height(TbDimens.spaceXl))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = TbDimens.spaceLg),
            ) {
                TbTextButton(text = stringResource(R.string.reset), onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                TbPrimaryButton(
                    text = stringResource(R.string.apply_filters),
                    onClick = { onApply(draft) },
                )
            }
        }
    }

    if (showStartPicker) {
        DateWindowPicker(
            initialIso = draft.closingAfter,
            onPick = { iso ->
                var next = draft.copy(closingAfter = iso)
                val end = next.closingBefore
                if (iso != null && end != null && iso > end) next = next.copy(closingBefore = iso)
                draft = next
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        DateWindowPicker(
            initialIso = draft.closingBefore,
            onPick = { iso ->
                var next = draft.copy(closingBefore = iso)
                val start = next.closingAfter
                if (iso != null && start != null && iso < start) next = next.copy(closingAfter = iso)
                draft = next
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false },
        )
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = TbDimens.spaceLg, bottom = TbDimens.spaceXs),
    )
}

@Composable
private fun facetChips(
    items: List<Pair<String, Int>>,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    TbChipFlow {
        items.forEach { (name, count) ->
            val label = if (count > 0) "$name ($count)" else name
            TbFilterChip(
                label = name.replace('-', ' ')
                    .replaceFirstChar { it.uppercase() }
                    .let { n -> if (count > 0) "$n ($count)" else n },
                selected = name in selected,
                onClick = { onToggle(name) },
            )
        }
    }
}

@Composable
private fun DateWindowPicker(
    initialIso: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMillis = initialIso?.let { iso ->
        runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(iso)?.time
        }.getOrNull()
    }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                onPick(
                    millis?.let {
                        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        fmt.timeZone = TimeZone.getTimeZone("UTC")
                        fmt.format(Date(it))
                    }
                )
            }) { Text(stringResource(R.string.show_results)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

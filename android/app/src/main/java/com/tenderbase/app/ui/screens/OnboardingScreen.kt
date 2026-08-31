@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.tenderbase.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tenderbase.app.ClickGuard
import com.tenderbase.app.CrashReporter
import com.tenderbase.app.OnboardingLogic
import com.tenderbase.app.R
import com.tenderbase.app.TenderTaxonomy
import com.tenderbase.app.ui.components.TbBrandMark
import com.tenderbase.app.ui.components.TbChipFlow
import com.tenderbase.app.ui.components.TbFilterChip
import com.tenderbase.app.ui.components.TbPrimaryButton
import com.tenderbase.app.ui.components.TbSecondaryButton
import com.tenderbase.app.ui.theme.TbDimens
import com.tenderbase.app.ui.vm.OnboardingViewModel
import kotlinx.coroutines.launch

/**
 * First-launch flow (spec §14): discover → choose categories/provinces →
 * save → deadline alerts. No account required — TenderBase works offline-first
 * and the backend needs only the install id.
 *
 * Sprint 0+1: the screen is stateless — selections live in
 * [OnboardingViewModel] (application context, single write on exit) and every
 * step/tap leaves a [CrashReporter] breadcrumb so a failure here can never be
 * invisible again. Page advances are clamped and double-tap guarded.
 */
@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel,
    onFinish: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val pageCount = vm.pageCount
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    val categories by vm.categories.collectAsStateWithLifecycle()
    val provinces by vm.provinces.collectAsStateWithLifecycle()

    // Breadcrumb trail of exactly where the user was (Sprint 0 diagnostics).
    LaunchedEffect(pagerState.currentPage) {
        CrashReporter.breadcrumb("ob: page ${pagerState.currentPage + 1}/$pageCount")
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = TbDimens.spaceLg)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TbDimens.spaceLg),
        ) {
            TbBrandMark(size = 26.dp)
            Spacer(Modifier.width(TbDimens.spaceSm))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (pagerState.currentPage < pageCount - 1) {
                TextButton(
                    onClick = onFinish,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.ob_skip))
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> ObWelcome()
                1 -> ObPicker(
                    title = stringResource(R.string.ob_cat_title),
                    body = stringResource(R.string.ob_cat_body),
                    options = TenderTaxonomy.CATEGORIES,
                    selected = categories,
                    onToggle = vm::toggleCategory,
                )
                2 -> ObPicker(
                    title = stringResource(R.string.ob_prov_title),
                    body = stringResource(R.string.ob_prov_body),
                    options = TenderTaxonomy.PROVINCES,
                    selected = provinces,
                    onToggle = vm::toggleProvince,
                )
                else -> ObNotifications()
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(TbDimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TbDimens.spaceMd),
        ) {
            repeat(pageCount) { i ->
                val targetWidth = if (pagerState.currentPage == i) 22.dp else 8.dp
                val w by animateDpAsState(targetWidth, label = "dot-$i")
                Box(
                    Modifier
                        .height(8.dp)
                        .width(w)
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = TbDimens.spaceLg),
        ) {
            if (pagerState.currentPage == 1) {
                TextButton(onClick = { vm.selectAllCategories() }) {
                    Text(stringResource(R.string.pref_select_all))
                }
            }
            if (pagerState.currentPage == 2) {
                TextButton(onClick = { vm.selectAllProvinces() }) {
                    Text(stringResource(R.string.pref_select_all))
                }
            }
            Spacer(Modifier.weight(1f))
            when (pagerState.currentPage) {
                in 0..2 -> TbPrimaryButton(
                    text = stringResource(R.string.ob_continue),
                    onClick = {
                        if (!ClickGuard.tryClick()) return@TbPrimaryButton
                        val current = pagerState.currentPage
                        // Clamped: the target can never escape the page range,
                        // even if a late frame reports a stale currentPage.
                        val target = (current + 1).coerceAtMost(pageCount - 1)
                        CrashReporter.breadcrumb("ob: continue ${current + 1}→${target + 1}")
                        scope.launch { pagerState.animateScrollToPage(target) }
                    },
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(TbDimens.spaceSm)) {
                    TbSecondaryButton(
                        text = stringResource(R.string.ob_notif_skip),
                        onClick = onFinish,
                    )
                    TbPrimaryButton(
                        text = stringResource(R.string.ob_notif_enable),
                        onClick = onRequestNotifications,
                    )
                }
            }
        }
    }
}

@Composable
private fun ObWelcome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TbDimens.spaceSm, vertical = TbDimens.spaceXxl),
    ) {
        TbBrandMark(size = 64.dp)
        Spacer(Modifier.height(TbDimens.spaceXl))
        Text(
            stringResource(R.string.ob_welcome_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(TbDimens.spaceMd))
        Text(
            stringResource(R.string.ob_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(TbDimens.spaceXxl))
        ObStep(Icons.Filled.Search, stringResource(R.string.ob_step1))
        Spacer(Modifier.height(TbDimens.spaceLg))
        ObStep(Icons.Filled.Star, stringResource(R.string.ob_step2))
        Spacer(Modifier.height(TbDimens.spaceLg))
        ObStep(Icons.Filled.CalendarMonth, stringResource(R.string.ob_step3))
    }
}

@Composable
private fun ObStep(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(TbDimens.iconMd),
            )
        }
        Spacer(Modifier.width(TbDimens.spaceMd))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ObPicker(
    title: String,
    body: String,
    options: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(TbDimens.spaceXxl))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(TbDimens.spaceSm))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(TbDimens.spaceXl))
        TbChipFlow {
            options.forEach { name ->
                TbFilterChip(
                    label = TenderTaxonomy.displayName(name),
                    selected = OnboardingLogic.isChipSelected(selected, name),
                    onClick = { onToggle(name) },
                )
            }
        }
        Spacer(Modifier.height(TbDimens.spaceLg))
        Text(
            stringResource(R.string.ob_picker_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun ObNotifications() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TbDimens.spaceSm),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(TbDimens.spaceXxl))
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(TbDimens.spaceXl))
        Text(
            stringResource(R.string.ob_notif_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(TbDimens.spaceSm))
        Text(
            stringResource(R.string.ob_notif_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(TbDimens.spaceXl))
        Text(
            stringResource(R.string.ob_done_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

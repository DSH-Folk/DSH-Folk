package me.bmax.apatch.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.generated.destinations.GeneralSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppearanceSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.BehaviorSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SecuritySettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.BackupSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ModuleSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.MultimediaSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ThemeStoreScreenDestination
import com.ramcosta.composedestinations.generated.destinations.UserscriptsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DshDeepCleanScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PermissionHubScreenDestination
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.util.ui.NavigationBarsSpacer

@Destination<RootGraph>
@Composable
fun SettingsSearchScreen(navigator: DestinationsNavigator) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    // 依赖扩展注册表：扩展可能在本次组合之后才注册设置项（异步加载），
    // 而 remember {} 会把它挡在外面 —— 表现是"扩展装了，但设置里搜不到"。
    val extSettingKeys = me.bmax.apatch.dsh.ext.ExtensionUiRegistry.settingItems().map { it.key }
    val resolvedEntries = remember(extSettingKeys) {
        SettingsRegistry.resolveAll(context.resources)
    }

    val filteredResults = remember(searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else {
            val query = searchQuery.lowercase()
            resolvedEntries.filter { it.title.contains(query, ignoreCase = true) ||
                    it.summary.contains(query, ignoreCase = true) ||
                    it.categoryName.contains(query, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.settings_search_title)) },
                searchText = searchQuery,
                onSearchTextChange = { searchQuery = it },
                onClearClick = { searchQuery = "" },
                onBackClick = { navigator.popBackStack() },
                startInSearchMode = true,
            )
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 内容延伸到透明的小白条（手势导航条）下方，而不是被它顶开一条实色带。
        // 底部安全区由页面自己补回（列表 contentPadding / 固定底栏的 navigationBarsPadding），
        // 否则最后一项会被手势条压住。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues_raw ->
        // contentWindowInsets 归零之后，Scaffold 不再替我们让开手势条。这里把底部
        // 安全区加回 innerPadding：各页面已有的 padding(innerPadding) 于是自动带上它，
        // 内部滚动容器一行都不用改。
        val paddingValues = PaddingValues(
            start = paddingValues_raw.calculateStartPadding(LocalLayoutDirection.current),
            top = paddingValues_raw.calculateTopPadding(),
            end = paddingValues_raw.calculateEndPadding(LocalLayoutDirection.current),
            // contentWindowInsets 归零后 Scaffold 不再让开手势条，这里把底部安全区补回来。
            bottom = paddingValues_raw.calculateBottomPadding() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (filteredResults.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isBlank())
                                stringResource(R.string.settings_search_empty_hint)
                            else
                                stringResource(R.string.settings_no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            val grouped = filteredResults.groupBy { it.entry.category }
            grouped.forEach { (category, entries) ->
                item(key = "header_${category.key}") {
                    Text(
                        text = stringResource(category.labelResId),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                items(entries, key = { it.entry.key }) { resolved ->
                    SearchResultItem(
                        title = resolved.title,
                        summary = resolved.summary.ifBlank { null },
                        categoryName = stringResource(resolved.entry.category.labelResId),
                        onClick = {
                            val highlightKey = resolved.entry.key
                            when (resolved.entry.directTarget) {
                                // 不在分类页上的设置项：直接开它真正所在的页面
                                SettingsTarget.THEME_STORE ->
                                    navigator.navigate(ThemeStoreScreenDestination) { launchSingleTop = true }
                                SettingsTarget.USERSCRIPTS ->
                                    navigator.navigate(UserscriptsScreenDestination) { launchSingleTop = true }
                                // 深度清理也不在分类页上（它挂在功能页那张卡的长按里），
                                // 搜索结果直接开它
                                SettingsTarget.DEEP_CLEAN ->
                                    navigator.navigate(DshDeepCleanScreenDestination) { launchSingleTop = true }
                                // 无障碍自动恢复在「权限管理」页里，不在功能页也不在安全页。
                                // 不单独指过去的话，用户搜到它、点进去会落在一个没有这张卡的页面上。
                                SettingsTarget.PERMISSION_HUB ->
                                    navigator.navigate(PermissionHubScreenDestination) { launchSingleTop = true }
                                null -> {
                                    // 扩展注册的设置项：宿主不知道它在哪个页面（它可能根本没有页面），
                                    // 所以直接调扩展给的 onClick。没有 onClick 的条目是纯展示项，
                                    // 点了什么也不做 —— 比跳到某个不相关的页面好。
                                    val ext = me.bmax.apatch.dsh.ext.ExtensionUiRegistry
                                        .settingItems().firstOrNull { it.key == highlightKey }
                                    if (ext != null) {
                                        ext.onClick?.invoke()
                                    } else when (category) {
                                    SettingCategory.GENERAL -> navigator.navigate(GeneralSettingsScreenDestination(highlightKey)) { launchSingleTop = true }
                                    SettingCategory.APPEARANCE -> navigator.navigate(AppearanceSettingsScreenDestination(highlightKey)) { launchSingleTop = true }
                                    SettingCategory.BEHAVIOR -> navigator.navigate(BehaviorSettingsScreenDestination(highlightKey)) { launchSingleTop = true }
                                    SettingCategory.SECURITY -> navigator.navigate(SecuritySettingsScreenDestination(highlightKey)) { launchSingleTop = true }
                                    SettingCategory.BACKUP -> navigator.navigate(BackupSettingsScreenDestination(highlightKey)) { launchSingleTop = true }
                                    SettingCategory.MODULE -> navigator.navigate(ModuleSettingsScreenDestination(highlightKey)) { launchSingleTop = true }
                                    SettingCategory.MULTIMEDIA -> navigator.navigate(MultimediaSettingsScreenDestination(highlightKey)) { launchSingleTop = true }
                                    SettingCategory.FUNCTION -> navigator.navigate(FunctionSettingsScreenDestination(highlightKey)) { launchSingleTop = true }
                                }
                                    }
                            }
                        },
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { NavigationBarsSpacer() }
        }
    }
}

@Composable
private fun SearchResultItem(
    title: String,
    summary: String?,
    categoryName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = categoryName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

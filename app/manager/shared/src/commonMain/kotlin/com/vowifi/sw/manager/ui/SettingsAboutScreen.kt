package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.model.EngineExecutableMatch
import com.vowifi.sw.manager.model.EngineIntegritySnapshot
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.ModuleDiagnostics
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info

@Stable
internal class AboutHeaderState(val list: LazyListState) {
    var spacerHeightPx by mutableIntStateOf(0)
    val progress by derivedStateOf {
        when {
            list.firstVisibleItemIndex > 0 -> 1f
            spacerHeightPx == 0 -> 0f
            else -> (list.firstVisibleItemScrollOffset.toFloat() / spacerHeightPx).coerceIn(0f, 1f)
        }
    }
}

internal val LocalAboutHeaderState = staticCompositionLocalOf<AboutHeaderState?> { null }

@Composable
internal fun rememberAboutHeaderState(): AboutHeaderState {
    val list = rememberLazyListState()
    return remember(list) { AboutHeaderState(list) }
}

internal fun aboutInformationRows(appVersion: String, appVersionCode: Int? = null,
    module: ModuleDiagnostics? = null,
    integrity: EngineIntegritySnapshot = EngineIntegritySnapshot(),
    includeIntegrity: Boolean = true,
): List<ManagerPreferenceRow.Information> {
    val about = aboutPresentation(appVersion)
    val installed = module?.takeIf { it.installed }
    val version = installed?.version?.takeUnless { Regex("\\d{13,}").containsMatchIn(it) }
    val moduleName = aboutPresentation(version.orEmpty()).versionLabel.removePrefix("版本 ")
    val appName = about.versionLabel.removePrefix("版本 ")
    return buildList {
        add(managerInformationPreference("应用版本", about.versionLabel + appVersionCode?.let { " · code $it" }.orEmpty(), MiuixIcons.Info))
        add(managerInformationPreference("应用信息", about.description, MiuixIcons.Info))
        val moduleSummary = if (moduleName == "未知" && installed?.versionCode == null) "未知"
            else "版本 $moduleName · code ${installed?.versionCode ?: "未知"}"
        add(managerInformationPreference("模块版本", moduleSummary, MiuixIcons.Info))
        if ((moduleName != "未知" && appName != "未知" && moduleName != appName) ||
            (appVersionCode != null && installed?.versionCode != null && appVersionCode != installed.versionCode)) {
            add(managerInformationPreference("版本检查", "App 与模块版本不一致", MiuixIcons.Info))
        }
        if (includeIntegrity) addAll(aboutEngineInformationRows(integrity))
    }
}

private fun aboutEngineInformationRows(integrity: EngineIntegritySnapshot): List<ManagerPreferenceRow.Information> = buildList {
    add(managerInformationPreference("引擎标识", integrity.fileFingerprint ?: "无法读取", MiuixIcons.Info))
    val running = integrity.running
    when {
        running == null -> add(managerInformationPreference("运行实例", "无法读取", MiuixIcons.Info))
        running.isEmpty() -> add(managerInformationPreference("运行实例", "当前没有运行实例", MiuixIcons.Info))
        else -> running.forEachIndexed { index, instance ->
            val title = instance.pid?.let { "运行实例 PID $it" } ?: "运行实例 ${index + 1}"
            val summary = when (instance.match) {
                EngineExecutableMatch.SAME -> "与文件一致"
                EngineExecutableMatch.DIFFERENT -> "与文件不一致"
                EngineExecutableMatch.UNREADABLE -> "无法读取"
            }
            add(managerInformationPreference(title, summary, MiuixIcons.Info))
        }
    }
}

@Composable
internal fun SettingsAboutScreen(appVersion: String, appVersionCode: Int? = null,
    diagnosticsState: EnvironmentDiagnosticsUiState = EnvironmentDiagnosticsUiState.Loading,
    readEngineIntegrity: (suspend () -> EngineIntegritySnapshot)? = null,
) {
    val about = aboutPresentation(appVersion)
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState().value
    val active = LocalAppRouteActive.current && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val reader = rememberUpdatedState(readEngineIntegrity)
    var integrity by remember { mutableStateOf(EngineIntegritySnapshot()) }
    var reading by remember { mutableStateOf(active && readEngineIntegrity != null) }
    LaunchedEffect(active) {
        val read = reader.value
        if (active && read != null) {
            integrity = EngineIntegritySnapshot()
            reading = true
            try { integrity = read() } finally { reading = false }
        }
    }
    val loading = rememberManagerLoadingState(reading, integrity.fileFingerprint != null || integrity.running != null)
    val module = (diagnosticsState as? EnvironmentDiagnosticsUiState.Ready)?.module
    val rows = remember(appVersion, appVersionCode, module) {
        aboutInformationRows(appVersion, appVersionCode, module, includeIntegrity = false)
    }
    val header = LocalAboutHeaderState.current ?: rememberAboutHeaderState()
    val density = LocalDensity.current
    var identityHeightPx by remember { mutableIntStateOf(0) }
    val topInset = LocalSubpageTopContentInset.current
    val bottomInset = LocalSubpageBottomContentInset.current
    val reduceMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val colors = LocalSoftVoWiFiColors.current
    Box(Modifier.fillMaxSize()) {
        AboutCloudBackground(progress = { header.progress })
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 960.dp).fillMaxWidth()
                .padding(top = topInset + 40.dp, start = 24.dp, end = 24.dp)
                .onSizeChanged { identityHeightPx = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(88.dp).aboutIdentityLayer(header, .35f, .50f, reduceMotion), Alignment.Center) {
                AboutAppIcon(Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "SoftVoWiFi", modifier = Modifier.fillMaxWidth().aboutIdentityLayer(header, .20f, .35f, reduceMotion),
                color = colors.textPrimary, fontWeight = FontWeight.Bold,
                fontSize = 35.sp, lineHeight = 42.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                about.versionLabel, modifier = Modifier.fillMaxWidth().aboutIdentityLayer(header, .05f, .20f, reduceMotion),
                color = colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            )
        }
        LazyColumn(
            state = header.list,
            modifier = Modifier.fillMaxSize().widthIn(max = 960.dp).align(Alignment.TopCenter)
                .managerVerticalScrollFeedback(),
            overscrollEffect = null,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topInset, bottom = bottomInset + 12.dp),
        ) {
            item(key = "about-identity-space") {
                Spacer(Modifier.fillMaxWidth()
                    .height(40.dp + with(density) { identityHeightPx.toDp() } + 112.dp)
                    .onSizeChanged { header.spacerHeightPx = it.height })
            }
            item(key = "about-card") {
                // A viewport-high body allows the short, real information list to cover the identity;
                // do not invent settings or links just to make this screen scrollable.
                Column(Modifier.fillParentMaxHeight()) {
                    Card(insideMargin = PaddingValues(vertical = 4.dp)) {
                        Column {
                            rows.forEach { ManagerInformationPreferenceItem(it) }
                            if (loading.showLoading) BasicComponent(title = "正在读取引擎标识", summary = "只读检查文件与运行实例。",
                                modifier = Modifier.managerLoadingAlpha(loading),
                                endActions = { CircularProgressIndicator(size = 16.dp, modifier = Modifier.size(16.dp)) })
                            else if (!loading.hidden) Column(Modifier.managerLoadingAlpha(loading)) {
                                aboutEngineInformationRows(integrity).forEach { ManagerInformationPreferenceItem(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.aboutIdentityLayer(header: AboutHeaderState, start: Float, end: Float, reduceMotion: Boolean) =
    graphicsLayer {
        val progress = ((header.progress - start) / (end - start)).coerceIn(0f, 1f)
        alpha = 1f - progress
        scaleX = if (reduceMotion) 1f else 1f - .05f * progress
        scaleY = scaleX
    }.semantics {
        if (header.progress >= end) hideFromAccessibility()
    }

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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.ModuleDiagnostics
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
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
): List<ManagerPreferenceRow.Information> {
    val about = aboutPresentation(appVersion)
    val installed = module?.takeIf { it.installed }
    val version = installed?.version?.takeUnless { Regex("\\d{13,}").containsMatchIn(it) }
    val moduleName = aboutPresentation(version.orEmpty()).versionLabel.removePrefix("版本 ")
    val appName = about.versionLabel.removePrefix("版本 ")
    return buildList {
        val moduleSummary = if (moduleName == "未知" && installed?.versionCode == null) "未知"
            else "版本 $moduleName · code ${installed?.versionCode ?: "未知"}"
        add(managerInformationPreference("Root 后端版本", moduleSummary, MiuixIcons.Info))
        if ((moduleName != "未知" && appName != "未知" && moduleName != appName) ||
            (appVersionCode != null && installed?.versionCode != null && appVersionCode != installed.versionCode)) {
            add(managerInformationPreference("版本检查", "App 与 Root 后端版本不一致", MiuixIcons.Info))
        }
    }
}

@Composable
internal fun SettingsAboutScreen(appVersion: String, appVersionCode: Int? = null,
    diagnosticsState: EnvironmentDiagnosticsUiState = EnvironmentDiagnosticsUiState.Loading,
    onOpenEngineInformation: (() -> Unit)? = null,
) {
    val about = aboutPresentation(appVersion)
    val module = (diagnosticsState as? EnvironmentDiagnosticsUiState.Ready)?.module
    val rows = remember(appVersion, appVersionCode, module) {
        aboutInformationRows(appVersion, appVersionCode, module)
    }
    val header = LocalAboutHeaderState.current ?: rememberAboutHeaderState()
    val density = LocalDensity.current
    var identityHeightPx by remember { mutableIntStateOf(0) }
    val topInset = LocalSubpageTopContentInset.current
    val bottomInset = LocalSubpageBottomContentInset.current
    val reduceMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val colors = LocalSoftVoWiFiColors.current
    val identityMeasured = identityHeightPx > 0
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
                about.versionLabel + appVersionCode?.let { " · code $it" }.orEmpty(),
                modifier = Modifier.fillMaxWidth().aboutIdentityLayer(header, .05f, .20f, reduceMotion),
                color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
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
            if (identityMeasured) item(key = "about-card") {
                // A viewport-high body allows the short, real information list to cover the identity;
                // do not invent settings or links just to make this screen scrollable.
                Column(Modifier.fillParentMaxHeight()) {
                    SmallTitle("版本信息")
                    Card(insideMargin = PaddingValues(vertical = 4.dp)) {
                        Column {
                            rows.forEach { row -> BasicComponent(title = row.title, summary = row.summary) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SmallTitle("更多信息")
                    Card(insideMargin = PaddingValues(vertical = 4.dp)) {
                        ManagerNavigatePreferenceItem(
                            managerNavigatePreference("引擎信息", "查看引擎标识与运行实例", MiuixIcons.Info,
                                if (onOpenEngineInformation != null) ManagerPreferenceAvailability.Enabled
                                else ManagerPreferenceAvailability.Disabled("当前入口不可用")),
                            onClick = onOpenEngineInformation,
                        )
                    }
                    Text(about.description, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        textAlign = TextAlign.Center)
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

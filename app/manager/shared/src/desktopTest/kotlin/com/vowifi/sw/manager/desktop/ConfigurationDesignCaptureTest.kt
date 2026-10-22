package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationImsPolicy
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.ui.AppSubpageScene
import com.vowifi.sw.manager.ui.AppearanceSettings
import com.vowifi.sw.manager.ui.ConfigurationDraftStore
import com.vowifi.sw.manager.ui.SettingsConfigurationActions
import com.vowifi.sw.manager.ui.SettingsConfigurationScreen
import com.vowifi.sw.manager.ui.SettingsConfigurationSimScreen
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import com.vowifi.sw.manager.ui.appChromeSpec
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConfigurationSimHandle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Synthetic-value design capture at Xiaomi 13 geometry; images stay in build/reports, never in Git. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ConfigurationDesignCaptureTest {
    @Test fun renderOverviewDetailBarAndDialogsWithSyntheticValues() {
        val folder = Files.createDirectories(Path.of("build/reports/configuration-design"))
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val first = synthetic("sim-1", "SIM 1 · 合成运营商甲 · 460 / 09 · •••• 1001", ConfigurationLifecycle.RUNNING,
            ConfigurationTransport.WIFI, 1)
        val second = synthetic("sim-2", "SIM 2 · 合成运营商乙 · 505 / 03 · •••• 2002", ConfigurationLifecycle.STOPPED,
            ConfigurationTransport.AUTO, 2)
        val state = ConfigurationUiState.Ready(listOf(first, second))
        val actions = SettingsConfigurationActions({ _, _, _ -> ConfigurationApplyAcceptance.Accepted }, {}, { error("预览不刷新") })
        val spec = appChromeSpec(AppearanceSettings.Default, false)
        val drafts = ConfigurationDraftStore()

        fun capture(name: String, fontScale: Float = 1f, dark: Boolean = false,
            steps: (ImageComposeScene, () -> Unit) -> Unit = { _, _ -> }, content: @Composable () -> Unit) {
            val scene = ImageComposeScene(width = 1080, height = 2400, density = Density(2.75f, fontScale)) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(dark) { content() } }
            }
            var time = 0L
            fun settle() = repeat(40) { time += 50_000_000L; scene.render(time).close() }
            try {
                settle()
                steps(scene, ::settle)
                scene.render(time).use { image -> image.encodeToData()!!.use { Files.write(folder.resolve("$name.png"), it.bytes) } }
            } finally { scene.close() }
        }

        capture("overview") {
            AppSubpageScene(AppRoute.SettingsConfiguration, spec, onBack = {}) {
                SettingsConfigurationScreen(state, null, actions, drafts)
            }
        }
        val route = AppRoute.SettingsConfigurationSim(ConfigurationSimHandle.parse("sim-1")!!)
        capture("detail-clean") {
            AppSubpageScene(route, spec, onBack = {}) {
                SettingsConfigurationSimScreen(route.sim, state, null, actions, ConfigurationDraftStore())
            }
        }
        val dirty = ConfigurationDraftStore()
        dirty.draftFor(first).apply { transport = ConfigurationTransport.SIM1; smsc.edit { replace(0, length, "+100200300") } }
        capture("detail-dirty") {
            AppSubpageScene(route, spec, onBack = {}) {
                SettingsConfigurationSimScreen(route.sim, state, null, actions, dirty)
            }
        }
        capture("field-dialog", steps = { scene, settle -> click(scene, settle, "ePDG 地址"); settle() }) {
            AppSubpageScene(route, spec, onBack = {}) {
                SettingsConfigurationSimScreen(route.sim, state, null, actions, ConfigurationDraftStore())
            }
        }
        capture("security-menu", steps = { scene, settle -> click(scene, settle, "SIP 安全策略"); settle() }) {
            AppSubpageScene(route, spec, onBack = {}) {
                SettingsConfigurationSimScreen(route.sim, state, null, actions, ConfigurationDraftStore())
            }
        }
        capture("detail-dirty-dark-200", fontScale = 2f, dark = true) {
            AppSubpageScene(route, spec, onBack = {}) {
                SettingsConfigurationSimScreen(route.sim, state, null, actions, dirty)
            }
        }
        var backs by mutableStateOf(0)
        capture("leave-dialog", steps = { scene, settle -> click(scene, settle, "返回"); settle() }) {
            AppSubpageScene(route, spec, onBack = { backs++ }) {
                SettingsConfigurationSimScreen(route.sim, state, null, actions, dirty)
            }
        }
        assertTrue(backs == 0, "有未保存修改时顶栏返回只能先询问，不能直接离开")
    }

    private fun synthetic(id: String, label: String, lifecycle: ConfigurationLifecycle, transport: ConfigurationTransport, seed: Int) =
        ConfigurationInstance(
            instanceId = ConfigurationInstanceId(id), displayLabel = label, lifecycle = lifecycle, transport = transport,
            smsc = "", epdgFqdn = "", imsRealm = "", dns = "",
            resolvedEpdgFqdn = "epdg.epc.mnc00$seed.mcc001.pub.3gppnetwork.org",
            resolvedImsRealm = "ims.mnc00$seed.mcc001.3gppnetwork.org", mcc = "001", mnc = "0$seed",
            draftOwnerId = SimOwnerId.fromHmacSha256(ByteArray(32) { seed.toByte() }),
            imsPolicy = ConfigurationImsPolicy(security = "tcp_ipsec_3gpp", securitySource = "carrier", initial = "wlan-node",
                initialSource = "catalog", country = "NZ", countrySource = "home_mcc", digestRealm = "ims.example.invalid",
                digestRealmSource = "carrier"),
        )

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return scene.semanticsOwners.flatMap { descend(it.unmergedRootSemanticsNode) }
    }

    private fun find(scene: ImageComposeScene, label: String): SemanticsNode? = nodes(scene).lastOrNull { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    // Lazy rows below the viewport are not composed (font metrics differ between hosts), so scroll like a user first.
    private fun click(scene: ImageComposeScene, settle: () -> Unit, label: String) {
        var node = find(scene, label)
        var scrolls = 0
        while (node == null && scrolls++ < 20) {
            val scroll = nodes(scene).firstNotNullOf { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }
            scroll(0f, 600f)
            settle()
            node = find(scene, label)
        }
        checkNotNull(node) { "未找到可点击的“$label”" }
        val action = generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        assertTrue(action())
    }
}

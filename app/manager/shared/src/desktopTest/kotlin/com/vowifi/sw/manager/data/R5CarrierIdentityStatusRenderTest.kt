package com.vowifi.sw.manager.data

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.ui.ConfigurationEditorDraft
import com.vowifi.sw.manager.ui.ConfigurationEditorModel
import com.vowifi.sw.manager.ui.ConfigurationSimEditor
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class R5CarrierIdentityStatusRenderTest {
    @Test fun realDecodedReadStatesReachTheExistingSIMEditorWithoutRawIdentity() {
        render("""{"spn":"known","gid1":"known","gid2":"denied"}""", 1f,
            listOf("SPN：已读取", "GID1：已读取", "GID2：权限不足"))
    }

    @Test fun unknownAbsentAndInvalidStatesDoNotClaimSuccessfulMatching() {
        render("""{"spn":"private-future-state","gid1":"absent","gid2":"invalid"}""", 2f,
            listOf("SPN：未知", "GID1：未提供", "GID2：数据无效"))
    }

    private fun render(status: String, scale: Float, expected: List<String>) {
        val body = redactedShowJson(extra = buildJsonObject {
            put("simAttributeReadStatus", Json.parseToJsonElement(status))
        })
        val snapshot = ConfigurationCodec.show(body)
        val instance = ConfigurationEvaluator.instance(ConfigurationInstanceId("sim-1"), snapshot)
        val ready = assertIs<ConfigurationUiState.Ready>(ConfigurationEvaluator.ready(listOf(instance)))
        val scene = ImageComposeScene(width = 375, height = 1800, density = Density(1f, scale)) {
            SoftVoWiFiTheme(forceDark = scale > 1f) {
                Scaffold {
                    ConfigurationSimEditor(ConfigurationEditorModel(instance, ready, true, null), ConfigurationEditorDraft(instance),
                        onSubmit = { _, _, _ -> error("只读身份状态不应提交") })
                }
            }
        }
        try {
            repeat(4) { scene.render((it + 1) * 100_000_000L).close() }
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            val text = scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
            assertTrue("SIM 匹配身份" in text, "真实解码状态未接入单卡配置页")
            expected.forEach { assertTrue(it in text, "身份读取状态未准确区分：$it") }
            assertFalse("private-future-state" in text)
            assertFalse("匹配成功" in text)
            assertFalse("00A1FF" in text)
        } finally { scene.close() }
    }
}

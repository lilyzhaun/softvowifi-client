package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.ConfigurationCodec
import com.vowifi.sw.manager.data.ConfigurationCallMediaCodec
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.ConfigurationSection
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationMediaEditorRenderTest {
    @Test fun sdesSuitesAreExplicitlyChosenOrderedSavedAndClearedWithoutDroppingSpeechFormats() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            callMedia = selected, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, next, complete ->
                        writes++
                        val security = ConfigurationCallMediaCodec.encode(next.callMedia!!).jsonObject.getValue("security").jsonObject
                        assertEquals(JsonPrimitive("sdes"), security["keying"])
                        assertEquals(listOf("AEAD_AES_256_GCM", "AES_CM_128_HMAC_SHA1_80"), security.getValue("suites").jsonArray.map { it.jsonPrimitive.content })
                        row = row.copy(callMedia = next.callMedia, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row, effect = ConfigurationApplyEffect.NextCall))
                        ConfigurationApplyAcceptance.Accepted
                    })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "媒体保护"); tap(e, "媒体保护"); tap(e, "AES-CM 128")
            assertEquals(0, writes)
            scrollTo(e, "添加加密套件"); tap(e, "添加加密套件"); tap(e, "AES-GCM 256")
            scrollTo(e, "优先顺序 · 套件 2"); tap(e, "优先顺序 · 套件 2"); tap(e, "第 1 位")
            assertTrue(draft.onlyNextCallChanges)
            assertEquals(selected.speechFormats, draft.callMedia!!.speechFormats)
            tap(e, "保存配置")
            assertEquals(1, writes)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
            scrollTo(e, "清除媒体保护策略"); tap(e, "清除媒体保护策略")
            assertFalse("security" in ConfigurationCallMediaCodec.encode(draft.callMedia!!).jsonObject)
            assertEquals(selected.speechFormats, draft.callMedia!!.speechFormats)
            assertEquals(1, writes)
            scrollTo(e, "语音媒体", direction = -1f)
            scrollTo(e, "媒体保护"); tap(e, "媒体保护"); tap(e, "AES-GCM 128")
            scrollTo(e, "移除格式 1"); tap(e, "移除格式 1")
            assertTrue("清除整组媒体覆盖？" in texts(e))
            tap(e, "取消")
            assertEquals(selected.speechFormats, draft.callMedia!!.speechFormats)
            assertTrue("security" in ConfigurationCallMediaCodec.encode(draft.callMedia!!).jsonObject)
            tap(e, "移除格式 1"); tap(e, "清除整组")
            assertEquals(ConfigurationCallMedia(), draft.callMedia)
            assertEquals(1, writes)
        }
    } }

    @Test fun transportQualityUpdatesDoNotDismissMediaEditsOrWholeGroupConfirmation() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            callMedia = selected, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        fun runtime(revision: Long) = ConfigurationRuntimeFacts(
            ConfigurationFacts(INITIAL, "rf1_" + "a".repeat(64), emptyList()),
            call = ConfigurationCallFacts(INITIAL, media = ConfigurationRuntimeMediaFacts("media-epoch", 3,
                ConfigurationSpeechCodec.AMR_WB, ConfigurationSpeechFraming.OA, 16000, 96, 96, 20, 240, "sendrecv", 0,
                ConfigurationTransportQuality(revision))))
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("runtime updates cannot save draft edits") })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "本地载荷编号 · 格式 1"); tap(e, "本地载荷编号 · 格式 1")
            enter(e, "117")
            row = row.copy(runtimeFacts = runtime(1)); frames(e)
            assertNotNull(visible(e, "确定"))
            val input = nodes(e).single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertEquals("117", input.config[SemanticsProperties.EditableText].text)
            tap(e, "确定")
            assertEquals(117, draft.callMedia!!.speechFormats.first().payloadType)
            scrollTo(e, "清除媒体覆盖", direction = -1f); tap(e, "清除媒体覆盖")
            row = row.copy(runtimeFacts = runtime(2)); frames(e)
            assertTrue(texts(e).contains("清除整组媒体覆盖？"))
            tap(e, "取消")
            assertEquals(117, draft.callMedia!!.speechFormats.first().payloadType)
            assertTrue(draft.dirty)
        }
    } }

    @Test fun unknownMediaRequiresARealClearSaveAndReadbackBeforeTheAddActionAppears() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            callMedia = null, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, next, complete ->
                        writes++
                        assertEquals(ConfigurationCallMedia(), next.callMedia)
                        row = row.copy(callMedia = next.callMedia, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row, effect = ConfigurationApplyEffect.NextCall))
                        ConfigurationApplyAcceptance.Accepted
                    })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "清除媒体覆盖")
            tap(e, "清除媒体覆盖"); tap(e, "取消")
            assertNull(draft.callMedia); assertFalse(draft.dirty)
            tap(e, "清除媒体覆盖"); tap(e, "清除整组")
            assertEquals(ConfigurationCallMedia(), draft.callMedia)
            assertEquals(0, writes)
            assertFalse(texts(e).contains("添加语音格式"))
            tap(e, "保存配置")
            assertEquals(1, writes)
            scrollTo(e, "添加语音格式")
            assertNotNull(visible(e, "添加语音格式"))
        }
    } }

    @Test fun realMediaFieldDialogKeepsZeroRejectsInvalidInputAndSavesTheAtomicGroupWithoutRestart() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            callMedia = selected, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        var patch: JsonObject? = null
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, next, complete ->
                        patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        writes++
                        row = row.copy(callMedia = next.callMedia, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row, effect = ConfigurationApplyEffect.NextCall))
                        ConfigurationApplyAcceptance.Accepted
                    })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "初始模式 · 格式 1"); tap(e, "初始模式 · 格式 1")
            enter(e, "1"); tap(e, "确定")
            assertEquals(0, draft.callMedia!!.speechFormats.first().initialMode)
            assertTrue(texts(e).contains("参数无效或与其它格式重复，请核对后再确认"))
            enter(e, "2"); tap(e, "确定")
            assertEquals(2, draft.callMedia!!.speechFormats.first().initialMode)
            assertEquals(0, writes)
            tap(e, "保存配置")
            assertEquals(1, writes)
            assertFalse(texts(e).contains("保存并重启实例？"))
            assertEquals(setOf("callMedia"), patch!!.getValue("overrides").jsonObject.keys)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun narrowLargeTextKeepsMediaControlsCompleteAndOldCallbacksLoseAuthorityWhenTheRouteExits() = onMotionTestThread { runTest {
        val ready = ready()
        val row = ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING, callMedia = selected,
            configVersion = 1, configRevision = INITIAL)
        val draft = ConfigurationEditorDraft(row)
        var active by mutableStateOf(true)
        MotionTestEnvironment(testScheduler, width = 320, height = 900) {
            SoftVoWiFiTheme(true) { CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalAppRouteActive provides active) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("geometry and retired route must not save") })
            } } }
        }.use { e ->
            frames(e)
            listOf("接收分包时长", "编解码 · 格式 1", "封装 · 格式 1", "允许模式 · 格式 1", "初始模式 · 格式 1", "本地载荷编号 · 格式 1", "接收模式周期 · 格式 1", "接收相邻模式 · 格式 1", "优先顺序 · 格式 1", "移除格式 1").forEach { label ->
                scrollTo(e, label, height = 900)
                val title = visible(e, label, 900) ?: error("missing $label")
                val layouts = mutableListOf<TextLayoutResult>()
                title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertTrue(layouts.isNotEmpty())
                layouts.forEach { layout ->
                    val geometry = "$label: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, bounds=${title.boundsInRoot}, overflow=${layout.didOverflowWidth}/${layout.didOverflowHeight}"
                    assertFalse(layout.didOverflowHeight, geometry)
                    // As on the incumbent signaling page, a wrap-content Text
                    // can retain a wider unused paragraph measurement box.
                    // Require every rendered line/character and the entire
                    // label to fit, rather than treating that box as clipping.
                    assertTrue(layout.size.width <= title.boundsInRoot.width, geometry)
                    for (line in 0 until layout.lineCount) {
                        assertTrue(layout.getLineLeft(line) >= 0f, geometry)
                        assertTrue(layout.getLineRight(line) <= layout.size.width, geometry)
                        assertFalse(layout.isLineEllipsized(line), geometry)
                    }
                    layout.layoutInput.text.indices.filter { !layout.layoutInput.text[it].isWhitespace() }.forEach { offset ->
                        val box = layout.getBoundingBox(offset)
                        assertTrue(box.left >= 0f && box.right <= layout.size.width, "$geometry, character=$offset, box=$box")
                    }
                    assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1), geometry)
                    assertTrue(layout.size.height <= title.boundsInRoot.height + 1f, geometry)
                }
                val action = clickable(e, label)
                assertTrue(action.boundsInRoot.height >= 48f, "$label: visible=${action.boundsInRoot}, layout=${action.size}, title=${title.boundsInRoot}")
            }
            scrollTo(e, "初始模式 · 格式 1", direction = -1f, height = 900)
            tap(e, "初始模式 · 格式 1"); enter(e, "2")
            val oldConfirm = clickable(e, "确定").config[SemanticsActions.OnClick].action!!
            active = false; frames(e)
            oldConfirm.invoke(); frames(e)
            assertEquals(0, draft.callMedia!!.speechFormats.first().initialMode)
            assertFalse(draft.dirty)
        }
    } }

    @Test fun realAddReorderFramingAndModeControlsKeepWholeFormatsAndOnlyChangeTheDraft() = onMotionTestThread { runTest {
        val ready = ready()
        val paired = selected.copy(packetization = ConfigurationCallPacketization(60, 80))
        val row = ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING, callMedia = paired,
            configVersion = 1, configRevision = INITIAL)
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("format controls must only change the draft") })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "添加语音格式"); tap(e, "添加语音格式"); tap(e, "AMR-WB · BE")
            assertEquals(2, draft.callMedia!!.speechFormats.size)
            scrollTo(e, "语音媒体", direction = -1f)
            scrollTo(e, "优先顺序 · 格式 2"); tap(e, "优先顺序 · 格式 2"); tap(e, "第 1 位")
            assertEquals(ConfigurationSpeechCodec.AMR_WB, draft.callMedia!!.speechFormats[0].codec)
            assertEquals(selected.speechFormats[0], draft.callMedia!!.speechFormats[1])
            scrollTo(e, "语音媒体", direction = -1f)
            scrollTo(e, "封装 · 格式 1"); tap(e, "封装 · 格式 1"); tap(e, "OA")
            assertEquals(ConfigurationSpeechFraming.OA, draft.callMedia!!.speechFormats[0].framing)
            scrollTo(e, "允许模式 · 格式 1"); tap(e, "允许模式 · 格式 1"); enter(e, "4,0,2"); tap(e, "确定")
            assertEquals(listOf(0, 2, 4), draft.callMedia!!.speechFormats[0].modeSet)
            scrollTo(e, "本地载荷编号 · 格式 1"); tap(e, "本地载荷编号 · 格式 1"); enter(e, "120"); tap(e, "确定")
            assertEquals(120, draft.callMedia!!.speechFormats[0].payloadType)
            scrollTo(e, "移除格式 1"); tap(e, "移除格式 1")
            assertEquals(paired, draft.callMedia)
            tap(e, "移除格式 1"); tap(e, "取消")
            assertEquals(paired, draft.callMedia)
            scrollTo(e, "清除媒体覆盖", direction = -1f)
            tap(e, "清除媒体覆盖"); tap(e, "清除整组")
            assertEquals(ConfigurationCallMedia(), draft.callMedia)
        }
    } }

    @Test fun pcmuCanBeAddedAndEditedAsSampleAudioWithoutAmrControlsOrImplicitPT() = onMotionTestThread { runTest {
        val ready = ready()
        val row = ready.instances.first().copy(callMedia = ConfigurationCallMedia(), configVersion = 1, configRevision = INITIAL)
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("format edits must stay in the draft") })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "添加语音格式"); tap(e, "添加语音格式"); tap(e, "PCMU · SAMPLE")
            assertEquals("pcmu", draft.callMedia!!.speechFormats.single().codec.wire)
            assertEquals("sample", draft.callMedia!!.speechFormats.single().framing.wire)
            assertNull(draft.callMedia!!.speechFormats.single().payloadType)
            scrollTo(e, "语音媒体", direction = -1f)
            scrollTo(e, "本地载荷编号 · 格式 1")
            for (field in listOf("允许模式", "初始模式", "接收模式周期", "接收相邻模式")) {
                assertFalse("$field · 格式 1" in texts(e))
            }
            tap(e, "本地载荷编号 · 格式 1"); enter(e, "0"); tap(e, "确定")
            assertEquals(0, draft.callMedia!!.speechFormats.single().payloadType)
            assertNull(draft.callMedia!!.speechFormats.single().modeSet)
            assertNull(draft.callMedia!!.speechFormats.single().initialMode)
            assertTrue(draft.onlyNextCallChanges)
            scrollTo(e, "添加语音格式"); tap(e, "添加语音格式"); tap(e, "PCMU · SAMPLE")
            assertEquals(1, draft.callMedia!!.speechFormats.size)
            scrollTo(e, "语音媒体", direction = -1f)
            scrollTo(e, "编解码 · 格式 1"); tap(e, "编解码 · 格式 1"); tap(e, "AMR-NB")
            // A codec change cannot silently discard explicit PCMU PT0 or
            // manufacture a different AMR framing. Remove/add is explicit.
            assertEquals("pcmu", draft.callMedia!!.speechFormats.single().codec.wire)
            assertEquals(0, draft.callMedia!!.speechFormats.single().payloadType)
        }
    } }

    @Test fun aReadOnlyRefreshCanRevealKnownMediaWithoutReconstructingTheDraftOrCrashingThePage() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(callMedia = null, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("refresh must not save") })
            } }
        }.use { e ->
            frames(e)
            row = row.copy(callMedia = ConfigurationCallMedia(), configRevision = NEXT)
            frames(e); scrollTo(e, "添加语音格式")
            assertEquals(ConfigurationCallMedia(), draft.callMedia)
            assertEquals(NEXT, draft.overrides().expectedRevision)
            assertFalse(draft.dirty)
        }
    } }

    @Test fun receiveModeChoicesAreRealDraftControlsAndExplicitNeighborFalseIsKept() = onMotionTestThread { runTest {
        val ready = ready()
        val row = ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING, callMedia = selected,
            configVersion = 1, configRevision = INITIAL)
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("receive choices must only change the draft") })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "接收模式周期 · 格式 1"); tap(e, "接收模式周期 · 格式 1"); tap(e, "每两帧")
            scrollTo(e, "接收相邻模式 · 格式 1"); tap(e, "接收相邻模式 · 格式 1"); tap(e, "不限制相邻")
            val format = ConfigurationCallMediaCodec.encode(draft.callMedia!!).jsonObject.getValue("speechFormats").jsonArray[0].jsonObject
            assertEquals(JsonPrimitive(2), format["receiveModeChangePeriod"])
            assertEquals(JsonPrimitive(false), format["receiveModeChangeNeighbor"])
            assertTrue(draft.onlyNextCallChanges)
        }
    } }

    @Test fun inheritedCatalogFormatsOnlyBecomeEditableOverridesAfterAnExplicitDraftAction() = onMotionTestThread { runTest {
        val ready = ready()
        val paired = selected.copy(packetization = ConfigurationCallPacketization(60, 80))
        val row = ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            callMedia = ConfigurationCallMedia(), configVersion = 1, configRevision = INITIAL,
            facts = ConfigurationFacts(INITIAL, "rf1_" + "a".repeat(64), emptyList(),
                ConfigurationCallMediaFacts(true, "catalog", value = paired)))
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("copying inherited media must only edit the draft") })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "编辑目录格式")
            assertFalse(draft.dirty)
            assertEquals(ConfigurationCallMedia(), draft.overrides().callMedia)
            tap(e, "编辑目录格式")
            assertEquals(paired, draft.callMedia)
            assertTrue(draft.onlyNextCallChanges)
            assertNull(row.facts!!.callMedia!!.overrideValue)
            scrollTo(e, "清除媒体覆盖", direction = -1f)
            tap(e, "清除媒体覆盖"); tap(e, "清除整组")
            assertEquals(ConfigurationCallMedia(), draft.callMedia)
            assertFalse(draft.dirty)
        }
    } }

    @Test fun receivePairValidatesTogetherAndSavesOnlyThroughTheExistingNextCallTransaction() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            callMedia = selected, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        var patch: JsonObject? = null
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, next, complete ->
                        writes++
                        patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        row = row.copy(callMedia = next.callMedia, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row, effect = ConfigurationApplyEffect.NextCall))
                        ConfigurationApplyAcceptance.Accepted
                    })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "接收分包时长"); tap(e, "接收分包时长")
            enterPair(e, "30", "80"); tap(e, "确定")
            assertNull(draft.callMedia!!.packetization)
            assertTrue(texts(e).contains("请成对填写；建议须为 20–240 ms 的完整 20 ms 帧，上限不得小于建议或超过 240 ms"))
            enterPair(e, "60", ""); tap(e, "确定")
            assertNull(draft.callMedia!!.packetization)
            enterPair(e, "60", "80"); tap(e, "确定")
            assertEquals(ConfigurationCallPacketization(60, 80), draft.callMedia!!.packetization)
            assertEquals(0, writes)
            tap(e, "保存配置")
            assertEquals(1, writes)
            assertEquals(setOf("callMedia"), patch!!.getValue("overrides").jsonObject.keys)
            assertEquals(JsonPrimitive(80), patch!!.getValue("overrides").jsonObject.getValue("callMedia").jsonObject
                .getValue("packetization").jsonObject["receiveMaxPtimeMs"])
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun clearingOnlyTheReceivePairKeepsFormatsAndDoesNotRestoreCatalogLeaves() = onMotionTestThread { runTest {
        val ready = ready()
        val row = ready.instances.first().copy(callMedia = selected.copy(packetization = ConfigurationCallPacketization(60, 80)),
            configVersion = 1, configRevision = INITIAL)
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("clearing the pair only edits the draft") })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "接收分包时长"); tap(e, "接收分包时长")
            enterPair(e, "", ""); tap(e, "确定")
            assertEquals(selected, draft.callMedia)
            assertTrue(draft.onlyNextCallChanges)
            assertFalse(ConfigurationCallMediaCodec.encode(draft.callMedia!!).jsonObject.containsKey("packetization"))
        }
    } }

    @Test fun receivePairDialogAtTwoHundredPercentKeepsBothInputsAndRetiresItsOldConfirm() = onMotionTestThread { runTest {
        val ready = ready()
        val row = ready.instances.first().copy(callMedia = selected, configVersion = 1, configRevision = INITIAL)
        val draft = ConfigurationEditorDraft(row)
        var active by mutableStateOf(true)
        MotionTestEnvironment(testScheduler, width = 320, height = 1000) {
            SoftVoWiFiTheme(true) { CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalAppRouteActive provides active) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("retired dialog must not submit") })
            } } }
        }.use { e ->
            frames(e); scrollTo(e, "接收分包时长", height = 1000); tap(e, "接收分包时长")
            enterPair(e, "20", "25")
            val inputs = nodes(e).filter { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertEquals(2, inputs.size)
            inputs.forEach { assertTrue(it.boundsInRoot.width > 0 && it.boundsInRoot.height >= 48f) }
            assertNotNull(visible(e, "建议时长（ms）", 1000))
            assertNotNull(visible(e, "接收上限（ms）", 1000))
            assertNotNull(visible(e, "确定", 1000))
            val confirm = clickable(e, "确定").config[SemanticsActions.OnClick].action!!
            active = false; frames(e); confirm.invoke(); frames(e)
            assertEquals(selected, draft.callMedia)
            assertFalse(draft.dirty)
        }
    } }

    @Test fun lastFormatClearConfirmationCannotOutliveAReadOnlyGroupReplacement() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(
            callMedia = selected.copy(packetization = ConfigurationCallPacketization(60, 80)),
            configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("refresh and retired confirmation must not save") })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "移除格式 1"); tap(e, "移除格式 1")
            val oldConfirm = clickable(e, "清除整组").config[SemanticsActions.OnClick].action!!
            val replaced = selected.copy(packetization = ConfigurationCallPacketization(20, 25))
            row = row.copy(callMedia = replaced, configRevision = NEXT)
            frames(e); oldConfirm.invoke(); frames(e)
            assertEquals(replaced, draft.callMedia, "旧确认不得清除刷新后读回的另一完整媒体组")
            assertFalse(draft.dirty)
            assertEquals(NEXT, draft.overrides().expectedRevision)
        }
    } }

    @Test fun inactivityValidatesExplicitZeroAndClearsOnlyItsOwnLeafBeforeOneNextCallSave() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(callMedia = selected.copy(packetization = ConfigurationCallPacketization(60, 80)),
            configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        var patch: JsonObject? = null
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, next, complete ->
                        writes++
                        patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        row = row.copy(callMedia = next.callMedia, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row, effect = ConfigurationApplyEffect.NextCall))
                        ConfigurationApplyAcceptance.Accepted
                    })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "媒体静默超时"); tap(e, "媒体静默超时")
            enter(e, "-1"); tap(e, "确定")
            assertNull(draft.callMedia!!.inactivity)
            assertTrue(texts(e).contains("请输入 0–86400000 的整数毫秒；0 禁用，留空移除本组超时覆盖"))
            enter(e, "0"); tap(e, "确定")
            assertEquals(ConfigurationCallInactivity(0), draft.callMedia!!.inactivity)
            assertEquals(ConfigurationCallPacketization(60, 80), draft.callMedia!!.packetization)
            assertEquals(0, writes)
            tap(e, "媒体静默超时"); enter(e, ""); tap(e, "确定")
            assertNull(draft.callMedia!!.inactivity)
            tap(e, "媒体静默超时"); enter(e, "160"); tap(e, "确定")
            tap(e, "保存配置")
            assertEquals(1, writes)
            assertEquals(setOf("callMedia"), patch!!.getValue("overrides").jsonObject.keys)
            assertEquals(JsonPrimitive(160), patch!!.getValue("overrides").jsonObject.getValue("callMedia").jsonObject
                .getValue("inactivity").jsonObject["timeoutMs"])
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun inactivityOnlyLastFormatRequiresWholeGroupConfirmationAndKeepsItOnCancel() = onMotionTestThread { runTest {
        val ready = ready()
        val policy = selected.copy(inactivity = ConfigurationCallInactivity(160))
        val row = ready.instances.first().copy(callMedia = policy, configVersion = 1, configRevision = INITIAL)
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready, true, null), draft, section = ConfigurationSection.VOICE,
                    onSubmit = { _, _, _ -> error("confirmation is draft-only") })
            } }
        }.use { e ->
            frames(e); scrollTo(e, "移除格式 1"); tap(e, "移除格式 1"); tap(e, "取消")
            assertEquals(policy, draft.callMedia)
            tap(e, "移除格式 1"); tap(e, "清除整组")
            assertEquals(ConfigurationCallMedia(), draft.callMedia)
        }
    } }

    @Test fun inactivityDialogAtTwoHundredPercentRetiresOnRefreshAndRouteLoss() = onMotionTestThread { runTest {
        for (routeLoss in listOf(false, true)) {
            val ready = ready()
            var active by mutableStateOf(true)
            var row by mutableStateOf(ready.instances.first().copy(callMedia = selected, configVersion = 1, configRevision = INITIAL))
            val draft = ConfigurationEditorDraft(row)
            MotionTestEnvironment(testScheduler, width = 320, height = 1000) {
                SoftVoWiFiTheme(true) { CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalAppRouteActive provides active) { Scaffold {
                    ConfigurationSimEditor(ConfigurationEditorModel(row, ready, true, null), draft, section = ConfigurationSection.VOICE,
                        onSubmit = { _, _, _ -> error("retired callback cannot save") })
                } } }
            }.use { e ->
                frames(e); scrollTo(e, "媒体静默超时", height = 1000); tap(e, "媒体静默超时")
                enter(e, "160")
                assertNotNull(visible(e, "超时（ms）", 1000))
                assertNotNull(visible(e, "确定", 1000))
                val oldConfirm = clickable(e, "确定").config[SemanticsActions.OnClick].action!!
                if (routeLoss) active = false else row = row.copy(callMedia = selected.copy(inactivity = ConfigurationCallInactivity(0)), configRevision = NEXT)
                frames(e); oldConfirm.invoke(); frames(e)
                assertEquals(row.callMedia, draft.callMedia)
                assertFalse(draft.dirty)
            }
        }
    } }

    private fun ready() = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
    private val selected = ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB,
        ConfigurationSpeechFraming.OA, listOf(0, 2, 4), initialMode = 0, payloadType = 110)))
    private fun frames(e: MotionTestEnvironment) = repeat(35) { e.advanceFrameBy(16) }
    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        visit(owner.unmergedRootSemanticsNode)
    }
    private fun texts(e: MotionTestEnvironment) = nodes(e).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun visible(e: MotionTestEnvironment, text: String, height: Int = 850) = nodes(e).firstOrNull { node ->
        if (node.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } != true ||
            node.boundsInRoot.width <= 0f || node.positionInRoot.y < 0f) false else {
            // boundsInRoot is clipped to the viewport: seeing the last 15px of
            // a title is not evidence that the actual text or action is visible.
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.isNotEmpty() && layouts.all {
                it.size.height <= node.boundsInRoot.height + 1f && node.positionInRoot.y + it.size.height <= height
            }
        }
    }
    private fun clickable(e: MotionTestEnvironment, text: String): SemanticsNode {
        val node = nodes(e).last { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true && it.boundsInRoot.width > 0f }
        return generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
    }
    private fun tap(e: MotionTestEnvironment, text: String) { assertTrue(clickable(e, text).config[SemanticsActions.OnClick].action!!.invoke()); frames(e) }
    private fun enter(e: MotionTestEnvironment, value: String) {
        val input = nodes(e).single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frames(e)
    }
    private fun enterPair(e: MotionTestEnvironment, ptime: String, maximum: String) {
        val inputs = nodes(e).filter { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertEquals(2, inputs.size)
        inputs.zip(listOf(ptime, maximum)).forEach { (input, value) ->
            assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        }
        frames(e)
    }
    private fun scrollTo(e: MotionTestEnvironment, text: String, direction: Float = 1f, height: Int = 850) {
        repeat(80) {
            if (visible(e, text, height) != null) return
            nodes(e).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 200f * direction)
            repeat(5) { e.advanceFrameBy(16) }
        }
        error("text not reachable: $text")
    }
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

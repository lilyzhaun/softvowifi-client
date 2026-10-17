@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class S7EnvironmentRestorationRenderTest {
    /** 真实Compose保存/销毁/新scene恢复，不是Android Activity重建；设备E4仍须独立取证。 */
    @Test fun callsSearchSurvivesSavedCompositionRecreationAndRealLoadingBackToTheSameOwner() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 20.seconds) {
            val saved = FakeCallHistoryRepository()
            for ((index, peer) in listOf("155501", "155502").withIndex()) {
                saved.upsert(OWNER_A, historyWrite(correlation = "s7-restore-$index", peer = peer,
                    startedAtMs = T0 + index * 10_000, connectedAtMs = T0 + index * 10_000 + 1000,
                    endedAtMs = T0 + index * 10_000 + 2000, durationSec = 1, outcome = CallOutcome.COMPLETED))
            }
            saved.selectOwner(OWNER_A)
            var gate: CompletableDeferred<Unit>? = null
            var ownerWrites = 0; var historyWrites = 0; var deletes = 0; var interrupts = 0; var callbacks = 0; var communication = 0
            val repository = object : CallHistoryRepository by saved {
                override suspend fun listByOwner(ownerId: SimOwnerId): List<CallRecord> {
                    gate?.await()
                    return saved.listByOwner(ownerId)
                }
                override suspend fun selectOwner(ownerId: SimOwnerId) { ownerWrites++; saved.selectOwner(ownerId) }
                override suspend fun upsert(ownerId: SimOwnerId, write: CallHistoryWrite) {
                    historyWrites++; saved.upsert(ownerId, write)
                }
                override suspend fun clearByOwner(ownerId: SimOwnerId) { deletes++; saved.clearByOwner(ownerId) }
                override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                    deletes++; saved.deleteByIds(ownerId, ids)
                }
                override suspend fun interruptOpen(endedAtMs: Long) { interrupts++; saved.interruptOpen(endedAtMs) }
            }
            val source = CallSourceAggregate(
                EngineMessageRuntime({ emptyList() }, { communication++; error("没有实际引擎连接") }, { T0 }, { emptyList() }),
                FakeCallAudioController(), CallControlTransport { _, _, _, _ -> communication++; error("不得执行通话RPC") },
                backgroundScope.coroutineContext + dispatcher, repository, { null }, { error("不得创建外呼记录") },
            )
            // 两次scene必须来自同一调用点；registry只接收performSave结果，不保存测试UiState或补写query。
            fun scene(registry: SaveableStateRegistry) = MotionTestEnvironment(testScheduler, 420, 900, dispatcher = dispatcher) {
                s4SmsHost {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        val snapshot by source.historySnapshot.collectAsState()
                        val state by source.state.collectAsState()
                        CallsScreen(state, true, { callbacks++ }, snapshot.view,
                            { callbacks++ }, { callbacks++ }, { callbacks++ }, { callbacks++ }, { callbacks++ },
                            historySnapshot = snapshot, historyOwners = snapshot.owners,
                            onOpenHistory = { callbacks++ }, onCallback = { callbacks++ }, callbackEnabled = true,
                            onDeleteRecords = { _, _ -> callbacks++ })
                    }
                }
            }
            fun field(e: MotionTestEnvironment, message: String) = assertNotNull(
                s4Nodes(e, false).singleOrNull { it.config.contains(SemanticsActions.SetText) &&
                    !it.config.contains(SemanticsProperties.Disabled) }, message)
            fun rows(e: MotionTestEnvironment) = s4Nodes(e, false).filter {
                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" &&
                    it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0
            }
            var current: MotionTestEnvironment? = null
            try {
                testScheduler.runCurrent()
                val initial = assertIs<CallHistoryState.Ready>(source.historySnapshot.value.view)
                assertEquals(OWNER_A, initial.owner)
                assertEquals(2, initial.records.size)
                val originalRecords = saved.listByOwner(OWNER_A)
                val originalOwner = saved.lastOwner()
                val initialUpserts = saved.upsertCount
                val baselineEffects = listOf(ownerWrites, historyWrites, deletes, interrupts, callbacks, communication)
                val registry = SaveableStateRegistry(null) { true }
                val first = scene(registry).also { current = it }
                s4Frames(first, 40)
                assertEquals(2, rows(first).size, "前提：真实同owner两条历史均已布局")
                assertEquals(setOf("155501", "155502"), listOf("155501", "155502").filter { peer ->
                    rows(first).any { s4Matches(it, peer) }
                }.toSet())
                val search = s4Action(first, "搜索通话记录")
                assertTrue(assertNotNull(search.config[SemanticsActions.OnClick].action).invoke())
                s4Frames(first, 24)
                val input = field(first, "前提：原搜索展开后须有真实EditText语义")
                assertTrue(assertNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString("155501")))
                s4Frames(first, 24)
                assertEquals("155501", field(first, "前提：原搜索输入存在").config[SemanticsProperties.EditableText].text)
                assertEquals(1, rows(first).size)
                assertTrue(s4Matches(rows(first).single(), "155501"))
                assertFalse(rows(first).any { s4Matches(it, "155502") }, "非零query必须真实过滤另一个号码")
                assertEquals(baselineEffects, listOf(ownerWrites, historyWrites, deletes, interrupts, callbacks, communication))
                val restoredMap = registry.performSave()
                first.close(); current = null // 完整销毁原composition，不在同scene改尺寸或key冒充恢复。
                assertFalse(saved.closed, "关闭scene不能销毁持续持有的同一历史store")

                gate = CompletableDeferred()
                val confirmedChoice = source.historyOwners.value.single { it.owner == OWNER_A && it.selected }
                source.selectHistoryOwner(confirmedChoice)
                assertEquals(CallHistoryState.Loading, source.historySnapshot.value.view, "必须由真实source进入Loading")
                val restoredRegistry = SaveableStateRegistry(restoredMap) { true }
                val second = scene(restoredRegistry).also { current = it }
                assertNotSame(first.scene, second.scene, "必须创建新的ImageComposeScene")
                s4Frames(second, 24)
                assertEquals(CallHistoryState.Loading, source.historySnapshot.value.view)
                val restoredInput = field(second, "E4 restored search missing：保存后新scene在真实Loading期间仍须展开搜索")
                assertEquals("155501", restoredInput.config[SemanticsProperties.EditableText].text,
                    "E4 restored query missing：不得由测试手填恢复query")
                gate!!.complete(Unit); s4Frames(second, 40)
                val recovered = assertIs<CallHistoryState.Ready>(source.historySnapshot.value.view)
                assertEquals(OWNER_A, recovered.owner)
                assertEquals("155501", field(second, "E4 restored search missing：同confirmed owner恢复后不可收起搜索")
                    .config[SemanticsProperties.EditableText].text)
                assertEquals(1, rows(second).size)
                assertTrue(s4Matches(rows(second).single(), "155501"))
                assertFalse(rows(second).any { s4Matches(it, "155502") })
                assertEquals(initial.records, recovered.records)
                assertEquals(originalRecords, saved.listByOwner(OWNER_A))
                assertEquals(originalOwner, saved.lastOwner())
                assertEquals(initialUpserts, saved.upsertCount)
                assertEquals(baselineEffects, listOf(ownerWrites, historyWrites, deletes, interrupts, callbacks, communication),
                    "Compose保存重建/同owner重读不得触发业务回调、通信或持久化写")
            } finally {
                gate?.complete(Unit); current?.close(); source.close(); testScheduler.runCurrent(); saved.close()
            }
        }
    }

    /** E4同进程真实Compose保存重建；不冒充Activity/进程死亡，不把草稿/配置写入registry。 */
    @Test fun configurationDisclosureAndAdvancedSettingsRestoreOnlyUiExpansionWithoutSavingDraft() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 20.seconds) {
            val fixture = FixtureConfigurationSource("ready"); var submits = 0; var callbacks = 0
            val source = object : ConfigurationSource by fixture {
                override fun submit(instanceId: ConfigurationInstanceId, overrides: ConfigurationOverrides,
                    onComplete: (ConfigurationApplyOutcome) -> Unit): ConfigurationApplyAcceptance {
                    submits++; return fixture.submit(instanceId, overrides) { callbacks++; onComplete(it) }
                }
            }
            val original = assertIs<ConfigurationUiState.Ready>(source.state.value)
            assertEquals(2, original.instances.size)
            val ids = original.instances.map { it.instanceId }; val safeKeys = ids.map { it.value }.toSet()
            val draft = ConfigurationEditorDraft(original.instances.first()); val originalDraft = draft
            fun fields() = listOf(draft.transport.name, draft.smsc.text.toString(), draft.epdgFqdn.text.toString(),
                draft.imsRealm.text.toString(), draft.dns.text.toString())
            val originalFields = fields(); val completion = draft.completionSequence
            lateinit var expansion: ConfigurationExpansionState
            fun uiOnly(value: Any?): Boolean = when (value) {
                null, is Boolean, is Int -> true // library scroll/dropdown offsets are UI state, not configuration fields.
                is State<*> -> uiOnly(value.value)
                is String -> value in safeKeys
                is List<*> -> value.all { uiOnly(it) }
                is Map<*, *> -> value.all { (k, v) -> k is String && k in safeKeys && v is Boolean }
                else -> false
            }
            fun registry(restored: Map<String, List<Any?>>? = null) = SaveableStateRegistry(restored, ::uiOnly)
            // 相同调用点，只由生产holder/header和高级组件拥有各自真实展开状态。
            fun scene(registry: SaveableStateRegistry) = MotionTestEnvironment(testScheduler, 440, 1800, dispatcher = dispatcher) {
                s4SmsHost { CompositionLocalProvider(LocalSaveableStateRegistry provides registry, LocalDensity provides Density(1f, 1f)) {
                    val configuration by source.state.collectAsState()
                    val instances = (configuration as ConfigurationUiState.Ready).instances
                    val state = rememberConfigurationExpansionState()
                    SideEffect { expansion = state }
                    Column {
                        instances.forEachIndexed { i, instance ->
                            val expanded = state.isExpanded(instance.instanceId)
                            ConfigurationDisclosureHeader("合成配置 ${i + 1}", "独立展开", expanded,
                                configurationDisclosureInteractionContract(expanded).copy(headerClickable = true,
                                    cardClickable = false, headerUsesTopSquircleClip = false),
                                { state.toggle(instance.instanceId) })
                            ConfigurationDisclosureContent(expanded) { PhoneText("合成配置内容 ${i + 1}") }
                        }
                        // 独立持续组合，保证parent恢复失败不掩盖Advanced自身的恢复失败。
                        ConfigurationAdvancedSettings(instances.first(), draft, enabled = true)
                    }
                } }
            }
            fun smscField(e: MotionTestEnvironment): Boolean {
                val label = s4Nodes(e).firstOrNull { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "SMSC" } == true
                } ?: return false
                val fields = s4Nodes(e).filter { it.config.contains(SemanticsActions.SetText) }
                return generateSequence(label) { it.parent }.any { ancestor ->
                    fields.count { field -> generateSequence(field) { it.parent }.any { it.id == ancestor.id } } == 1
                }
            }
            var current: MotionTestEnvironment? = null
            try {
                val savedRegistry = registry(); val first = scene(savedRegistry).also { current = it }; s4Frames(first, 40)
                assertFalse(expansion.isExpanded(ids[0])); assertFalse(expansion.isExpanded(ids[1]))
                assertFalse(s4HasText(first, "底层网络")); assertFalse(smscField(first))
                assertTrue(assertNotNull(s4Action(first, "合成配置 1").config[SemanticsActions.OnClick].action).invoke())
                s4Frames(first, 40); assertTrue(expansion.isExpanded(ids[0])); assertFalse(expansion.isExpanded(ids[1]))
                assertTrue(assertNotNull(s4Action(first, "高级设置").config[SemanticsActions.OnClick].action).invoke())
                s4Frames(first, 40); assertTrue(s4HasText(first, "底层网络")); assertTrue(smscField(first), "真实高级SMSC TextField已展开")
                assertTrue(fields() == originalFields); assertSame(original, source.state.value)
                val savedMap = savedRegistry.performSave()
                assertTrue(savedMap.values.flatten().all(::uiOnly), "只能保存UI布尔/合成展开key，不得保存完整配置或draft")
                assertTrue(savedMap.keys.none { it in originalFields.filter(String::isNotEmpty) }, "registry key不得含配置值")
                val previousExpansion = expansion; first.close(); current = null
                val restored = scene(registry(savedMap)).also { current = it }; s4Frames(restored, 40)
                assertNotSame(first.scene, restored.scene); assertNotSame(previousExpansion, expansion)
                val failures = mutableListOf<String>()
                if (!expansion.isExpanded(ids[0])) failures += "E4 restored first disclosure missing"
                if (expansion.isExpanded(ids[1])) failures += "E4 restored second disclosure must stay collapsed"
                if (!s4HasText(restored, "底层网络")) failures += "E4 restored advanced settings missing"
                if (!smscField(restored)) failures += "E4 restored advanced SMSC field missing"
                assertSame(originalDraft, draft); assertSame(original, source.state.value)
                assertTrue(fields() == originalFields, "五字段仅内存草稿不能因保存重建被修改")
                assertEquals(completion, draft.completionSequence); assertFalse(draft.pending)
                assertEquals(0, submits); assertEquals(0, callbacks)
                assertTrue(failures.isEmpty(), failures.joinToString("\n"))
            } finally { current?.close(); source.close(); testScheduler.runCurrent() }
        }
    }
}

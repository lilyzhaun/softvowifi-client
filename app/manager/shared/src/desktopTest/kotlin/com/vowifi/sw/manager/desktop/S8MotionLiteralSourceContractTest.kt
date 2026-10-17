package com.vowifi.sw.manager.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class S8MotionLiteralSourceContractTest {
    private data class Site(val offset: Int, val expression: String, val prefix: String)
    private data class Kept(val path: String, val owner: String, val expression: String, val limit: Int = 1)
    private val whitespace = Regex("\\s+")
    private val call = Regex("\\b(?:tween|spring|TweenSpec|SpringSpec)\\s*(?:<\\s*[\\w.,?\\s]+>\\s*)?\\(")
    private val number = Regex("(?<![\\w.])[+-]?(?:[0-9][0-9_]*(?:\\.[0-9][0-9_]*)?|\\.[0-9][0-9_]*)(?:[eE][+-]?[0-9_]+)?[fFdDlL]?(?!\\w)")

    // 有限词法扫描：字符串正文屏蔽，但${...}中的真实代码递归保留；不做类型/别名绑定。
    private fun masked(source: String): String {
        val out = source.toCharArray()
        fun blank(a: Int, b: Int) { for (k in a until minOf(b, out.size)) if (out[k] != '\n') out[k] = ' ' }
        lateinit var code: (Int, Boolean) -> Int
        fun quoted(start: Int): Int {
            val quote = source[start]; val raw = source.startsWith("\"\"\"", start)
            val close = if (raw) "\"\"\"" else quote.toString(); var i = start + close.length
            blank(start, i)
            while (i < source.length) {
                when {
                    source.startsWith(close, i) -> { blank(i, i + close.length); return i + close.length }
                    !raw && quote != '`' && source[i] == '\\' -> { blank(i, i + 2); i += 2 }
                    quote == '"' && source.startsWith("\${", i) -> { blank(i, i + 2); i = code(i + 2, true) }
                    else -> { blank(i, i + 1); i++ }
                }
            }
            error("unterminated quoted literal")
        }
        code = fun(start: Int, template: Boolean): Int {
            var i = start; var braces = 0
            while (i < source.length) {
                val begin = i
                when {
                    source.startsWith("//", i) -> {
                        i = source.indexOf('\n', i).let { if (it < 0) source.length else it }; blank(begin, i)
                    }
                    source.startsWith("/*", i) -> {
                        var depth = 1; i += 2
                        while (i < source.length && depth > 0) when {
                            source.startsWith("/*", i) -> { depth++; i += 2 }
                            source.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                        require(depth == 0) { "unterminated comment" }; blank(begin, i)
                    }
                    source[i] == '"' || source[i] == '\'' || source[i] == '`' -> i = quoted(i)
                    source[i] == '{' -> { braces++; i++ }
                    source[i] == '}' && template && braces == 0 -> { blank(i, i + 1); return i + 1 }
                    source[i] == '}' -> { braces--; i++ }
                    else -> i++
                }
            }
            require(!template) { "unterminated template" }; return i
        }
        code(0, false); return String(out)
    }

    private fun sites(source: String): List<Site> {
        val code = masked(source)
        return call.findAll(code).mapNotNull { match ->
            var end = match.range.last + 1; var depth = 1
            while (end < code.length && depth > 0) {
                when (code[end++]) { '(' -> depth++; ')' -> depth-- }
            }
            require(depth == 0) { "unbalanced animation call" }
            val body = code.substring(match.range.last + 1, end - 1)
            if (!number.containsMatchIn(body)) null else Site(match.range.first,
                code.substring(match.range.first, end).replace(whitespace, ""),
                code.substring(0, match.range.first).replace(whitespace, "").takeLast(220))
        }.toList()
    }

    @Test fun detectorRecognizesNumericGenericNamedAndMultilineCallsButNotNonCode() {
        val sample = listOf("tween(120)", "tween(durationMillis = 120)", "tween<Float>(\n durationMillis = 120)",
            "tween(MotionTokens.DurBase, delayMillis = 12)", "tween(MotionTokens.DurBase, 12, MotionTokens.EaseStandard)",
            "tween(easing = CubicBezierEasing(.2f, .8f, .2f, 1f))", "spring(stiffness = 7e2f)", "spring(1f, 500f, .001f)",
            "spring<Float>(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = .001f)",
            "TweenSpec<Float>(durationMillis = 120)", "SpringSpec<Float>(stiffness = 500f)")
        assertEquals(sample.map { it.replace(whitespace, "") }, sites(sample.joinToString("\n")).map { it.expression })
        val ignored = "// tween(1)\n/* spring(2f) /* tween(3) */ spring(4f) */\n" +
            "val a = \"tween(5) \\\"spring(6f)\\\"\"\nval b = \"\"\"spring(7f)\ntween(8)\"\"\"\n" +
            "val ch = '('; val digit = '9'; delay(4000); withTimeout(720) { wait() }\n" +
            "tween(durationMillis = MotionTokens.DurFast, delayMillis = MotionTokens.DurBase, easing = MotionTokens.EaseStandard); " +
            "spring<Float>(dampingRatio = token.dampingRatio, stiffness = MotionTokens.SpringSpatial.stiffness)"
        assertTrue(sites(ignored).isEmpty(), "注释/字符串/字符与业务超时不是动画字面量")
        assertEquals("tween(120)", sites("/* tween(9) */ tween(/* duration */ 120)").single().expression)
        val template = "val s = \"tween(1) ${'$'}{tween(120)} ${'$'}{\"spring(2f)\"}\""
        val raw = "\"\"\"tween(2) ${'$'}{spring(stiffness = 700f)}\"\"\""
        assertEquals(listOf("tween(120)", "spring(stiffness=700f)"), sites("$template\n$raw").map { it.expression })
    }

    @Test fun actualUiAnimationCallsUseTokensExceptExactRetainedComponentPhysics() {
        val relative = "shared/src"
        val here = File("").absoluteFile
        val src = listOf(relative, "../$relative", "../../$relative", "app/manager/$relative")
            .map { File(here, it) }.firstOrNull { File(it, "commonMain/kotlin/com/vowifi/sw/manager/ui").isDirectory }
        assertTrue(src != null, "必须找到真实shared/src；找不到源码不能空扫通过")
        val root = requireNotNull(src); val common = "commonMain/kotlin/com/vowifi/sw/manager/ui/"
        val kept = listOf(
            Kept(common + "DampedDragAnimation.kt", "valvalueSpec=", "spring(1f,1000f,visibilityThreshold)"),
            Kept(common + "DampedDragAnimation.kt", "valvelocitySpec=", "spring(0.5f,300f,visibilityThreshold*10f)"),
            Kept(common + "DampedDragAnimation.kt", "valpressSpec=", "spring(1f,1000f,0.001f)"),
            Kept(common + "DampedDragAnimation.kt", "valscaleXSpec=", "spring(0.6f,250f,0.001f)"),
            Kept(common + "DampedDragAnimation.kt", "valscaleYSpec=", "spring(0.7f,250f,0.001f)"),
            Kept(common + "liquid/InteractiveHighlight.kt", "valPressSpec=", "spring(0.5f,300f,0.001f)"),
            Kept(common + "liquid/InteractiveHighlight.kt", "valPositionSpec=", "spring(0.5f,300f,Offset.VisibilityThreshold)"),
            Kept(common + "SlidingFloatingBottomBar.kt", "rawOffset.animateTo(0f,", "spring(1f,300f,0.5f)", 3),
            Kept(common + "InCallScreenContent.kt", "valprogress=animateFloatAsState(if(keypad)1felse0f,",
                "spring(dampingRatio=1f,stiffness=500f,visibilityThreshold=.001f)"),
            Kept(common + "CommunicationContextMenu.kt", "progress.animateTo(1f,", "tween(260,easing=CubicBezierEasing(.2f,.8f,.2f,1f))"),
            Kept(common + "CommunicationContextMenu.kt", "progress.animateTo(0f,", "tween(170,easing=exitEasing)"),
        )
        val counts = IntArray(kept.size); val violations = mutableListOf<String>()
        val files = listOf("commonMain", "androidMain", "desktopMain").flatMap { set ->
            val ui = File(root, "$set/kotlin/com/vowifi/sw/manager/ui")
            assertTrue(ui.isDirectory, "$set/ui不存在，不能静默漏扫")
            ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also { assertTrue(it.isNotEmpty(), "$set/ui为空") }
        }
        files.sortedBy { it.path }.forEach { file ->
            val source = file.readText(); val path = file.relativeTo(root).invariantSeparatorsPath
            if (path == common + "MotionTokens.kt") return@forEach
            sites(source).forEach { site ->
                val rule = kept.indexOfFirst { it.path == path && site.prefix.endsWith(it.owner) && it.expression == site.expression }
                if (rule < 0 || ++counts[rule] > kept[rule].limit) {
                    val line = 1 + source.take(site.offset).count { it == '\n' }
                    violations += "$path:$line ${site.expression}"
                }
            }
        }
        assertTrue(violations.isEmpty(), "动画参数须引用MotionTokens；精确例外外的字面量：\n" + violations.joinToString("\n"))
        assertEquals(kept.map { it.limit }, counts.toList(), "11例外按完整sourceSet路径/owner/spec/精确次数匹配；令牌迁移后显式删规则")
    }
}

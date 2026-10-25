package com.vowifi.sw.manager.data

import kotlinx.serialization.json.*

enum class NotificationDiagnosticOwner { OBSERVER, INCOMING, MICROPHONE, COORDINATOR }
enum class NotificationDiagnosticAction { PROMOTE, NOTIFY, CANCEL, RETIRE, DETACH }
enum class NotificationDiagnosticChannel { OBSERVER, INCOMING, ONGOING, OTHER }

/** Metadata of the request, captured before the call; not proof of live system ownership or posting.
 * Never accepts text, Person, Intent, URI or exception messages. */
data class NotificationDiagnostic(
    val owner: NotificationDiagnosticOwner,
    val action: NotificationDiagnosticAction,
    val id: Int,
    val channel: NotificationDiagnosticChannel? = null,
    val channelExists: Boolean? = null,
    val importance: Int? = null,
    val smallIcon: Boolean? = null,
    val iconLoadable: Boolean? = null,
    val callStyle: Boolean? = null,
    val foregroundFlag: Boolean? = null,
    val fullScreen: Boolean? = null,
) {
    internal fun valid(): Boolean = when (owner) {
        NotificationDiagnosticOwner.OBSERVER -> id == 1
        NotificationDiagnosticOwner.INCOMING -> id == 100
        NotificationDiagnosticOwner.MICROPHONE -> id == 101
        NotificationDiagnosticOwner.COORDINATOR -> id == 100 || id == 101 || id == 102
    } && (importance == null || importance == -1000 || importance in 0..5)

    internal fun json(): JsonObject = buildJsonObject {
        put("owner", owner.name.lowercase()); put("action", action.name.lowercase()); put("id", id)
        channel?.let { put("channel", it.name.lowercase()) }
        channelExists?.let { put("channel_exists", it) }; importance?.let { put("importance", it) }
        smallIcon?.let { put("small_icon", it) }; iconLoadable?.let { put("icon_loadable", it) }
        callStyle?.let { put("call_style", it) }; foregroundFlag?.let { put("foreground_flag", it) }
        fullScreen?.let { put("full_screen", it) }
    }

    internal companion object {
        private val fields = setOf("owner", "action", "id", "channel", "channel_exists", "importance",
            "small_icon", "icon_loadable", "call_style", "foreground_flag", "full_screen")
        private val booleans = setOf("channel_exists", "small_icon", "icon_loadable", "call_style", "foreground_flag", "full_screen")
        private fun number(j: JsonObject, key: String): Int? = (j[key] as? JsonPrimitive)?.let {
            if (it.isString) null else it.intOrNull
        }
        private fun boolean(j: JsonObject, key: String): Boolean? = (j[key] as? JsonPrimitive)?.let {
            if (it.isString) null else it.booleanOrNull
        }
        private inline fun <reified T : Enum<T>> enumValue(j: JsonObject, key: String): T? =
            (j[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { text ->
                enumValues<T>().firstOrNull { it.name.lowercase() == text }
            }
        fun parse(value: JsonElement?): NotificationDiagnostic? {
            val j = value as? JsonObject ?: return null
            if (j.keys.any { it !in fields } || booleans.any { it in j && boolean(j, it) == null } ||
                "importance" in j && number(j, "importance") == null ||
                "channel" in j && enumValue<NotificationDiagnosticChannel>(j, "channel") == null) return null
            return NotificationDiagnostic(
                enumValue<NotificationDiagnosticOwner>(j, "owner") ?: return null,
                enumValue<NotificationDiagnosticAction>(j, "action") ?: return null,
                number(j, "id") ?: return null,
                enumValue(j, "channel"), boolean(j, "channel_exists"), number(j, "importance"),
                boolean(j, "small_icon"), boolean(j, "icon_loadable"), boolean(j, "call_style"),
                boolean(j, "foreground_flag"), boolean(j, "full_screen"),
            ).takeIf { it.valid() }
        }
    }
}

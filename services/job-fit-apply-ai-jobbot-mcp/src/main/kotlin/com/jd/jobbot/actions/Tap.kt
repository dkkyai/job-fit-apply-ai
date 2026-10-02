package com.jd.jobbot.actions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A button tap as the Hermes plugin forwards it. */
@Serializable
data class TapRequest(
    val verb: String,
    val seq: Long,
    @SerialName("user_id") val userId: String,
    @SerialName("chat_id") val chatId: String,
    /** The card's Telegram message id, for the audit record. */
    @SerialName("message_id") val messageId: Long? = null,
    /** The card's send time (Telegram `message.date`, epoch seconds) — the expiry clock. */
    @SerialName("message_date") val messageDate: Long? = null,
)

@Serializable
data class InlineButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String,
)

/**
 * What the plugin should do in Telegram. Every field is optional except [outcome]:
 *  - [toast]: the answerCallbackQuery text (≤ 200 chars).
 *  - [reply]: a message to send as a reply to the card.
 *  - [actionRow]: replace the card's action-button row; `[]` removes it, null leaves it alone.
 *    URL buttons (View Report / Resume) are kept by the plugin.
 *  - [agentPrompt]: wake the agent with this text, in the user's chat, as a reply to the card.
 */
@Serializable
data class TapResponse(
    val outcome: String,
    val toast: String? = null,
    val reply: String? = null,
    @SerialName("action_row") val actionRow: List<InlineButton>? = null,
    @SerialName("agent_prompt") val agentPrompt: String? = null,
)

/** Everything a verb handler needs about one validated tap. */
data class TapContext(
    val request: TapRequest,
    val event: JsonObject,
    val jobKey: String,
    val ref: String,
)

/** One verb's behavior. Handlers run only after the tap passed every shared check. */
fun interface VerbHandler {
    fun handle(ctx: TapContext, store: ActionStore): TapResponse
}

object Outcomes {
    const val DONE = "done"
    const val ALREADY = "already"
    const val NOT_IMPLEMENTED = "not_implemented"
    const val AGENT = "agent"
    const val DRY_RUN = "dry_run"
    const val EXPIRED = "expired"
    const val REFUSED = "refused"
    const val ERROR = "error"
}

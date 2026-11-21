package com.proxai.hermes.data

import org.json.JSONObject

/**
 * F1 (0.9.9): a question a Hermes yes-gate on the PC is waiting on (an app build or delete, a mode switch, a
 * scheduled job, a vault write, a GitHub change). The apps server hands it over (GET /apps/v1/approvals) after
 * each reply, and ProxAI shows it with Reject and Accept, in Chat and in the power-button assistant.
 *
 * The buttons only send "no" / "yes" as his next message, with a note saying it came from the pop-up: the PC
 * decides exactly as when he types it, so a button can never approve more than his own yes would.
 */
data class Approval(
    val id: String,
    val session: String,
    val plugin: String,
    val question: String,
    val detail: String,
    val expiresAt: Long,
) {
    /** The pop-up's title: what kind of thing is asking. */
    val title: String
        get() = when (plugin) {
            "proxai-apps" -> "App builder"
            "proxai-mode" -> "PC mode"
            "proxai-schedule" -> "Scheduled job"
            "proxai-vault-write" -> "Vault"
            "proxai-github" -> "GitHub"
            else -> "ProxAI"
        }

    companion object {
        /** {"approval": {...} | null} from the apps server; null when there's nothing (or it can't be read). */
        fun parse(json: String): Approval? = try {
            val o = JSONObject(json).optJSONObject("approval")
            val id = o?.optString("id").orEmpty()
            val question = o?.optString("question").orEmpty().trim()
            if (o == null || id.isBlank() || question.isBlank()) null
            else Approval(id, o.optString("session"), o.optString("plugin"), question, o.optString("detail").trim(),
                (o.optDouble("expires", 0.0) * 1000).toLong())
        } catch (e: org.json.JSONException) {
            null
        }

        /** The note before his "yes" / "no" on the wire. One [...] block with no brackets inside: the PC's
         *  plugins drop [...] notes before reading his words, so they see a plain yes or no. */
        fun note(a: Approval, accept: Boolean): String =
            "[Approval: Nathan tapped ${if (accept) "Accept" else "Reject"} in ProxAI's pop-up for: " +
                "“${ChatNotices.unbracket(a.question).take(300)}”]"

        /** What he sends: the words the PC's yes-gates read. */
        fun word(accept: Boolean) = if (accept) "yes" else "no"

        /** His own chat bubble. */
        fun shown(accept: Boolean) = if (accept) "✅ Accept" else "✖️ Reject"
    }
}

/** A question on screen, with the chat it belongs to (the answer goes to that chat's Hermes session). */
data class PendingApproval(val channel: String, val approval: Approval)

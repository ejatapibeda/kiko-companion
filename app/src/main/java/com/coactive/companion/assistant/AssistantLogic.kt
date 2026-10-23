package com.coactive.companion.assistant

import com.coactive.companion.llm.ChatMessage
import com.coactive.companion.ui.face.Expression

enum class Phase { IDLE, LISTENING, THINKING, TALKING, SLEEPING }

/** Ekspresi wajah dari fase asisten + emosi jawaban terakhir. */
object FaceLogic {
    fun expressionFor(phase: Phase, emotion: Expression): Expression = when (phase) {
        Phase.SLEEPING -> Expression.SLEEPY
        Phase.LISTENING -> if (emotion == Expression.SLEEPY) Expression.NEUTRAL else Expression.NEUTRAL
        Phase.THINKING -> Expression.THINKING
        Phase.TALKING -> when (emotion) {
            Expression.HAPPY, Expression.EXCITED, Expression.SAD, Expression.SURPRISED, Expression.CURIOUS -> emotion
            else -> Expression.TALKING
        }
        Phase.IDLE -> emotion
    }

    /** Apakah sudah waktunya tidur (ngantuk) setelah tidak ada interaksi. */
    fun shouldSleep(phase: Phase, lastInteractionMs: Long, nowMs: Long, sleepAfterSeconds: Int): Boolean =
        phase == Phase.IDLE && sleepAfterSeconds > 0 && nowMs - lastInteractionMs >= sleepAfterSeconds * 1000L
}

data class ParsedReply(val text: String, val emotion: Expression, val sources: List<com.coactive.companion.search.WebSource> = emptyList())

/** Mengambil tag emosi dari jawaban LLM ("[happy] ..."), atau menebak dengan heuristik. */
object EmotionParser {
    private val tag = Regex("""^\s*[\[(<]\s*(neutral|happy|excited|curious|thinking|surprised|sad|sleepy|talking)\s*[\])>]\s*""", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("""\[(neutral|happy|excited|curious|thinking|surprised|sad|sleepy|talking)]\s*""", RegexOption.IGNORE_CASE)

    private val think = Regex("""(?s)<think>.*?</think>\s*""")

    fun parse(rawIn: String): ParsedReply {
        val raw = rawIn.replace(think, "") // model "thinking" (mis. Qwen) kadang menyertakan penalaran di content
        val m = tag.find(raw)
        val text = raw.replace(anyTag, "").trim()
        if (m != null) {
            val e = when (m.groupValues[1].lowercase()) {
                "happy" -> Expression.HAPPY; "excited" -> Expression.EXCITED; "curious" -> Expression.CURIOUS
                "thinking" -> Expression.THINKING; "surprised" -> Expression.SURPRISED; "sad" -> Expression.SAD
                "sleepy" -> Expression.SLEEPY; else -> Expression.NEUTRAL
            }
            return ParsedReply(text, e)
        }
        return ParsedReply(text, guess(text))
    }

    fun guess(text: String): Expression {
        val t = text.lowercase()
        return when {
            listOf("maaf", "sayang sekali", "sedih", "turut berduka", "gagal", "sorry", "unfortunately").any { it in t } -> Expression.SAD
            listOf("wah", "wow", "astaga", "serius?", "hah?", "really?").any { it in t } -> Expression.SURPRISED
            listOf("hebat", "keren", "selamat", "mantap", "asyik", "yay", "great", "awesome").any { it in t } -> Expression.EXCITED
            listOf("hmm", "sepertinya", "mungkin", "let me think").any { it in t } -> Expression.THINKING
            t.trimEnd().endsWith("?") -> Expression.CURIOUS
            listOf("senang", "tentu", "siap", "oke", "baik", "halo", "sure", "happy").any { it in t } || t.contains("!") -> Expression.HAPPY
            else -> Expression.NEUTRAL
        }
    }
}

/** Memori sesi: menyimpan N giliran terakhir (user + jawaban akhir asisten). */
class ConversationMemory(private val maxTurns: Int = 12) {
    private val turns = ArrayDeque<Pair<String, String>>()
    val size get() = turns.size

    fun add(user: String, assistant: String) {
        turns.addLast(user to assistant)
        while (turns.size > maxTurns) turns.removeFirst()
    }

    fun messages(): List<ChatMessage> = turns.flatMap { (u, a) -> listOf(ChatMessage.user(u), ChatMessage.assistant(a)) }
    fun clear() = turns.clear()
}

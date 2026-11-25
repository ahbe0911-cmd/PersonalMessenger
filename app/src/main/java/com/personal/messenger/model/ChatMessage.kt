package com.personal.messenger.model

import java.util.UUID

enum class MessageStatus { SENT, DELIVERED, READ }
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val outgoing: Boolean = true,
    val status: MessageStatus = MessageStatus.SENT,
    val replyTo: String? = null
)
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val query: String = "",
    val replyTo: String? = null,
    val dark: Boolean? = null,
    val initializing: Boolean = true,
    val deviceName: String? = null,
    val connection: String = "",
    val busy: Boolean = false,
    val error: String? = null
) {
    val filtered: List<ChatMessage> get() = messages.filter {
        normalize(it.text).contains(normalize(query.trim()))
    }
}
fun normalize(text: String): String = text.replace('ي', 'ی').replace('ك', 'ک').lowercase()

package com.personal.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.personal.messenger.model.ChatMessage
import com.personal.messenger.model.ChatState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ChatViewModel : ViewModel() {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    fun draft(value: String) = mutable.update { it.copy(draft = value.take(16000)) }
    fun search(value: String) = mutable.update { it.copy(query = value) }
    fun theme(currentDark: Boolean) = mutable.update { it.copy(dark = !currentDark) }
    fun reply(id: String?) = mutable.update { it.copy(replyTo = id) }
    fun delete(id: String) = mutable.update { s ->
        s.copy(messages = s.messages.filterNot { it.id == id }, replyTo = s.replyTo.takeUnless { it == id })
    }
    fun send() = mutable.update { s ->
        if (s.draft.isBlank()) s else s.copy(
            messages = s.messages + ChatMessage(text = s.draft, replyTo = s.replyTo),
            draft = "", replyTo = null
        )
    }
}

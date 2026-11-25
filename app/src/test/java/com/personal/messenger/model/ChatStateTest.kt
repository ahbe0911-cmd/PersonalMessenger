package com.personal.messenger.model
import org.junit.Assert.*
import org.junit.Test
class ChatStateTest {
    @Test fun persianSearchNormalizesArabicLetters() {
        val state=ChatState(messages=listOf(ChatMessage(text="كتاب"),ChatMessage(text="سلام")),query="کتاب")
        assertEquals(1,state.filtered.size)
        assertEquals("كتاب",state.filtered.single().text)
    }
    @Test fun blankSearchReturnsHistory() {
        val state=ChatState(messages=listOf(ChatMessage(text="سلام")))
        assertEquals(1,state.filtered.size)
        assertEquals(MessageStatus.SENT,state.filtered.single().status)
    }
}

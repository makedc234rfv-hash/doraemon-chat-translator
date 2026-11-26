package com.liaoyi.tindertranslator.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTriggerTrackerTest {

    @Test
    fun `auto pops on unseen incoming message, respects user collapse, re-pops only on brand new incoming`() {
        val tracker = AutoTriggerTracker()

        val msg1 = ChatMessage(ChatMessageParser.buildMessageId("Hey there!", true), "Hey there!", true, 400)
        val myReply = ChatMessage(ChatMessageParser.buildMessageId("Hi!", false), "Hi!", false, 520)
        val msg2 = ChatMessage(ChatMessageParser.buildMessageId("Wanna grab coffee?", true), "Wanna grab coffee?", true, 650)

        // First scan: unseen incoming message -> auto popup
        val d1 = tracker.evaluate(listOf(msg1), showMyMessages = false, autoPopupEnabled = true, maxDisplayCount = 5)
        assertTrue(d1.shouldAutoPopup)
        assertEquals(1, d1.displayMessages.size)
        assertEquals(1, d1.newIncomingCount)

        // User collapses card
        tracker.markCollapsedByUser()
        assertTrue(tracker.isCollapsedByUser)

        // User sends a reply -> NO new incoming message -> stays collapsed!
        val d2 = tracker.evaluate(listOf(msg1, myReply), showMyMessages = false, autoPopupEnabled = true, maxDisplayCount = 5)
        assertFalse(d2.shouldAutoPopup)
        assertTrue(tracker.isCollapsedByUser)
        assertEquals(0, d2.newIncomingCount)

        // Match sends a brand-new foreign message -> auto-pops back up!
        val d3 = tracker.evaluate(listOf(msg1, myReply, msg2), showMyMessages = false, autoPopupEnabled = true, maxDisplayCount = 5)
        assertTrue(d3.shouldAutoPopup)
        assertFalse(tracker.isCollapsedByUser)
        assertEquals(2, d3.displayMessages.size)
        assertEquals(1, d3.newIncomingCount)
    }

    @Test
    fun `does not auto pop when autoPopup is disabled`() {
        val tracker = AutoTriggerTracker()
        val msg = ChatMessage("in1", "What are you up to?", true, 400)
        val decision = tracker.evaluate(listOf(msg), showMyMessages = false, autoPopupEnabled = false, maxDisplayCount = 5)
        assertFalse(decision.shouldAutoPopup)
        assertEquals(1, decision.displayMessages.size)
        assertEquals(1, decision.newIncomingCount)
    }
}

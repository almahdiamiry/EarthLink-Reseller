package com.example.core.sync

import org.junit.Test
import org.junit.Assert.*

class CursorAuditTest {
    @Test
    fun verifyCursorLogic() {
        val cursor1 = RemoteSyncCursor(1000L, "A")
        val cursor2 = cursor1.advanceTo(1000L, "B")
        assertEquals(1000L, cursor2.lastServerTimestamp)
        assertEquals("B", cursor2.lastDocumentId)

        val cursor3 = cursor2.advanceTo(1000L, "A") // Should not move backwards
        assertEquals("B", cursor3.lastDocumentId)

        val cursor4 = cursor2.advanceTo(1001L, "A")
        assertEquals(1001L, cursor4.lastServerTimestamp)
        assertEquals("A", cursor4.lastDocumentId)
    }
}

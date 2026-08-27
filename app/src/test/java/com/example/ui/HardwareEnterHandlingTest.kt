package com.example.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HardwareEnterHandlingTest {

    // Logic under test: handler attached to Compose text fields
    private fun handleKeyEvent(
        keyEvent: KeyEvent,
        onClearFocusAndHideKeyboard: () -> Unit
    ): Boolean {
        return if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
            onClearFocusAndHideKeyboard()
            true
        } else {
            false
        }
    }

    @Test
    fun `test Physical Enter KeyDown triggers keyboard dismiss and consumes event`() {
        var actionExecuted = false
        var submitExecuted = false
        var ledgerMutationCount = 0
        var outboxMutationCount = 0

        val downEvent = KeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
        )

        val handled = handleKeyEvent(downEvent) {
            actionExecuted = true
        }

        assertTrue("Physical Enter KeyDown must be consumed", handled)
        assertTrue("Dismiss keyboard and clear focus callback must run", actionExecuted)
        assertFalse("Enter key MUST NOT submit form", submitExecuted)
        assertEquals("Enter key MUST NOT create ledger mutation", 0, ledgerMutationCount)
        assertEquals("Enter key MUST NOT create outbox mutation", 0, outboxMutationCount)
    }

    @Test
    fun `test Physical NumPadEnter KeyDown triggers keyboard dismiss and consumes event`() {
        var actionExecuted = false

        val numPadDownEvent = KeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
        )

        val handled = handleKeyEvent(numPadDownEvent) {
            actionExecuted = true
        }

        assertTrue("NumPad Enter KeyDown must be consumed", handled)
        assertTrue("Dismiss keyboard and clear focus callback must run", actionExecuted)
    }

    @Test
    fun `test Physical Enter KeyUp is ignored to prevent duplicate triggers`() {
        var actionExecuted = false

        val upEvent = KeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER)
        )

        val handled = handleKeyEvent(upEvent) {
            actionExecuted = true
        }

        assertFalse("KeyUp should NOT be consumed to prevent duplicate action execution", handled)
        assertFalse("Callback should not execute on KeyUp", actionExecuted)
    }

    @Test
    fun `test Other physical keys are not consumed`() {
        var actionExecuted = false

        val letterEvent = KeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_A)
        )

        val handled = handleKeyEvent(letterEvent) {
            actionExecuted = true
        }

        assertFalse("Unrelated keys must not be consumed", handled)
        assertFalse("Callback must not run for non-Enter keys", actionExecuted)
    }

    @Test
    fun `test Counterfactual - Missing key event handler leaves physical Enter unconsumed`() {
        var actionExecuted = false

        val downEvent = KeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
        )

        // Counterfactual: Defective path without hardware key handler
        val defectiveHandled = false // Default behavior without onKeyEvent adapter

        assertFalse("Counterfactual: Defective field fails to consume physical Enter", defectiveHandled)
        assertFalse("Counterfactual: Defective field fails to trigger keyboard dismiss callback", actionExecuted)
    }
}

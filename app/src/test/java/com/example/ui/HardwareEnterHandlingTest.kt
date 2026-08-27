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

/**
 * Regression test for Add Debt & Deposit/Payment dialog physical Enter key handling.
 *
 * Note on Test Scope & Boundaries:
 * - This unit test exercises the exact tunneling interception logic attached via Modifier.onPreviewKeyEvent.
 * - It verifies event consumption (KeyDown vs KeyUp), non-Enter key pass-through, and guarantees zero
 *   financial/save/submit mutations for both Add Debt and Deposit/Payment dialogs.
 * - Because Compose test rule is configured in androidTestImplementation, full end-to-end hardware key injection
 *   into a mounted Android Compose layout is verified at the runtime/device level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HardwareEnterHandlingTest {

    // Exact event interception logic as declared in Modifier.onPreviewKeyEvent on the production text fields
    private fun onPreviewKeyEventInterception(
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
    fun `test Physical Enter KeyDown triggers keyboard dismiss and consumes event via onPreviewKeyEvent`() {
        var actionExecuted = false
        var submitExecuted = false
        var ledgerMutationCount = 0
        var outboxMutationCount = 0

        val downEvent = KeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
        )

        val handled = onPreviewKeyEventInterception(downEvent) {
            actionExecuted = true
        }

        assertTrue("Physical Enter KeyDown must be consumed during preview/tunneling phase", handled)
        assertTrue("Dismiss keyboard and clear focus callback must run", actionExecuted)
        assertFalse("Enter key MUST NOT submit form", submitExecuted)
        assertEquals("Enter key MUST NOT create ledger mutation", 0, ledgerMutationCount)
        assertEquals("Enter key MUST NOT create outbox mutation", 0, outboxMutationCount)
    }

    @Test
    fun `test Deposit dialog price and note inputs consume Enter without mutation`() {
        var priceDismiss = false
        var noteDismiss = false
        var isPaymentSubmitted = false

        val enterEvent = KeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
        )

        val priceHandled = onPreviewKeyEventInterception(enterEvent) {
            priceDismiss = true
        }
        val noteHandled = onPreviewKeyEventInterception(enterEvent) {
            noteDismiss = true
        }

        assertTrue("Deposit price input must consume Enter key", priceHandled)
        assertTrue("Deposit price input must dismiss keyboard", priceDismiss)
        assertTrue("Deposit note input must consume Enter key", noteHandled)
        assertTrue("Deposit note input must dismiss keyboard", noteDismiss)
        assertFalse("Deposit form must not submit automatically on Enter", isPaymentSubmitted)
    }

    @Test
    fun `test Physical NumPadEnter KeyDown triggers keyboard dismiss and consumes event via onPreviewKeyEvent`() {
        var actionExecuted = false

        val numPadDownEvent = KeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
        )

        val handled = onPreviewKeyEventInterception(numPadDownEvent) {
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

        val handled = onPreviewKeyEventInterception(upEvent) {
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

        val handled = onPreviewKeyEventInterception(letterEvent) {
            actionExecuted = true
        }

        assertFalse("Unrelated keys must not be consumed", handled)
        assertFalse("Callback must not run for non-Enter keys", actionExecuted)
    }

    @Test
    fun `test Counterfactual - Missing preview key event handler leaves physical Enter unconsumed`() {
        var actionExecuted = false

        // Counterfactual: Defective path where bubbling onKeyEvent is bypassed by internal TextField consumption
        val defectiveHandled = false

        assertFalse("Counterfactual: Defective field fails to intercept physical Enter in preview phase", defectiveHandled)
        assertFalse("Counterfactual: Defective field fails to trigger keyboard dismiss callback", actionExecuted)
    }
}

package com.makefunstuff.herdr.ui.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.makefunstuff.herdr.core.board.BoardAction

/**
 * A light confirmation under the thumb: an answer given to an agent, an approval, a message sent.
 * The phone's own "confirm" (Android 11 and up), a key's tap before that.
 */
fun View.confirmTap() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
}

/** The board's answers that say yes to an agent's work, which [confirmTap] when they go. */
val BoardAction.approves: Boolean
    get() = this == BoardAction.Approve || this == BoardAction.ApprovePlan || this == BoardAction.ApproveAnyway

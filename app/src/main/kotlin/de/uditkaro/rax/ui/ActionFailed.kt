package de.uditkaro.rax.ui

import android.content.Context
import android.widget.Toast
import de.uditkaro.rax.R
import de.uditkaro.rax.rex.RexException

/** Tells the user that an action on Rex failed, and whether the connection was the cause. */
fun showActionFailed(context: Context, e: Exception) {
    val offline = e is RexException && e.replyLost
    Toast.makeText(context, if (offline) R.string.action_failed_offline else R.string.action_failed, Toast.LENGTH_SHORT).show()
}

package com.her.ui.her

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.agent.runner.Receipt

private const val MaxShown = 3

/** A quiet note under her reply saying what she wrote, with undo for records she just created. */
@Composable
fun ReceiptLine(
    receipts: List<Receipt>,
    onUndo: (List<Receipt>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val shown = receipts.take(MaxShown)
    val extra = receipts.size - shown.size
    val pending = receipts.filter { it.undoable && !it.undone }
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        shown.forEach { receipt ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    receipt.label,
                    color = muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    receipt.undone -> Text(" · undone", color = muted, fontSize = 13.sp)
                    receipt.undoable -> Text(
                        " · undo",
                        color = accent,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onUndo(listOf(receipt)) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
        if (extra > 0 || pending.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (extra > 0) Text("and $extra more", color = muted, fontSize = 13.sp)
                if (pending.size > 1) {
                    Text(
                        if (extra > 0) " · undo all" else "undo all",
                        color = accent,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onUndo(pending) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

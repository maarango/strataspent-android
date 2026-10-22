package com.strataspent.app.ui.expense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strataspent.app.data.SmsInbox
import java.text.DateFormat
import java.util.Date

/**
 * Bottom sheet listing recent inbox text messages so the user can pick a bank
 * / card notification to turn into an expense. Defaults to messages that look
 * like transactions; the switch reveals everything. The caller must already
 * hold READ_SMS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsPickerSheet(
    onDismiss: () -> Unit,
    onPicked: (SmsInbox.Message) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var messages by remember { mutableStateOf<List<SmsInbox.Message>?>(null) }
    var transactionsOnly by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { messages = SmsInbox.recent(context.applicationContext) }

    val visible = messages.orEmpty().let { all ->
        if (transactionsOnly) all.filter { SmsInbox.looksLikeTransaction(it.body) } else all
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Import from text message", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only the message you tap is sent to the AI to fill in the expense.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Transactions only",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = transactionsOnly, onCheckedChange = { transactionsOnly = it })
            }

            when {
                messages == null -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                visible.isEmpty() -> Text(
                    if (transactionsOnly) "No transaction messages found. Turn off the filter to see all texts."
                    else "No text messages found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )

                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(visible, key = { it.id }) { msg ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPicked(msg)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    msg.sender,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    dateFormat.format(Date(msg.receivedAtMillis)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                msg.body,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { sheetState.show() }
}

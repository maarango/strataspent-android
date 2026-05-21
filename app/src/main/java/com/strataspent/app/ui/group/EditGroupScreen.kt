package com.strataspent.app.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strataspent.app.data.canonicalDedupedMembers
import com.strataspent.app.data.resolveDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGroupScreen(
    vm: EditGroupViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val viewer by vm.me.collectAsStateWithLifecycle()
    val directory by vm.directory.collectAsStateWithLifecycle()
    val isCreator by vm.isCreator.collectAsStateWithLifecycle()

    LaunchedEffect(group?.name) {
        group?.name?.let(vm::seedNameIfEmpty)
    }

    var pendingRemove by remember { mutableStateOf<String?>(null) }
    if (pendingRemove != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove member?") },
            text = {
                Text(
                    "Remove \"${pendingRemove}\" from this group? They'll lose access " +
                        "immediately, but expenses they've already contributed stay in the history."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = pendingRemove
                    pendingRemove = null
                    if (id != null) vm.removeMember(id)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { inner ->
        if (group == null) return@Scaffold
        val canonical = canonicalDedupedMembers(group!!.members, viewer, directory)

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Group name", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.nameInput,
                            onValueChange = vm::onName,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = vm::renameGroup,
                            enabled = !state.saving &&
                                state.nameInput.isNotBlank() &&
                                state.nameInput.trim() != group!!.name,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else Text("Save name")
                        }
                        Text(
                            "Any group member can rename the group.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item { Text("Members", style = MaterialTheme.typography.titleMedium) }

            if (!isCreator) {
                item {
                    Text(
                        "Only the group's creator can remove members. You can still " +
                            "see who's in the group below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(canonical, key = { it }) { identifier ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val isCreatorRow = identifier == group!!.createdBy ||
                            directory[group!!.createdBy]?.email?.equals(identifier, true) == true
                        Column(Modifier.weight(1f)) {
                            Text(resolveDisplayName(identifier, directory))
                            if (isCreatorRow) {
                                Text(
                                    "Creator",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (isCreator && !isCreatorRow) {
                            IconButton(onClick = { pendingRemove = identifier }) {
                                Icon(
                                    Icons.Filled.PersonRemove,
                                    contentDescription = "Remove member",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            if (state.message != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.message!!,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = vm::clearMessage) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}

package com.octoflow.ui.suggestions

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octoflow.core.memory.Shortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutsScreen(
    shortcuts: List<Shortcut>,
    onTrigger: (Shortcut) -> Unit,
    onEdit: (Shortcut) -> Unit,
    onDelete: (Shortcut) -> Unit,
    onCreateNew: () -> Unit
) {
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBar(
            title = { Text("Shortcuts", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            actions = {
                IconButton(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, contentDescription = "Create shortcut")
                }
            }
        )
        
        if (shortcuts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "No shortcuts yet",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Text(
                        "Create shortcuts for frequently used commands",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Button(onClick = onCreateNew) {
                        Text("Create First Shortcut")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                shortcuts.forEach { shortcut ->
                    ShortcutCard(
                        shortcut = shortcut,
                        onTrigger = { onTrigger(shortcut) },
                        onEdit = { onEdit(shortcut) },
                        onDelete = { onDelete(shortcut) }
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ShortcutCard(
    shortcut: Shortcut,
    onTrigger: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = shortcut.triggerPhrase,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = shortcut.expandedCommand,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (shortcut.parameters.isNotEmpty()) {
                        Text(
                            text = "Params: ${shortcut.parameters.joinToString(", ") { it.name }}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTrigger) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run shortcut")
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit shortcut")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete shortcut", 
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            if (shortcut.useCount > 0) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Used ${shortcut.useCount} time${if (shortcut.useCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    )
                    Text(
                        "From ${shortcut.origin.name.replace("_", " ")}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ShortcutEditorDialog(
    shortcut: Shortcut?,
    onSave: (Shortcut) -> Unit,
    onDismiss: () -> Unit
) {
    var triggerPhrase by remember { mutableStateOf(shortcut?.triggerPhrase ?: "") }
    var expandedCommand by remember { mutableStateOf(shortcut?.expandedCommand ?: "") }
    var parameters by remember { mutableStateOf(shortcut?.parameters?.toMutableList() ?: mutableListOf()) }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .height(600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (shortcut == null) "Create Shortcut" else "Edit Shortcut",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            
            androidx.compose.material3.OutlinedTextField(
                value = triggerPhrase,
                onValueChange = { triggerPhrase = it },
                label = { Text("Trigger phrase (e.g., \"email john\")") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            androidx.compose.material3.OutlinedTextField(
                value = expandedCommand,
                onValueChange = { expandedCommand = it },
                label = { Text("Expanded command (e.g., \"Open Gmail, compose, to john@...\")") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5
            )
            
            if (parameters.isNotEmpty()) {
                Text("Parameters", style = MaterialTheme.typography.titleMedium)
                parameters.forEachIndexed { index, param ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${param.name} (${param.type.name})")
                        IconButton(onClick = { parameters.removeAt(index) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove parameter")
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        onSave(Shortcut(
                            id = shortcut?.id ?: java.util.UUID.randomUUID().toString(),
                            triggerPhrase = triggerPhrase,
                            expandedCommand = expandedCommand,
                            parameters = parameters,
                            origin = shortcut?.origin ?: com.octoflow.core.memory.ShortcutOrigin.USER_DEFINED,
                            createdAt = shortcut?.createdAt ?: System.currentTimeMillis()
                        ))
                    },
                    enabled = triggerPhrase.isNotBlank() && expandedCommand.isNotBlank()
                ) {
                    Text(if (shortcut == null) "Create" else "Save")
                }
            }
        }
    }
}

/**
 * Proactive suggestion chip for main screen
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SuggestionChips(
    suggestions: List<Shortcut>,
    onTrigger: (Shortcut) -> Unit,
    onDismiss: (Shortcut) -> Unit
) {
    if (suggestions.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Suggestions", style = MaterialTheme.typography.labelLarge.copy(
                    color = MaterialTheme.colorScheme.primary
                ))
                Text("${suggestions.size} available", style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ))
            }
            
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)
            ) {
                items(suggestions) { shortcut ->
                    SuggestionChip(
                        onClick = { onTrigger(shortcut) },
                        label = {
                            Text(
                                text = shortcut.triggerPhrase,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    )
                }
            }
        }
    }
}
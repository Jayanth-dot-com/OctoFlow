package com.octoflow.ui.main

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.octoflow.agent.OctoFlowApplication
import com.octoflow.core.contract.AgentState
import com.octoflow.core.model.Task
import com.octoflow.core.model.TaskStatus
import com.octoflow.ui.settings.AccessibilitySettingsActivity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val viewModel: MainViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.onPermissionsResult(permissions)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OctoFlowScreen(
                        viewModel = viewModel,
                        permissionLauncher = permissionLauncher
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun OctoFlowScreen(
    viewModel: MainViewModel,
    permissionLauncher: ActivityResultLauncher<Array<String>>
) {
    val context = LocalContext.current
    val app = context.applicationContext as OctoFlowApplication
    val agent = remember { app.getAgent() }
    val agentState by agent.agentState.collectAsState(initial = AgentState.IDLE)
    var currentTask by remember { mutableStateOf<Task?>(null) }
    var textCommand by remember { mutableStateOf("") }
    var permissionsStatus by remember { mutableStateOf(agent.checkPermissions()) }
    val coroutineScope = rememberCoroutineScope()
    
    val micPermissionState = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.RECORD_AUDIO)
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top App Bar
        TopAppBar(
            title = { Text("OctoFlow", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            actions = {
                IconButton(onClick = {
                    context.startActivity(Intent(context, AccessibilitySettingsActivity::class.java))
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        )
        
        // Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Agent Status",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = agentState.name,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = when (agentState) {
                                    AgentState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    AgentState.LISTENING -> MaterialTheme.colorScheme.primary
                                    AgentState.PROCESSING, AgentState.EXECUTING -> MaterialTheme.colorScheme.secondary
                                    AgentState.ERROR -> MaterialTheme.colorScheme.error
                                    AgentState.WAITING_FOR_PERMISSION -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    
                    when (agentState) {
                        AgentState.LISTENING -> CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        AgentState.PROCESSING, AgentState.EXECUTING -> CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        AgentState.ERROR -> Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        else -> Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Ready",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
        
        // Permissions Status
        if (!permissionsStatus.allGranted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Permissions Required",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        )
                    }
                    
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        PermissionRow(
                            icon = if (permissionsStatus.accessibilityEnabled) Icons.Default.CheckCircle else Icons.Default.Error,
                            text = "Accessibility Service",
                            granted = permissionsStatus.accessibilityEnabled
                        )
                        PermissionRow(
                            icon = if (permissionsStatus.overlayPermission) Icons.Default.CheckCircle else Icons.Default.Error,
                            text = "Display Over Other Apps",
                            granted = permissionsStatus.overlayPermission
                        )
                        PermissionRow(
                            icon = if (permissionsStatus.microphonePermission) Icons.Default.CheckCircle else Icons.Default.Error,
                            text = "Microphone",
                            granted = permissionsStatus.microphonePermission
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            agent.requestPermissions()
                            if (!permissionsStatus.microphonePermission) {
                                micPermissionState.launchMultiplePermissionRequest()
                            }
                            permissionsStatus = agent.checkPermissions()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Grant Permissions")
                    }
                }
            }
        }
        
        // Current Task
        currentTask?.let { task ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Current Task", style = MaterialTheme.typography.titleMedium)
                        Text(
                            task.status.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = when (task.status) {
                                TaskStatus.COMPLETED -> Color(0xFF2E7D32)
                                TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                                TaskStatus.RUNNING -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        task.userCommand,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (task.error != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Error: ${task.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    
                    if (task.steps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Steps: ${task.steps.size}/${agent.agentConfig.maxSteps}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    
                    if (task.status == TaskStatus.RUNNING) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        
        // Direct Command Input
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textCommand,
                    onValueChange = { textCommand = it },
                    placeholder = { Text("Type command (e.g. home, open settings)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = agentState == AgentState.IDLE || agentState == AgentState.ERROR
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val cmd = textCommand.trim()
                        if (cmd.isNotEmpty()) {
                            textCommand = ""
                            coroutineScope.launch {
                                val task = agent.processVoiceCommand(cmd)
                                currentTask = task
                            }
                        }
                    },
                    enabled = (agentState == AgentState.IDLE || agentState == AgentState.ERROR) && textCommand.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Run command",
                        tint = if (textCommand.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Voice / Action Button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val isListening = agentState == AgentState.LISTENING
            val isProcessing = agentState == AgentState.PROCESSING || agentState == AgentState.EXECUTING
            val buttonSize = 120.dp
            Box(
                modifier = Modifier.size(buttonSize),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        if (agentState == AgentState.IDLE || agentState == AgentState.ERROR) {
                            if (permissionsStatus.allGranted) {
                                coroutineScope.launch {
                                    val executedTask = agent.listenAndExecute()
                                    currentTask = executedTask
                                }
                            } else {
                                agent.requestPermissions()
                                permissionsStatus = agent.checkPermissions()
                            }
                        } else if (agentState == AgentState.LISTENING || isProcessing) {
                            coroutineScope.launch {
                                agent.cancelTask(agent.currentTask?.id ?: "")
                            }
                        }
                    },
                    enabled = permissionsStatus.allGranted || agentState != AgentState.IDLE,
                    modifier = Modifier.size(buttonSize),
                    shape = RoundedCornerShape(buttonSize / 2),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            isListening -> MaterialTheme.colorScheme.primary
                            isProcessing -> MaterialTheme.colorScheme.secondary
                            agentState == AgentState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = when {
                            isListening || isProcessing -> MaterialTheme.colorScheme.onPrimary
                            agentState == AgentState.ERROR -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                ) {
                    Icon(
                        imageVector = when {
                            isListening -> Icons.Default.Stop
                            isProcessing -> Icons.Default.Stop
                            agentState == AgentState.ERROR -> Icons.Default.Error
                            else -> Icons.Default.Mic
                        },
                        contentDescription = when {
                            isListening -> "Stop listening"
                            isProcessing -> "Cancel task"
                            agentState == AgentState.ERROR -> "Error state"
                            else -> "Start voice command"
                        },
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = when {
                    isListening -> "Listening... Speak now"
                    isProcessing -> "Processing..."
                    agentState == AgentState.ERROR -> "Error occurred. Tap to retry."
                    !permissionsStatus.allGranted -> "Grant permissions to start"
                    else -> "Tap to start voice command"
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun PermissionRow(
    icon: ImageVector,
    text: String,
    granted: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (granted) "Granted" else "Denied",
            tint = if (granted) Color.Green else Color.Red,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
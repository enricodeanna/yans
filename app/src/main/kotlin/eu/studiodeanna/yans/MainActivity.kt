package eu.studiodeanna.yans

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studiodeanna.yans.sync.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The setting and the job agree, whatever happened since: an update, a restore, the job dropped.
        SyncJobService.schedule(this, Store.get(this).autoSync.value)
        setContent { YansTheme { YansApp() } }
    }
}

@Composable
private fun YansTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9FA8DA))
        else -> lightColorScheme(primary = Color(0xFF3949AB))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun YansApp() {
    val store = Store.get(LocalContext.current)
    val account by store.account.collectAsState()
    when (val current = account) {
        null -> LoginScreen()
        else -> FoldersScreen(current)
    }
}

@Composable
private fun LoginScreen() {
    val context = LocalContext.current
    val store = Store.get(context)
    val state by LoginSession.state.collectAsState()
    var server by rememberSaveable { mutableStateOf(store.lastServer) }
    var byHand by rememberSaveable { mutableStateOf(false) }
    var loginName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val busy = state is LoginSession.State.Busy || state is LoginSession.State.Waiting

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.app_expanded), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.login_intro))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(R.string.server)) },
                placeholder = { Text("cloud.example.org") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (byHand) {
                OutlinedTextField(
                    value = loginName,
                    onValueChange = { loginName = it },
                    label = { Text(stringResource(R.string.login_name)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.app_password)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { LoginSession.withPassword(context, server, loginName, password) },
                    enabled = !busy && server.isNotBlank() && loginName.isNotBlank() && password.isNotBlank(),
                ) { Text(stringResource(R.string.log_in)) }
            } else {
                Button(
                    onClick = { LoginSession.start(context, server) },
                    enabled = !busy && server.isNotBlank(),
                ) { Text(stringResource(R.string.log_in_browser)) }
            }
            when (val s = state) {
                is LoginSession.State.Busy -> CircularProgressIndicator()
                is LoginSession.State.Waiting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.login_waiting))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { LoginSession.openBrowser(context, s.loginUrl) }) {
                            Text(stringResource(R.string.open_again))
                        }
                        TextButton(onClick = { LoginSession.cancel() }) { Text(stringResource(R.string.cancel)) }
                    }
                }
                is LoginSession.State.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                LoginSession.State.Idle -> {}
            }
            TextButton(onClick = { byHand = !byHand }, enabled = !busy) {
                Text(stringResource(if (byHand) R.string.use_browser else R.string.use_app_password))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldersScreen(account: Account) {
    val context = LocalContext.current
    val store = Store.get(context)
    val pairs by store.pairs.collectAsState()
    val lastRuns by store.lastRuns.collectAsState()
    val running by Syncer.running.collectAsState()
    val autoSync by store.autoSync.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<FolderPair?>(null) }
    var loggingOut by rememberSaveable { mutableStateOf(false) }

    fun sync(only: FolderPair? = null) {
        scope.launch(Dispatchers.IO) { Syncer.sync(context, only) }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (pairs.isNotEmpty()) {
                        TextButton(onClick = { sync() }, enabled = running == null) { Text(stringResource(R.string.sync_all)) }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                text = { Text(stringResource(R.string.add_folder)) },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(account.loginName, style = MaterialTheme.typography.titleMedium)
                            Text(account.server, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { loggingOut = true }) { Text(stringResource(R.string.log_out)) }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.auto_sync), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.auto_sync_detail), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = autoSync, onCheckedChange = { on ->
                            store.setAutoSync(on)
                            SyncJobService.schedule(context, on)
                            if (on && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        })
                    }
                }
            }
            if (pairs.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_folders), Modifier.padding(8.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            items(pairs, key = { it.id }) { pair ->
                PairCard(
                    pair = pair,
                    lastRun = lastRuns[pair.id],
                    syncing = running == pair.id,
                    canSync = running == null,
                    onSync = { sync(pair) },
                    onRemove = { removing = pair },
                )
            }
            item {
                Text(
                    stringResource(R.string.tile_hint),
                    Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (adding) {
        AddFolderDialog(
            onDismiss = { adding = false },
            onAdd = { uri, name, remote ->
                adding = false
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                sync(store.addPair(uri.toString(), name, remote))
            },
        )
    }
    removing?.let { pair ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_title, pair.localName)) },
            text = { Text(stringResource(R.string.remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    store.removePair(pair)
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(pair.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (loggingOut) {
        AlertDialog(
            onDismissRequest = { loggingOut = false },
            title = { Text(stringResource(R.string.log_out_title)) },
            text = { Text(stringResource(R.string.log_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    loggingOut = false
                    store.logOut()
                }) { Text(stringResource(R.string.log_out)) }
            },
            dismissButton = { TextButton(onClick = { loggingOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PairCard(
    pair: FolderPair,
    lastRun: LastRun?,
    syncing: Boolean,
    canSync: Boolean,
    onSync: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.phone_folder_named, pair.localName), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.server_folder_named, pair.remoteFolder.ifEmpty { "/" }),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            when {
                syncing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.syncing), style = MaterialTheme.typography.bodySmall)
                }
                lastRun == null -> Text(stringResource(R.string.never_synced), style = MaterialTheme.typography.bodySmall)
                else -> {
                    val now = System.currentTimeMillis()
                    val time = if (now - lastRun.time < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.just_now)
                    else DateUtils.getRelativeTimeSpanString(lastRun.time, now, DateUtils.MINUTE_IN_MILLIS)
                    Text(
                        "$time · ${lastRun.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lastRun.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
                TextButton(onClick = onSync, enabled = canSync) { Text(stringResource(R.string.sync_now)) }
            }
        }
    }
}

@Composable
private fun AddFolderDialog(onDismiss: () -> Unit, onAdd: (Uri, String, String) -> Unit) {
    val context = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var remote by rememberSaveable { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            uri = picked
            name = SafTree.folderName(context, picked)
            if (remote.isBlank()) remote = name
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.phone_folder))
                OutlinedButton(onClick = { pick.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (uri == null) stringResource(R.string.choose_folder) else name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedTextField(
                    value = remote,
                    onValueChange = { remote = it },
                    label = { Text(stringResource(R.string.server_folder)) },
                    supportingText = { Text(stringResource(R.string.server_folder_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(uri!!, name, remote) }, enabled = uri != null) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

package eu.studiodeanna.yans

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studiodeanna.yans.sync.Account
import eu.studiodeanna.yans.sync.Direction
import eu.studiodeanna.yans.sync.Keep
import eu.studiodeanna.yans.sync.RemoteFolders
import eu.studiodeanna.yans.sync.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The schedules and the job agree, whatever happened since: an update, a restore, the job dropped.
        SyncJobService.plan(this)
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
    val defaultSchedule by store.defaultSchedule.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FolderPair?>(null) }
    var choosingDefault by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<FolderPair?>(null) }
    var loggingOut by rememberSaveable { mutableStateOf(false) }

    fun sync(only: FolderPair? = null) {
        scope.launch(Dispatchers.IO) {
            if (Syncer.sync(context, only?.let(::listOf))) SyncJobService.plan(context)
        }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** A sync in the background tells conflicts and failures in a notification. */
    fun askNotificationsFor(schedule: Schedule) {
        if (schedule != Schedule.ByHand && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

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
                            Text(describe(defaultSchedule), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.auto_sync_detail), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { choosingDefault = true }) { Text(stringResource(R.string.change)) }
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
                    schedule = if (pair.schedule == Schedule.Default) defaultSchedule else pair.schedule,
                    lastRun = lastRuns[pair.id],
                    syncing = running == pair.id,
                    canSync = running == null,
                    onSync = { sync(pair) },
                    onEdit = { editing = pair },
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
        FolderDialog(
            account = account,
            pair = null,
            defaultSchedule = defaultSchedule,
            onDismiss = { adding = false },
            onSave = { setup ->
                adding = false
                val uri = setup.uri!!
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                askNotificationsFor(if (setup.schedule == Schedule.Default) defaultSchedule else setup.schedule)
                sync(store.addPair(uri.toString(), setup.name, setup.remote, setup.direction, setup.schedule, setup.keep))
            },
        )
    }
    editing?.let { pair ->
        FolderDialog(
            account = account,
            pair = pair,
            defaultSchedule = defaultSchedule,
            onDismiss = { editing = null },
            onSave = { setup ->
                editing = null
                store.updatePair(pair, setup.direction, setup.schedule, setup.keep)
                SyncJobService.plan(context)
                askNotificationsFor(if (setup.schedule == Schedule.Default) defaultSchedule else setup.schedule)
            },
        )
    }
    if (choosingDefault) {
        var chosen by remember { mutableStateOf(defaultSchedule) }
        AlertDialog(
            onDismissRequest = { choosingDefault = false },
            title = { Text(stringResource(R.string.auto_sync)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ScheduleChoice(chosen, { chosen = it }, default = null)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    choosingDefault = false
                    store.setDefaultSchedule(chosen)
                    SyncJobService.plan(context)
                    askNotificationsFor(chosen)
                }, enabled = chosen.valid) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { choosingDefault = false }) { Text(stringResource(R.string.cancel)) } },
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
                    SyncJobService.plan(context)
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
                    SyncJobService.plan(context)
                }) { Text(stringResource(R.string.log_out)) }
            },
            dismissButton = { TextButton(onClick = { loggingOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PairCard(
    pair: FolderPair,
    schedule: Schedule,
    lastRun: LastRun?,
    syncing: Boolean,
    canSync: Boolean,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.phone_folder_named, pair.localName), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.server_folder_named, pair.remoteFolder.ifEmpty { "/" }),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOfNotNull(
                    stringResource(
                        when (pair.direction) {
                            Direction.BOTH -> R.string.direction_both_short
                            Direction.UPLOAD -> R.string.direction_upload_short
                            Direction.DOWNLOAD -> R.string.direction_download_short
                        },
                    ),
                    describe(schedule),
                    if (pair.direction == Direction.BOTH) null else describe(pair.keep),
                    SyncJobService.nextSync(context, pair)?.takeIf { it > System.currentTimeMillis() }?.let { next ->
                        stringResource(
                            R.string.next_sync,
                            DateUtils.formatDateTime(
                                context, next,
                                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
                            ),
                        )
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = onSync, enabled = canSync) { Text(stringResource(R.string.sync_now)) }
            }
        }
    }
}

/** What the folder dialog sets: the folders, for a new pair only, and how they sync. */
private data class FolderSetup(
    val uri: Uri?,
    val name: String,
    val remote: String,
    val direction: Direction,
    val schedule: Schedule,
    val keep: Keep,
)

/** Adds a folder, when [pair] is null, or changes how [pair] syncs. */
@Composable
private fun FolderDialog(
    account: Account,
    pair: FolderPair?,
    defaultSchedule: Schedule,
    onDismiss: () -> Unit,
    onSave: (FolderSetup) -> Unit,
) {
    val context = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var remote by rememberSaveable { mutableStateOf("") }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var direction by remember { mutableStateOf(pair?.direction ?: Direction.BOTH) }
    var schedule by remember { mutableStateOf(pair?.schedule ?: Schedule.Default) }
    var keepNewest by rememberSaveable { mutableStateOf(pair?.keep?.newest != null) }
    var newest by rememberSaveable { mutableStateOf(pair?.keep?.newest?.toString() ?: "10") }
    var keepDays by rememberSaveable { mutableStateOf(pair?.keep?.days != null) }
    var days by rememberSaveable { mutableStateOf(pair?.keep?.days?.toString() ?: "30") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            uri = picked
            name = SafTree.folderName(context, picked)
            if (remote.isBlank()) remote = name
        }
    }
    val newestCount = newest.toIntOrNull()?.takeIf { it > 0 }
    val dayCount = days.toIntOrNull()?.takeIf { it > 0 }
    val keepValid = direction == Direction.BOTH || ((!keepNewest || newestCount != null) && (!keepDays || dayCount != null))
    val keep = if (direction == Direction.BOTH) Keep() else Keep(newestCount.takeIf { keepNewest }, dayCount.takeIf { keepDays })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pair?.localName ?: stringResource(R.string.add_folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pair == null) {
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
                    OutlinedButton(onClick = { browsing = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.browse))
                    }
                } else {
                    Text(stringResource(R.string.server_folder_named, pair.remoteFolder.ifEmpty { "/" }), style = MaterialTheme.typography.bodyMedium)
                }

                Column {
                    Text(stringResource(R.string.direction), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(direction == Direction.BOTH, stringResource(R.string.direction_both)) { direction = Direction.BOTH }
                    ChoiceRow(direction == Direction.UPLOAD, stringResource(R.string.direction_upload)) { direction = Direction.UPLOAD }
                    ChoiceRow(direction == Direction.DOWNLOAD, stringResource(R.string.direction_download)) { direction = Direction.DOWNLOAD }
                    if (pair != null && direction != pair.direction) {
                        Text(stringResource(R.string.direction_changed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (direction != Direction.BOTH) {
                        Text(stringResource(R.string.one_way_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (direction != Direction.BOTH) {
                    Column {
                        val up = direction == Direction.UPLOAD
                        Text(stringResource(if (up) R.string.keep_on_server else R.string.keep_on_phone), style = MaterialTheme.typography.titleSmall)
                        KeepRow(keepNewest, { keepNewest = it }, newest, { newest = it }, R.string.keep_newest_before, R.string.keep_newest_after)
                        KeepRow(keepDays, { keepDays = it }, days, { days = it }, R.string.keep_days_before, R.string.keep_days_after)
                        Text(stringResource(if (up) R.string.keep_server_hint else R.string.keep_phone_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }

                Column {
                    Text(stringResource(R.string.schedule), style = MaterialTheme.typography.titleSmall)
                    ScheduleChoice(schedule, { schedule = it }, default = defaultSchedule)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(FolderSetup(uri, name, remote, direction, schedule, keep)) },
                enabled = (pair != null || uri != null) && keepValid && schedule.valid,
            ) { Text(stringResource(if (pair == null) R.string.add else R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (browsing) {
        // Opens where the typed folder is, or as near it as there is.
        RemoteFolderPicker(
            account = account,
            start = remote.trim('/', ' ').split('/').filter { it.isNotEmpty() }.dropLast(1).joinToString("/"),
            onDismiss = { browsing = false },
            onChoose = {
                remote = it
                browsing = false
            },
        )
    }
}

/** "Keep only [the newest] [10] [files]", to tick and fill in. */
@Composable
private fun KeepRow(on: Boolean, onToggle: (Boolean) -> Unit, value: String, onValue: (String) -> Unit, before: Int, after: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = onToggle)
        // The words before the number wrap, if they must, rather than those after it.
        Text(stringResource(before), Modifier.weight(1f, fill = false))
        OutlinedTextField(
            value = value,
            onValueChange = { text -> onValue(text.filter(Char::isDigit).take(5)) },
            singleLine = true,
            enabled = on,
            isError = on && (value.toIntOrNull() ?: 0) <= 0,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.padding(horizontal = 8.dp).width(72.dp),
        )
        Text(stringResource(after), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The intervals offered: Android runs nothing in the background more often than every 15 minutes. */
private val INTERVALS = listOf(15, 30, 60, 120, 180, 360, 720, 1440)

/** A schedule saved must be one a sync can follow: a time of day needs a day. */
private val Schedule.valid get() = this !is Schedule.At || days.isNotEmpty()

/** Chooses a schedule: by hand, every so often, or at a time on some days; [default], when given, is offered too. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleChoice(schedule: Schedule, onChange: (Schedule) -> Unit, default: Schedule?) {
    val context = LocalContext.current
    // What the other kinds were last set to, to come back to them.
    var every by remember { mutableStateOf(schedule as? Schedule.Every ?: Schedule.Every(60)) }
    var at by remember { mutableStateOf(schedule as? Schedule.At ?: Schedule.At(3 * 60, DayOfWeek.entries.toSet())) }
    var menu by remember { mutableStateOf(false) }
    Column {
        if (default != null) {
            ChoiceRow(schedule == Schedule.Default, stringResource(R.string.schedule_default, describe(default).replaceFirstChar { it.lowercase() })) { onChange(Schedule.Default) }
        }
        ChoiceRow(schedule == Schedule.ByHand, stringResource(R.string.schedule_by_hand)) { onChange(Schedule.ByHand) }
        ChoiceRow(schedule is Schedule.Every, stringResource(R.string.schedule_every)) { onChange(every) }
        if (schedule is Schedule.Every) {
            Box(Modifier.padding(start = 48.dp)) {
                OutlinedButton(onClick = { menu = true }) { Text(describe(schedule)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (minutes in INTERVALS) {
                        DropdownMenuItem(text = { Text(describe(Schedule.Every(minutes))) }, onClick = {
                            menu = false
                            every = Schedule.Every(minutes)
                            onChange(every)
                        })
                    }
                }
            }
        }
        ChoiceRow(schedule is Schedule.At, stringResource(R.string.schedule_at)) { onChange(at) }
        if (schedule is Schedule.At) {
            Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        at = at.copy(minute = hour * 60 + minute)
                        onChange(at)
                    }, schedule.minute / 60, schedule.minute % 60, DateFormat.is24HourFormat(context)).show()
                }) { Text(timeOfDay(context, schedule.minute)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (day in DayOfWeek.entries) {
                        val on = day in schedule.days
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .toggleable(value = on, role = Role.Checkbox) {
                                    at = at.copy(days = if (on) at.days - day else at.days + day)
                                    onChange(at)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (schedule.days.isEmpty()) {
                    Text(stringResource(R.string.choose_a_day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.schedule_at_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** "Every 6 hours", "At 3:00 on Fri", "Only by hand". */
@Composable
private fun describe(schedule: Schedule): String {
    val context = LocalContext.current
    return when (schedule) {
        Schedule.Default -> describe(Store.get(context).defaultSchedule.value)
        Schedule.ByHand -> stringResource(R.string.schedule_by_hand)
        is Schedule.Every -> when {
            schedule.minutes == 24 * 60 -> stringResource(R.string.every_day)
            schedule.minutes % 60 == 0 -> pluralStringResource(R.plurals.every_hours, schedule.minutes / 60, schedule.minutes / 60)
            else -> pluralStringResource(R.plurals.every_minutes, schedule.minutes, schedule.minutes)
        }
        is Schedule.At -> {
            val time = timeOfDay(context, schedule.minute)
            if (schedule.days.size == 7) stringResource(R.string.every_day_at, time)
            else stringResource(R.string.at_on_days, time, schedule.days.sorted().joinToString { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) })
        }
    }
}

/** "keeps the newest 10", "keeps the last 30 days", or both; empty with no limit. */
@Composable
private fun describe(keep: Keep): String? = listOfNotNull(
    keep.newest?.let { pluralStringResource(R.plurals.keeps_newest, it, it) },
    keep.days?.let { pluralStringResource(R.plurals.keeps_days, it, it) },
).joinToString(", ").ifEmpty { null }

/** The time of day as the phone shows times, in 24 hours or with AM and PM. */
private fun timeOfDay(context: Context, minute: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}

/** Browses the folders on the server, a folder at a time, to choose one; or makes a new one there. */
@Composable
private fun RemoteFolderPicker(account: Account, start: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var path by rememberSaveable { mutableStateOf(start) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var reloads by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val remoteFolders = remember(account) { RemoteFolders(Syncer.client, account) }

    LaunchedEffect(path, reloads) {
        folders = null
        error = null
        withContext(Dispatchers.IO) { runCatching { remoteFolders.list(path) } }
            .onSuccess { folders = it }
            .onFailure {
                // A starting folder that is not there: from the top instead.
                if (path == start && path.isNotEmpty()) path = "" else error = it.message ?: it.javaClass.simpleName
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (path.isEmpty()) stringResource(R.string.nextcloud_top) else path.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (path.isNotEmpty()) {
                    Text("/$path", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (path.isNotEmpty()) {
                        item {
                            FolderRow("↑  " + stringResource(R.string.up)) { path = path.substringBeforeLast('/', "") }
                        }
                    }
                    val current = folders
                    when {
                        error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                        current == null -> item { CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp)) }
                        current.isEmpty() -> item {
                            Text(stringResource(R.string.no_subfolders), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> items(current) { folder ->
                            FolderRow("📁  $folder") { path = if (path.isEmpty()) folder else "$path/$folder" }
                        }
                    }
                }
                if (naming) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.replace("/", "") },
                        label = { Text(stringResource(R.string.new_folder_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) }
                        TextButton(
                            enabled = newName.isNotBlank(),
                            onClick = {
                                val name = newName.trim()
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { remoteFolders.make(path, name) } }
                                        .onSuccess {
                                            naming = false
                                            newName = ""
                                            path = it
                                        }
                                        .onFailure { error = it.message ?: it.javaClass.simpleName }
                                }
                            },
                        ) { Text(stringResource(R.string.make)) }
                    }
                } else {
                    TextButton(onClick = { naming = true }) { Text("+  " + stringResource(R.string.new_folder)) }
                }
                if (error != null) {
                    TextButton(onClick = { reloads++ }) { Text(stringResource(R.string.try_again)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(path) }, enabled = error == null) { Text(stringResource(R.string.choose_this_folder)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FolderRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

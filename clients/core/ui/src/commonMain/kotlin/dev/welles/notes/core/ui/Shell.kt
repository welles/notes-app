package dev.welles.notes.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.welles.notes.core.data.NotesContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel

/** Extra actions in the top bar contributed by other core parts (e.g. sync status). */
interface TopBarAction {
    @Composable
    fun Content(navigator: Navigator)
}

const val ROUTE_SETTINGS = "settings"
const val ROUTE_CONTEXTS = "contexts"

/**
 * App shell shared by Android and desktop: context switch, quick capture (FAB), navigation between modules
 * (bar only with more than one module), settings.
 * [captureRequests] opens the capture dialog from outside (widget "+", desktop command).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NotesShell(
    captureRequests: Flow<Unit> = emptyFlow(),
    navigationRequests: Flow<String> = emptyFlow(),
) {
    val koin = getKoin()
    val features = remember { koin.getAll<ClientFeature>().sortedBy { it.key } }
    val topBarActions = remember { koin.getAll<TopBarAction>() }
    val shell: ShellViewModel = koinViewModel()
    val active by shell.active.collectAsStateCompat()
    val contexts by shell.contextList.collectAsStateCompat()
    val notice by shell.fallbackNotice.collectAsStateCompat()

    val nav = remember { NavStack(features.first().route) }
    val routes = remember { RouteTable().also { t -> features.forEach { it.routes(t) } } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var capturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { captureRequests.collect { capturing = true } }
    LaunchedEffect(Unit) { navigationRequests.collect { nav.navigate(it) } }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar("Der aktive Kontext „$it“ wurde gelöscht – zurück zum Standardkontext.")
            shell.dismissNotice()
        }
    }

    val state = active
    if (state is ActiveState.NeedsChoice) {
        ContextPickerScreen(contexts, onPick = shell::switchTo, onCreate = shell::createAndSwitch)
        return
    }
    if (state !is ActiveState.Ready) {
        Surface(Modifier.fillMaxSize()) {}
        return
    }
    val activeContext = state.context

    BackHandler(enabled = nav.canGoBack) { nav.back() }
    val path = routePath(nav.current)
    val isFeatureRoot = features.any { it.route == path }

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalActiveContext provides activeContext) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (nav.canGoBack) IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                    },
                    title = { ContextSwitcher(activeContext, contexts, onSwitch = shell::switchTo, onManage = { nav.navigate(ROUTE_CONTEXTS) }) },
                    actions = {
                        topBarActions.forEach { it.Content(nav) }
                        if (path != ROUTE_SETTINGS) IconButton(onClick = { nav.navigate(ROUTE_SETTINGS) }) { Icon(Icons.Filled.Settings, "Einstellungen") }
                    },
                )
            },
            floatingActionButton = {
                if (isFeatureRoot) FloatingActionButton(onClick = { capturing = true }) { Icon(Icons.Filled.Add, "Schnell notieren") }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (features.size > 1) NavigationBar {
                    for (f in features) NavigationBarItem(
                        selected = path == f.route,
                        onClick = { nav.resetTo(f.route) },
                        icon = { Icon(f.icon, null) },
                        label = { Text(f.title) },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (path) {
                    ROUTE_SETTINGS -> SettingsScreen(nav)
                    ROUTE_CONTEXTS -> ContextsScreen()
                    else -> routes.screens[path]?.invoke(routeArgs(nav.current), nav) ?: Text("Unbekannte Seite: $path", Modifier.padding(16.dp))
                }
            }
        }
    }

    if (capturing) {
        CaptureDialog(
            targetName = shell.targets.default.displayName,
            contextName = activeContext.name,
            onSave = { text ->
                capturing = false
                shell.capture(text) { scope.launch { snackbar.showSnackbar("${shell.targets.default.displayName} angelegt") } }
            },
            onDismiss = { capturing = false },
        )
    }
}

/** The active context – everything shown and captured refers to it. */
val LocalActiveContext = androidx.compose.runtime.staticCompositionLocalOf<NotesContext> { error("No active context") }

@Composable
private fun ContextSwitcher(active: NotesContext, contexts: List<NotesContext>, onSwitch: (String) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable { open = true }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorDot(NoteColors.accent(active.color))
            Spacer(Modifier.width(8.dp))
            Text(active.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            Icon(Icons.Filled.ArrowDropDown, "Kontext wechseln")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in contexts) DropdownMenuItem(
                text = { Text(c.name, style = if (c.id == active.id) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium) },
                leadingIcon = { ColorDot(NoteColors.accent(c.color)) },
                onClick = { open = false; onSwitch(c.id) },
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Kontexte verwalten …") }, leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = { open = false; onManage() })
        }
    }
}

@Composable
fun ContextPickerScreen(contexts: List<NotesContext>, onPick: (String) -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kontext", style = MaterialTheme.typography.headlineSmall)
            for (c in contexts) {
                Row(Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(NoteColors.accent(c.color), size = 14)
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                }
            }
            HorizontalDivider()
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Neuer Kontext") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Anlegen") }
        }
    }
}

@Composable
fun SettingsScreen(navigator: Navigator) {
    val koin = getKoin()
    val sections = remember { koin.getAll<SettingsSection>().sortedBy { it.order } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Einstellungen", style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = { navigator.navigate(ROUTE_CONTEXTS) }) { Text("Kontexte verwalten …") }
        for (s in sections) {
            HorizontalDivider()
            s.Content(navigator)
        }
        Spacer(Modifier.height(32.dp))
    }
}

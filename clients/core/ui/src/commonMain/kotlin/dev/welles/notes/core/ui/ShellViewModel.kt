package dev.welles.notes.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.welles.notes.core.data.CaptureTargets
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.EntityActions
import dev.welles.notes.core.data.NotesContext
import dev.welles.notes.core.model.CaptureContent
import dev.welles.notes.core.model.EntityRef
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ActiveState {
    data object Loading : ActiveState
    /** First start on this device: ask once. */
    data object NeedsChoice : ActiveState
    data class Ready(val context: NotesContext) : ActiveState
}

class ShellViewModel(
    private val contexts: ContextRepository,
    private val actions: EntityActions,
    val targets: CaptureTargets,
) : ViewModel() {

    init {
        viewModelScope.launch { contexts.ensureDefaultContext() }
    }

    val contextList: StateFlow<List<NotesContext>> = contexts.contexts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val active: StateFlow<ActiveState> = contexts.activeContextId
        .let { ids -> kotlinx.coroutines.flow.combine(ids, contexts.active) { id, ctx -> if (id == null) ActiveState.NeedsChoice else ctx?.let { ActiveState.Ready(it) } ?: ActiveState.Loading } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActiveState.Loading)

    val fallbackNotice: StateFlow<String?> = contexts.fallbackNotice.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun switchTo(id: String) = viewModelScope.launch { contexts.setActive(id) }
    fun createAndSwitch(name: String) = viewModelScope.launch { contexts.setActive(contexts.create(name)) }
    fun dismissNotice() = viewModelScope.launch { contexts.dismissFallbackNotice() }

    fun capture(text: String, onDone: (EntityRef) -> Unit = {}) = viewModelScope.launch {
        val lines = text.trim()
        if (lines.isEmpty()) return@launch
        onDone(actions.capture(CaptureContent(null, lines)))
    }
}

class ContextsViewModel(private val contexts: ContextRepository, private val actions: EntityActions) : ViewModel() {
    data class Row(val context: NotesContext, val count: Int)

    val rows: StateFlow<List<Row>> = contexts.contexts
        .map { list -> list.map { Row(it, contexts.countIn(it.id)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun create(name: String) = viewModelScope.launch { if (name.isNotBlank()) contexts.create(name) }
    fun rename(id: String, name: String) = viewModelScope.launch { if (name.isNotBlank()) contexts.rename(id, name) }
    fun setColor(id: String, color: String?) = viewModelScope.launch { contexts.setColor(id, color) }
    fun delete(id: String, onResult: (Boolean) -> Unit) = viewModelScope.launch { onResult(contexts.delete(id)) }
    fun moveAll(from: String, to: String) = viewModelScope.launch { actions.moveAll(from, to) }
    fun move(index: Int, delta: Int) = viewModelScope.launch {
        val list = rows.value.map { it.context }.toMutableList()
        val item = list.removeAt(index)
        list.add((index + delta).coerceIn(0, list.size), item)
        list.forEachIndexed { i, c -> if (c.position != i.toDouble()) contexts.setPosition(c.id, i.toDouble()) }
    }
}

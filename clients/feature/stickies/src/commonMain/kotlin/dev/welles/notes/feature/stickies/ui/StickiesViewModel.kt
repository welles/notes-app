package dev.welles.notes.feature.stickies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.welles.notes.core.data.CaptureTargets
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.EntityActions
import dev.welles.notes.core.data.NotesContext
import dev.welles.notes.core.model.EntityRef
import dev.welles.notes.feature.stickies.data.STICKY_TYPE
import dev.welles.notes.feature.stickies.data.Sticky
import dev.welles.notes.feature.stickies.data.StickyRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class StickiesViewModel(
    private val repository: StickyRepository,
    private val contexts: ContextRepository,
    private val actions: EntityActions,
    val targets: CaptureTargets,
) : ViewModel() {

    /** Post-its of the active context (pinned first, then manual order). */
    val stickies: StateFlow<List<Sticky>?> = contexts.active.filterNotNull()
        .flatMapLatest { repository.observe(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val contextList: StateFlow<List<NotesContext>> = contexts.contexts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setPinned(s: Sticky, pinned: Boolean) = viewModelScope.launch { repository.setPinned(s.id, pinned) }
    fun setColor(s: Sticky, color: String) = viewModelScope.launch { repository.setColor(s.id, color) }
    fun delete(s: Sticky) = viewModelScope.launch { repository.delete(s.id) }
    fun restore(id: String) = viewModelScope.launch { repository.restore(id) }
    fun reorder(id: String, after: String?, before: String?) = viewModelScope.launch { repository.reorder(id, after, before) }
    fun moveToContext(s: Sticky, contextId: String) = viewModelScope.launch { actions.moveToContext(listOf(EntityRef(STICKY_TYPE, s.id)), contextId) }
    fun convert(s: Sticky, targetKey: String, deleteSource: Boolean) =
        viewModelScope.launch { actions.convert(EntityRef(STICKY_TYPE, s.id), targetKey, deleteSource) }
}

class StickyEditorViewModel(private val repository: StickyRepository, private val contexts: ContextRepository) : ViewModel() {
    suspend fun load(id: String): Sticky? = repository.get(id)

    /** Saves on leaving the editor. New post-its are only created if something was entered. */
    fun save(id: String?, text: String, color: String, pinned: Boolean, onSaved: (String?) -> Unit = {}) = viewModelScope.launch {
        if (id == null) {
            if (text.isBlank()) return@launch onSaved(null)
            onSaved(repository.create(contexts.activeIdOrDefault(), text, color, pinned))
        } else {
            repository.update(id, text, color, pinned)
            onSaved(id)
        }
    }

    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }
    fun restore(id: String) = viewModelScope.launch { repository.restore(id) }
}

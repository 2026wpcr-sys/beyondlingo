package dev.beyonddev.beyondlingo.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beyonddev.beyondlingo.data.db.AppDatabase
import dev.beyonddev.beyondlingo.data.db.HistoryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).historyDao()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    val history: StateFlow<List<HistoryEntity>> =
        combine(_query, _showFavoritesOnly) { q, fav -> q to fav }
            .flatMapLatest { (q, fav) ->
                when {
                    fav -> dao.observeFavorites()
                    q.isBlank() -> dao.observeRecent()
                    else -> dao.search(q)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
    }

    fun toggleFavoritesFilter() {
        _showFavoritesOnly.value = !_showFavoritesOnly.value
    }

    fun toggleFavorite(entity: HistoryEntity) {
        viewModelScope.launch { dao.setFavorite(entity.id, !entity.isFavorite) }
    }

    fun delete(entity: HistoryEntity) {
        viewModelScope.launch { dao.deleteById(entity.id) }
    }

    fun clearAll() {
        viewModelScope.launch { dao.clearAll() }
    }
}

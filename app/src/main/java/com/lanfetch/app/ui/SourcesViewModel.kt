package com.lanfetch.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.Source
import com.lanfetch.app.net.SyncProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategoryPickerState(
    val source: Source,
    val categories: List<String>,
)

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LanFetchApp).container
    private val sources = container.sourceRepository
    private val sync = container.syncManager

    val all: StateFlow<List<Source>> = sources.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncing: StateFlow<Map<Long, SyncProgress>> = sync.progress

    private val _picker = MutableStateFlow<CategoryPickerState?>(null)
    val picker: StateFlow<CategoryPickerState?> = _picker.asStateFlow()

    private val _pickerLoading = MutableStateFlow(false)
    val pickerLoading: StateFlow<Boolean> = _pickerLoading.asStateFlow()

    fun syncSource(source: Source) = sync.syncSource(source.id)

    fun syncAll() = sync.syncAll()

    fun cancelSync(source: Source) = sync.cancel(source.id)

    fun addSource(name: String, url: String) {
        viewModelScope.launch {
            runCatching { sources.add(name, url) }
                .onFailure { sync.message("Could not add server: ${it.message}") }
        }
    }

    fun deleteSource(source: Source) {
        sync.cancel(source.id)
        viewModelScope.launch {
            runCatching { sources.delete(source.id) }
                .onFailure { sync.message("Could not remove server: ${it.message}") }
        }
    }

    fun setSourceEnabled(source: Source, enabled: Boolean) {
        viewModelScope.launch { sources.setEnabled(source, enabled) }
    }

    fun openCategoryPicker(source: Source) {
        viewModelScope.launch {
            _pickerLoading.value = true
            try {
                val categories = withContext(Dispatchers.IO) {
                    container.scraper().fetchCategories(source)
                }
                _picker.value = CategoryPickerState(source, categories)
            } catch (e: Exception) {
                sync.message("${source.name}: could not list categories (${e.message})")
            } finally {
                _pickerLoading.value = false
            }
        }
    }

    fun saveCategories(source: Source, selected: Set<String>, allCategories: List<String>) {
        viewModelScope.launch {
            // "Everything ticked" is stored as an empty selection = crawl all.
            val stored = if (selected.size == allCategories.size) emptySet() else selected
            sources.setCategories(source, stored)
            _picker.value = null
            sync.message(
                if (stored.isEmpty()) "${source.name}: syncing all categories"
                else "${source.name}: syncing ${stored.size} categories"
            )
        }
    }

    fun closeCategoryPicker() {
        _picker.value = null
    }
}

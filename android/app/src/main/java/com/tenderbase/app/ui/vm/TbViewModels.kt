package com.tenderbase.app.ui.vm

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tenderbase.app.ApiClient
import com.tenderbase.app.Dashboard
import com.tenderbase.app.DateUtils
import com.tenderbase.app.DeadlineStatus
import com.tenderbase.app.DocumentStore
import com.tenderbase.app.ErrorMessages
import com.tenderbase.app.SearchFilters
import com.tenderbase.app.SortOption
import com.tenderbase.app.Tender
import com.tenderbase.app.TenderRepository
import com.tenderbase.app.UserErrorKind
import com.tenderbase.app.DocumentFilter
import com.tenderbase.app.DateFilter
import com.tenderbase.app.StatusFilter
import com.tenderbase.app.ChecklistItemEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

// ---------------------------------------------------------------- discovery

enum class FeedState { LOADING, READY, EMPTY, ERROR }

data class FeedUiState(
    val rows: List<Tender> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val hasMore: Boolean = false,
    val offline: Boolean = false,
    val state: FeedState = FeedState.LOADING,
    val errorKind: UserErrorKind? = null,
)

/** Quick filter chips below search (spec §5). */
enum class QuickChip { CLOSING_SOON, NEW_TODAY, MY_CATEGORIES, MY_PROVINCES, WITH_DOCS }

/** Result of saving the current discovery state as an alert. */
enum class SaveSearchOutcome { SAVED, QUEUED_OFFLINE, DUPLICATE, FAILED }

/**
 * The single discovery state machine (search + filters + sort + pagination +
 * quick chips). Backs both the Home and Search destinations, so switching
 * tabs never re-fetches or loses state.
 */
class DiscoveryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TenderRepository(app)

    private val _filters = MutableStateFlow(SearchFilters(sort = persistedSort()))
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    private val _feed = MutableStateFlow(FeedUiState())
    val feed: StateFlow<FeedUiState> = _feed.asStateFlow()

    val facets = MutableStateFlow<ApiClient.Facets?>(null)
    val lastUpdated = MutableStateFlow(repo.lastFeedUpdate())
    val recentSearches = MutableStateFlow(repo.recentSearches())
    val refreshing = MutableStateFlow(false)

    val savedIds: StateFlow<Set<Int>> = repo.savedTendersFlow
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val unreadCount: StateFlow<Int> = repo.unreadCountFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val urgentSaved: StateFlow<List<Tender>> = repo.savedTendersFlow
        .map { list -> list.map { it.toTender() }.filter { DeadlineStatus.isClosingWithin48h(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var loadJob: Job? = null
    private var currentPage = 1
    private var hasMore = false
    private var isLoadingMore = false
    private var typeJob: Job? = null
    private var appliedQuery: String = _filters.value.query
    private var started = false

    // ------------------------------------------------------------- lifecycle

    /** First load, triggered once by the host. */
    fun start() {
        if (started) return
        started = true
        load(refresh = false, forceSkeleton = true)
    }

    /** Apply discovery state coming from a saved search or preset. */
    fun applyFiltersJson(json: String?) {
        _filters.value = SearchFilters.fromJson(json)
        appliedQuery = _filters.value.query.trim()
        load(refresh = false, forceSkeleton = true)
    }

    fun applyPresetClosingWeek() {
        _filters.value = SearchFilters.closingWeekPreset(sort = _filters.value.sort)
        appliedQuery = ""
        load(refresh = false, forceSkeleton = true)
    }

    fun resetToLatest() {
        _filters.value = SearchFilters(sort = _filters.value.sort)
        appliedQuery = ""
        load(refresh = false, forceSkeleton = true)
    }

    // ---------------------------------------------------------------- search

    fun onType(text: String) {
        _filters.update { it.copy(query = text) }
        typeJob?.cancel()
        typeJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            commitQuery()
        }
    }

    /** Commit the typed query immediately (IME action / chip tap). */
    fun commitQuery() {
        typeJob?.cancel()
        val q = _filters.value.query.trim()
        if (q.length >= MIN_RECENT_LEN) addRecentSearch(q)
        if (q == appliedQuery) return
        appliedQuery = q
        load(refresh = false, forceSkeleton = false)
    }

    fun clearQuery() {
        _filters.update { it.copy(query = "") }
        appliedQuery = ""
        load(refresh = false, forceSkeleton = false)
    }

    /** Search a fixed phrase (suggestions / recent chips). */
    fun searchFor(text: String) {
        _filters.update { it.copy(query = text) }
        addRecentSearch(text)
        appliedQuery = text.trim()
        load(refresh = false, forceSkeleton = false)
    }

    private fun addRecentSearch(query: String) {
        repo.addRecentSearch(query)
        recentSearches.value = repo.recentSearches()
    }

    fun removeRecentSearch(query: String) {
        repo.removeRecentSearch(query)
        recentSearches.value = repo.recentSearches()
    }

    fun clearRecentSearches() {
        repo.clearRecentSearches()
        recentSearches.value = emptyList()
    }

    // ----------------------------------------------------------- quick chips

    fun isQuickActive(chip: QuickChip): Boolean {
        val f = _filters.value
        return when (chip) {
            QuickChip.CLOSING_SOON -> f.status == StatusFilter.CLOSING_SOON
            QuickChip.NEW_TODAY -> f.dateFilter == DateFilter.PUBLISHED_TODAY
            QuickChip.MY_CATEGORIES ->
                repo.getSelectedCategories().isNotEmpty() &&
                    f.categories.toSet() == repo.getSelectedCategories()
            QuickChip.MY_PROVINCES ->
                repo.getSelectedProvinces().isNotEmpty() &&
                    f.provinces.toSet() == repo.getSelectedProvinces()
            QuickChip.WITH_DOCS -> f.docs == DocumentFilter.HAS_DOCS
        }
    }

    fun toggleQuick(chip: QuickChip) {
        _filters.update { f ->
            when (chip) {
                QuickChip.CLOSING_SOON -> f.copy(
                    status = if (f.status == StatusFilter.CLOSING_SOON) null else StatusFilter.CLOSING_SOON
                )
                QuickChip.NEW_TODAY -> f.copy(
                    dateFilter = if (f.dateFilter == DateFilter.PUBLISHED_TODAY) DateFilter.ANY
                    else DateFilter.PUBLISHED_TODAY
                )
                QuickChip.MY_CATEGORIES -> {
                    val sel = repo.getSelectedCategories().sorted()
                    if (f.categories.toSet() == sel.toSet()) f.copy(categories = emptyList())
                    else f.copy(categories = sel)
                }
                QuickChip.MY_PROVINCES -> {
                    val sel = repo.getSelectedProvinces().sorted()
                    if (f.provinces.toSet() == sel.toSet()) f.copy(provinces = emptyList())
                    else f.copy(provinces = sel)
                }
                QuickChip.WITH_DOCS -> f.copy(
                    docs = if (f.docs == DocumentFilter.HAS_DOCS) DocumentFilter.ANY
                    else DocumentFilter.HAS_DOCS
                )
            }
        }
        load(refresh = false, forceSkeleton = false)
    }

    // ------------------------------------------------------- filters / sort

    /** Merge a filter selection from the sheet, keeping query + sort. */
    fun applySheetFilters(applied: SearchFilters) {
        _filters.value = applied.copy(
            query = _filters.value.query,
            sort = _filters.value.sort,
        )
        load(refresh = false, forceSkeleton = false)
    }

    fun setSort(sort: SortOption) {
        _filters.update { it.copy(sort = sort) }
        prefs().edit().putString(PREF_SORT, sort.key).apply()
        load(refresh = false, forceSkeleton = false)
    }

    fun removeProvince(p: String) { mutateAndLoad { it.copy(provinces = it.provinces - p) } }
    fun removeCategory(c: String) { mutateAndLoad { it.copy(categories = it.categories - c) } }
    fun removeSource(s: String) { mutateAndLoad { it.copy(sources = it.sources - s) } }
    fun clearStatus() { mutateAndLoad { it.copy(status = null) } }
    fun clearDate() { mutateAndLoad { it.copy(dateFilter = DateFilter.ANY, closingAfter = null, closingBefore = null) } }
    fun clearOrg() { mutateAndLoad { it.copy(organisation = null) } }
    fun clearDocs() { mutateAndLoad { it.copy(docs = DocumentFilter.ANY) } }
    fun clearAllFacetFilters() {
        mutateAndLoad {
            it.copy(
                provinces = emptyList(), categories = emptyList(), sources = emptyList(),
                status = null, dateFilter = DateFilter.ANY, closingAfter = null,
                closingBefore = null, organisation = null, docs = DocumentFilter.ANY,
            )
        }
    }

    private fun mutateAndLoad(block: (SearchFilters) -> SearchFilters) {
        _filters.update(block)
        load(refresh = false, forceSkeleton = false)
    }

    // ----------------------------------------------------------- saved search

    fun saveCurrentSearchNamed(name: String, onResult: (SaveSearchOutcome) -> Unit) {
        val f = _filters.value
        if (f.isDefault()) {
            onResult(SaveSearchOutcome.FAILED)
            return
        }
        val clientId = repo.clientId()
        val payload = f.toSavedSearchPayload()
        viewModelScope.launch {
            try {
                ApiClient.createSavedSearch(clientId, name, payload)
                onResult(SaveSearchOutcome.SAVED)
            } catch (e: ApiClient.ApiException) {
                onResult(
                    if (e.statusCode == 409) SaveSearchOutcome.DUPLICATE else SaveSearchOutcome.FAILED
                )
            } catch (e: Exception) {
                repo.queueSavedSearch(name, payload.toString())
                onResult(SaveSearchOutcome.QUEUED_OFFLINE)
            }
        }
    }

    // ------------------------------------------------------------- list ops

    fun toggleSave(t: Tender, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val saved = repo.toggleSave(t)
            onResult(saved)
        }
    }

    fun hide(t: Tender) {
        repo.hideTender(t.id)
        _feed.update { it.copy(rows = it.rows.filterNot { r -> r.id == t.id }) }
    }

    fun unhideAllAndReload() {
        repo.unhideAllTenders()
        load(refresh = false, forceSkeleton = false)
    }

    fun refresh() {
        refreshing.value = true
        load(refresh = true, forceSkeleton = false)
        refreshFacets(force = true)
    }

    fun retry() {
        load(refresh = false, forceSkeleton = true)
    }

    private fun endRefresh() {
        refreshing.value = false
    }

    // ------------------------------------------------------------ data flow

    private fun load(refresh: Boolean, forceSkeleton: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val keepRows = _feed.value.rows.isNotEmpty() && !forceSkeleton
            _feed.update {
                it.copy(
                    loading = true,
                    state = FeedState.LOADING,
                    rows = if (keepRows) it.rows else emptyList(),
                    moreFailed = false,
                )
            }
            refreshFacets(force = refresh)
            try {
                val f = _filters.value
                val pg = ApiClient.fetchTenders(page = 1, limit = PAGE_SIZE, filters = f)
                currentPage = pg.page
                hasMore = currentPage < pg.totalPages
                val rows = Dashboard.filterHidden(pg.items, repo.hiddenTenderIds())
                if (rows.isNotEmpty()) repo.cacheTenders(rows)
                repo.setLastFeedUpdate(System.currentTimeMillis())
                lastUpdated.value = repo.lastFeedUpdate()
                _feed.update {
                    it.copy(
                        rows = rows,
                        total = pg.total,
                        loading = false,
                        hasMore = hasMore,
                        offline = false,
                        state = if (rows.isEmpty()) FeedState.EMPTY else FeedState.READY,
                        errorKind = null,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "feed load failed: ${ErrorMessages.forLogging(e)}")
                val cached = Dashboard.filterHidden(repo.getCachedTenders(), repo.hiddenTenderIds())
                if (cached.isNotEmpty()) {
                    _feed.update {
                        it.copy(
                            rows = cached,
                            total = cached.size,
                            loading = false,
                            hasMore = false,
                            offline = true,
                            state = FeedState.READY,
                            errorKind = null,
                        )
                    }
                } else {
                    _feed.update {
                        it.copy(
                            loading = false,
                            rows = emptyList(),
                            state = FeedState.ERROR,
                            errorKind = if (e is ApiClient.ApiException)
                                ErrorMessages.kindOfHttp(e.statusCode) else ErrorMessages.kindOf(e),
                        )
                    }
                }
            } finally {
                endRefresh()
            }
        }
    }

    fun loadMore() {
        if (!hasMore || isLoadingMore || _feed.value.loading) return
        isLoadingMore = true
        _feed.update { it.copy(loadingMore = true, moreFailed = false) }
        val next = currentPage + 1
        viewModelScope.launch {
            try {
                val f = _filters.value
                val pg = ApiClient.fetchTenders(page = next, limit = PAGE_SIZE, filters = f)
                currentPage = pg.page
                hasMore = currentPage < pg.totalPages
                val seen = _feed.value.rows.map { it.id }.toSet()
                val more = Dashboard.filterHidden(
                    pg.items.filter { it.id !in seen },
                    repo.hiddenTenderIds(),
                )
                _feed.update {
                    it.copy(
                        rows = it.rows + more,
                        total = pg.total,
                        hasMore = hasMore,
                        loadingMore = false,
                        moreFailed = false,
                    )
                }
                if (more.isNotEmpty()) repo.cacheTenders(more)
            } catch (e: Exception) {
                Log.w(TAG, "load-more failed: ${ErrorMessages.forLogging(e)}")
                _feed.update { it.copy(loadingMore = false, moreFailed = true) }
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun refreshFacets(force: Boolean) {
        if (!force && facets.value != null) return
        viewModelScope.launch {
            try {
                facets.value = ApiClient.fetchFacets()
                repo.flushSavedSearchQueue()
            } catch (e: Exception) {
                Log.d(TAG, "facets unavailable: ${ErrorMessages.forLogging(e)}")
            }
        }
    }

    // ------------------------------------------------------------- helpers

    fun selectedCategories(): List<String> = repo.getSelectedCategories().sorted()
    fun selectedProvinces(): List<String> = repo.getSelectedProvinces().sorted()

    private fun persistedSort(): SortOption =
        SortOption.fromKey(prefs().getString(PREF_SORT, null))

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(PREFS, 0)

    companion object {
        private const val TAG = "TenderBase"
        private const val PAGE_SIZE = 20
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val MIN_RECENT_LEN = 2
        private const val PREFS = "tenderbase_prefs"
        private const val PREF_SORT = "last_sort"
    }
}

// ------------------------------------------------------------------- detail

sealed interface DocDownloadState {
    data object Idle : DocDownloadState
    data object Working : DocDownloadState
    data class Done(val file: File) : DocDownloadState
    data class Failed(val kind: UserErrorKind) : DocDownloadState
}

data class DetailUiState(
    val loading: Boolean = true,
    val tender: Tender? = null,
    val errorKind: UserErrorKind? = null,
    val saved: Boolean = false,
    val offlineCopy: Boolean = false,
)

/**
 * Tender detail + bid workspace + document downloads. Downloads report
 * friendly [UserErrorKind]s — never raw exceptions (spec §17).
 */
class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TenderRepository(app)

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _docStates = MutableStateFlow<Map<String, DocDownloadState>>(emptyMap())
    val docStates: StateFlow<Map<String, DocDownloadState>> = _docStates.asStateFlow()

    private val _noteText = MutableStateFlow<String?>(null)
    val noteText: StateFlow<String?> = _noteText.asStateFlow()

    private val _checklist = MutableStateFlow<List<ChecklistItemEntity>>(emptyList())
    val checklist: StateFlow<List<ChecklistItemEntity>> = _checklist.asStateFlow()

    private var tenderId = -1
    private var noteJob: Job? = null
    private var checklistJob: Job? = null
    private var backupJob: Job? = null
    private var lastPushedState: String? = null

    fun start(id: Int) {
        if (id == tenderId) return
        tenderId = id
        observeWorkspace(id)
        load()
    }

    private fun observeWorkspace(id: Int) {
        viewModelScope.launch { repo.ensureDefaultChecklist(id) }
        noteJob?.cancel()
        noteJob = viewModelScope.launch {
            repo.noteFlow(id).collect { note ->
                _noteText.value = note?.note
                scheduleWorkspaceBackup()
            }
        }
        checklistJob?.cancel()
        checklistJob = viewModelScope.launch {
            repo.checklistFlow(id).collect { items ->
                _checklist.value = items
                scheduleWorkspaceBackup()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorKind = null) }
            try {
                val t = ApiClient.fetchTender(tenderId)
                primeDocStates(t)
                _state.update {
                    it.copy(
                        loading = false,
                        tender = t,
                        saved = repo.isSaved(t.id),
                        errorKind = null,
                        offlineCopy = false,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "detail load failed: ${ErrorMessages.forLogging(e)}")
                val cached = repo.getCachedTenders().firstOrNull { it.id == tenderId }
                if (cached != null) {
                    _state.update {
                        it.copy(
                            loading = false,
                            tender = cached,
                            saved = repo.isSaved(cached.id),
                            errorKind = null,
                            offlineCopy = true,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            errorKind = if (e is ApiClient.ApiException)
                                ErrorMessages.kindOfHttp(e.statusCode)
                            else ErrorMessages.kindOf(e),
                        )
                    }
                }
            }
        }
    }

    private fun primeDocStates(t: Tender) {
        val context = getApplication<Application>()
        val map = t.documents.associate { d ->
            val target = DocumentStore.fileFor(context, t.id, d.title, d.url, d.mime)
            d.url to if (target.exists() && target.length() > 0)
                DocDownloadState.Done(target) else DocDownloadState.Idle
        }
        _docStates.value = map
    }

    fun toggleSave() {
        val t = _state.value.tender ?: return
        viewModelScope.launch {
            val now = repo.toggleSave(t)
            _state.update { it.copy(saved = now) }
        }
    }

    // ------------------------------------------------------------ workspace

    fun saveNote(text: String) {
        viewModelScope.launch { repo.saveNote(tenderId, text) }
    }

    fun addChecklistItem(label: String) {
        if (label.isBlank()) return
        viewModelScope.launch { repo.addChecklistItem(tenderId, label) }
    }

    fun renameChecklistItem(id: Long, label: String) {
        viewModelScope.launch { repo.renameChecklistItem(id, label) }
    }

    fun deleteChecklistItem(id: Long) {
        viewModelScope.launch { repo.deleteChecklistItem(id) }
    }

    fun toggleChecklistDone(id: Long, done: Boolean) {
        viewModelScope.launch { repo.setChecklistDone(id, done) }
    }

    fun moveChecklistItem(id: Long, up: Boolean) {
        viewModelScope.launch { repo.moveChecklistItem(tenderId, id, up) }
    }

    fun resetChecklist() {
        viewModelScope.launch {
            repo.replaceChecklist(
                tenderId,
                com.tenderbase.app.BidPack.defaultChecklist().map { it to false }
            )
        }
    }

    /** Debounced server backup of the workspace (saved tenders only). */
    private fun scheduleWorkspaceBackup() {
        if (!_state.value.saved) return
        val items = _checklist.value.map { it.label to it.isDone }
        val signature = _noteText.value.orEmpty() + "|" +
            items.joinToString(";") { "${it.first}=${it.second}" }
        if (signature == lastPushedState) return
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            delay(800)
            try {
                repo.pushWorkspace(tenderId, _noteText.value, items)
                lastPushedState = signature
            } catch (e: Exception) {
                Log.d(TAG, "workspace backup deferred: ${ErrorMessages.forLogging(e)}")
            }
        }
    }

    // ------------------------------------------------------------ downloads

    /**
     * Download a tender document. The source certificate chains are known to
     * be flaky on some municipal sites; a TLS failure transparently retries
     * once over cleartext where the OS config permits it, and any final
     * failure is surfaced as a [UserErrorKind], never a raw exception.
     */
    fun download(doc: com.tenderbase.app.TenderDoc) {
        val t = _state.value.tender ?: return
        val context = getApplication<Application>()
        val target = DocumentStore.fileFor(context, t.id, doc.title, doc.url, doc.mime)
        if (target.exists() && target.length() > 0) return
        _docStates.update { it + (doc.url to DocDownloadState.Working) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ok = fetchToFile(doc.url, target)
                if (ok && target.exists() && target.length() > 0) {
                    _docStates.update { it + (doc.url to DocDownloadState.Done(target)) }
                } else {
                    target.delete()
                    _docStates.update {
                        it + (doc.url to DocDownloadState.Failed(UserErrorKind.SERVER_UNAVAILABLE))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "doc download failed: ${ErrorMessages.forLogging(e)}")
                target.delete()
                _docStates.update {
                    it + (doc.url to DocDownloadState.Failed(ErrorMessages.kindOf(e)))
                }
            }
        }
    }

    fun retryDownload(doc: com.tenderbase.app.TenderDoc) {
        _docStates.update { it + (doc.url to DocDownloadState.Idle) }
        download(doc)
    }

    private suspend fun fetchToFile(urlStr: String, target: File): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                rawDownload(urlStr, target)
            } catch (e: Exception) {
                // Cert-chain failure on a source the OS allows cleartext for
                // (see network_security_config): one transparent fallback.
                val host = runCatching { java.net.URL(urlStr).host }.getOrDefault("")
                if (ErrorMessages.kindOf(e) == UserErrorKind.SECURITY &&
                    urlStr.startsWith("https://") &&
                    host.endsWith("etenders.gov.za")
                ) {
                    Log.i(TAG, "TLS retry fallback to cleartext for $host")
                    rawDownload(urlStr.replaceFirst("https://", "http://"), target)
                } else throw e
            }
        }

    private fun rawDownload(urlStr: String, target: File): Boolean {
        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android; TenderBase) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept", "*/*")
            if (conn.responseCode !in 200..299) return false
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    fun bidPackText(): String {
        val t = _state.value.tender ?: return ""
        val items = _checklist.value.map { it.label to it.isDone }
        return com.tenderbase.app.BidPack.build(t, _noteText.value, items)
    }

    fun shareSummary(): String {
        val t = _state.value.tender ?: return ""
        return com.tenderbase.app.TenderActions.shareSummary(t)
    }

    companion object {
        private const val TAG = "TenderBase"
    }
}

// -------------------------------------------------------------------- saved

data class SavedRow(
    val tender: Tender,
    val done: Int,
    val total: Int,
    val hasNote: Boolean,
    val savedAt: Long,
)

/** Saved tenders joined with bid-prep stats (all local Room flows). */
class SavedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TenderRepository(app)

    val rows: StateFlow<List<SavedRow>> = combine(
        repo.savedTendersFlow,
        repo.checklistStatsFlow(),
        repo.notedTenderIdsFlow()
    ) { saved, stats, noted ->
        val byId = stats.associateBy { it.tenderId }
        saved.map { e ->
            val st = byId[e.id]
            SavedRow(
                tender = e.toTender(),
                done = st?.done ?: 0,
                total = st?.total ?: 0,
                hasNote = e.id in noted,
                savedAt = e.savedAt,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun unsave(id: Int) {
        viewModelScope.launch { repo.removeSaved(id) }
    }
}

// -------------------------------------------------------------- notifications

data class NotificationUiRow(
    val id: Long,
    val tenderId: Int,
    val title: String,
    val body: String,
    val timestamp: Long,
    val isRead: Boolean,
)

class NotificationsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TenderRepository(app)

    val items: StateFlow<List<NotificationUiRow>> = repo.notificationHistoryFlow
        .map { list ->
            list.map {
                NotificationUiRow(
                    id = it.id, tenderId = it.tenderId, title = it.title,
                    body = it.body, timestamp = it.timestamp, isRead = it.isRead,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun markRead(id: Long) {
        viewModelScope.launch { repo.markNotificationRead(id) }
    }

    fun markAllRead() {
        viewModelScope.launch { repo.markAllNotificationsRead() }
    }
}

// ---------------------------------------------------------------- deadlines

class DeadlinesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TenderRepository(app)

    private val _closingSoon = MutableStateFlow(
        Triple<List<Tender>, Boolean, UserErrorKind?>(emptyList(), true, null)
    )
    val closingSoon: StateFlow<Triple<List<Tender>, Boolean, UserErrorKind?>> =
        _closingSoon.asStateFlow()

    val savedTenders: StateFlow<List<Tender>> = repo.savedTendersFlow
        .map { list -> list.map { it.toTender() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val alerts: StateFlow<List<com.tenderbase.app.NotificationEntity>> =
        repo.notificationHistoryFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyList()
        )

    val savedIds: StateFlow<Set<Int>> = repo.savedTendersFlow
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleSave(t: Tender) {
        viewModelScope.launch { repo.toggleSave(t) }
    }

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _closingSoon.update { it.copy(second = true) }
            try {
                val page = ApiClient.fetchTenders(
                    page = 1, limit = 20, filters = Dashboard.closingThisWeekFilters()
                )
                val rows = Dashboard.filterHidden(page.items, repo.hiddenTenderIds())
                _closingSoon.value = Triple(rows, false, null)
            } catch (e: Exception) {
                Log.w(TAG, "deadlines feed failed: ${ErrorMessages.forLogging(e)}")
                _closingSoon.update { Triple(it.first, false, ErrorMessages.kindOf(e)) }
            }
        }
    }
}

// ---------------------------------------------------------- saved searches

class SavedSearchesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TenderRepository(app)

    data class State(
        val loading: Boolean = true,
        val items: List<ApiClient.SavedSearchInfo> = emptyList(),
        val error: UserErrorKind? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val items = ApiClient.fetchSavedSearches(repo.clientId())
                _state.value = State(loading = false, items = items)
            } catch (e: Exception) {
                Log.w(TAG, "saved searches failed: ${ErrorMessages.forLogging(e)}")
                _state.update {
                    it.copy(
                        loading = false,
                        error = if (e is ApiClient.ApiException)
                            ErrorMessages.kindOfHttp(e.statusCode) else ErrorMessages.kindOf(e),
                    )
                }
            }
        }
    }

    fun setAlerts(info: ApiClient.SavedSearchInfo, enabled: Boolean, onFail: () -> Unit) {
        viewModelScope.launch {
            try {
                ApiClient.setSavedSearchAlerts(repo.clientId(), info.id, enabled)
                _state.update {
                    it.copy(
                        items = it.items.map { s ->
                            if (s.id == info.id) s.copy(alertsEnabled = enabled) else s
                        }
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "alerts toggle failed: ${ErrorMessages.forLogging(e)}")
                onFail()
            }
        }
    }

    fun delete(info: ApiClient.SavedSearchInfo, onDeleted: () -> Unit, onFail: () -> Unit) {
        viewModelScope.launch {
            try {
                ApiClient.deleteSavedSearch(repo.clientId(), info.id)
                _state.update { it.copy(items = it.items.filterNot { s -> s.id == info.id }) }
                onDeleted()
            } catch (e: Exception) {
                Log.w(TAG, "search delete failed: ${ErrorMessages.forLogging(e)}")
                onFail()
            }
        }
    }
}

// -------------------------------------------------------------- downloads

data class DownloadRow(val file: File, val sizeLabel: String)

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {

    private val rows = MutableStateFlow<List<DownloadRow>>(emptyList())
    val files: StateFlow<List<DownloadRow>> = rows.asStateFlow()

    fun refresh() {
        val context = getApplication<Application>()
        rows.value = DocumentStore.listDocuments(context).map { f ->
            DownloadRow(f, com.tenderbase.app.TenderActions.formatFileSize(f.length()) ?: "—")
        }
    }

    fun delete(file: File) {
        runCatching { file.delete() }
        refresh()
    }
}

// ----------------------------------------------------------------- settings

enum class ThemeMode(val key: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TenderRepository(app)

    val connection = MutableStateFlow<Boolean?>(null)

    val themeMode = MutableStateFlow(repoThemeMode())

    fun setTheme(mode: ThemeMode) {
        themeMode.value = mode
        repoSetThemeMode(mode)
    }

    fun checkConnection() {
        viewModelScope.launch {
            connection.value = null
            connection.value = try {
                ApiClient.healthOk()
            } catch (e: Exception) {
                false
            }
        }
    }

    /** -1 when the server is unreachable, otherwise restored-workspace count. */
    suspend fun restoreWorkspaces(): Int = repo.restoreWorkspacesFromServer()

    private fun prefs() =
        getApplication<Application>().getSharedPreferences("tenderbase_prefs", 0)

    private fun repoThemeMode(): ThemeMode =
        ThemeMode.entries.firstOrNull {
            it.key == prefs().getString("theme_mode", null)
        } ?: ThemeMode.SYSTEM

    private fun repoSetThemeMode(mode: ThemeMode) {
        prefs().edit().putString("theme_mode", mode.key).apply()
    }
}

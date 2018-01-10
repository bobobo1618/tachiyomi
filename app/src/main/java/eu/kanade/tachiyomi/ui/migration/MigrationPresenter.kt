package eu.kanade.tachiyomi.ui.migration

import android.os.Bundle
import com.jakewharton.rxrelay.BehaviorRelay
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.combineLatest
import eu.kanade.tachiyomi.util.syncChaptersWithSource
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrationPresenter(
        private val sourceManager: SourceManager = Injekt.get(),
        private val db: DatabaseHelper = Injekt.get(),
        private val preferences: PreferencesHelper = Injekt.get()
) : BasePresenter<MigrationController>() {

    var state = ViewState()
        private set(value) {
            field = value
            stateRelay.call(value)
        }

    private val stateRelay = BehaviorRelay.create(state)

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        db.getLibraryMangas()
                .asRxObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { state = state.copy(sourcesWithManga = findSourcesWithManga(it)) }
                .combineLatest(stateRelay.map { it.selectedSource }
                        .distinctUntilChanged(),
                        { library, source -> library to source })
                .filter { (_, source) -> source != null }
                .observeOn(Schedulers.io())
                .map { (library, source) -> libraryToMigrationItem(library, source!!.id) }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { state = state.copy(mangaForSource = it) }
                .subscribe()

        stateRelay.subscribeLatestCache(MigrationController::render)
    }

    fun setSelectedSource(source: Source) {
        state = state.copy(selectedSource = source, mangaForSource = emptyList())
    }

    fun deselectSource() {
        state = state.copy(selectedSource = null, mangaForSource = emptyList())
    }

    private fun findSourcesWithManga(library: List<Manga>): List<SourceItem> {
        val header = SelectionHeader()
        return library.map { it.source }.toSet()
                .mapNotNull { if (it != LocalSource.ID) sourceManager.get(it) else null }
                .map { SourceItem(it, header) }
    }

    private fun libraryToMigrationItem(library: List<Manga>, sourceId: Long): List<MangaItem> {
        return library.filter { it.source == sourceId }.map(::MangaItem)
    }

    fun migrateManga(prevManga: Manga, manga: Manga, replace: Boolean) {
        val source = sourceManager.get(manga.source) ?: return

        state = state.copy(isReplacingManga = true)

        Observable.defer { source.fetchChapterList(manga) }
                // Update chapters
                .doOnNext { mangaChapters ->
                    if (!preferences.migrateChapters().getOrDefault()) return@doOnNext

                    syncChaptersWithSource(db, mangaChapters, manga, source)

                    val prevMangaChapters = db.getChapters(prevManga).executeAsBlocking()
                    val maxChapterRead = prevMangaChapters.filter { it.read }
                            .maxBy { it.chapter_number }?.chapter_number
                    if (maxChapterRead != null) {
                        val dbChapters = db.getChapters(manga).executeAsBlocking()
                        for (chapter in dbChapters) {
                            if (chapter.isRecognizedNumber && chapter.chapter_number < maxChapterRead) {
                                chapter.read = true
                            }
                        }
                        db.insertChapters(dbChapters).executeAsBlocking()
                    }
                }
                .map { Unit }
                // Update categories
                .doOnNext {
                    if (!preferences.migrateCategories().getOrDefault()) return@doOnNext

                    val categories = db.getCategoriesForManga(prevManga).executeAsBlocking()
                    val mangaCategories = categories.map { MangaCategory.create(manga, it) }
                    db.setMangaCategories(mangaCategories, listOf(manga))
                }
                // Update tracking
                .doOnNext {
                    if (!preferences.migrateTracks().getOrDefault()) return@doOnNext

                    val tracks = db.getTracks(prevManga).executeAsBlocking()
                    for (track in tracks) {
                        track.id = null
                        track.manga_id = manga.id!!
                    }
                    db.insertTracks(tracks).executeAsBlocking()
                }
                // Swap favorite status if everything went ok
                .doOnNext {
                    if (replace) {
                        prevManga.favorite = false
                        db.updateMangaFavorite(prevManga).executeAsBlocking()
                    }
                    manga.favorite = true
                    db.updateMangaFavorite(manga).executeAsBlocking()
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnUnsubscribe { state = state.copy(isReplacingManga = false) }
                .subscribe()
    }

}
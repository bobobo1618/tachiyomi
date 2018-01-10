package eu.kanade.tachiyomi.ui.migration

import com.afollestad.materialdialogs.MaterialDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.ui.catalogue.global_search.CatalogueSearchController
import uy.kohesive.injekt.injectLazy

class SearchController(private val manga: Manga? = null) :
        CatalogueSearchController(manga?.title) {

    private val preferences: PreferencesHelper by injectLazy()

    override fun onMangaClick(newManga: Manga) {
        val activity = activity ?: return
        val manga = manga ?: return

        val options = arrayOf(
                R.string.chapters,
                R.string.categories,
                R.string.track
        )

        val settings = arrayOf(
                preferences.migrateChapters(),
                preferences.migrateCategories(),
                preferences.migrateTracks()
        )

        val preselected = settings.mapIndexedNotNull { index, preference ->
            if (preference.getOrDefault()) index else null
        }


        MaterialDialog.Builder(activity)
                .content(R.string.migration_dialog_what_to_include)
                .items(options.map { activity.getString(it) })
                .alwaysCallMultiChoiceCallback()
                .itemsCallbackMultiChoice(preselected.toTypedArray(), { _, positions, _ ->
                    // Save current settings for the next time
                    settings.forEachIndexed { index, preference ->
                        preference.set(index in positions)
                    }
                    true
                })
                .positiveText(R.string.migrate)
                .negativeText(R.string.copy)
                .neutralText(android.R.string.cancel)
                .onPositive { _, _ ->
                    val target = targetController as? MigrationController
                    if (target != null) {
                        router.popController(this)
                        target.onMigrateSelected(manga, newManga)
                    }
                }
                .onNegative { _, _ ->
                    val target = targetController as? MigrationController
                    if (target != null) {
                        router.popController(this)
                        target.onCopySelected(manga, newManga)
                    }
                }
                .show()
    }

}
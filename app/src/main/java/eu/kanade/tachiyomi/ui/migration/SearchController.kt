package eu.kanade.tachiyomi.ui.migration

import com.afollestad.materialdialogs.MaterialDialog
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.ui.catalogue.global_search.CatalogueSearchController

class SearchController(private val manga: Manga? = null) :
        CatalogueSearchController(manga?.title) {

    override fun onMangaClick(newManga: Manga) {
        val activity = activity ?: return

        MaterialDialog.Builder(activity)
                .content("Do you want to replace this manga?")
                .positiveText(android.R.string.yes)
                .negativeText(android.R.string.no)
                .onPositive { _, _ ->
                    val target = targetController as? MigrationController
                    if (target != null && manga != null) {
                        router.popController(this)
                        target.onReplacementSelected(manga, newManga)
                    }
                }
                .show()
    }

}
package eu.kanade.tachiyomi.animeextension.all.supjav2

import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class SupJav2Factory : AnimeSourceFactory {
    override fun createSources() = listOf(
        SupJav2("en"),
        SupJav2("ja"),
        SupJav2("zh"),
    )
}

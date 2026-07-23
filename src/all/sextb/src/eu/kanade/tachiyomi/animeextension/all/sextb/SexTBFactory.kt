package eu.kanade.tachiyomi.animeextension.all.sextb

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class SexTBFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        SexTB(),
    )
}

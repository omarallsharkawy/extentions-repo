package eu.kanade.tachiyomi.animeextension.all.streamingcommunity

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class StreamingCommunityFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        StreamingCommunity("en", "movie", "StreamingUnity (Movie)"),
        StreamingCommunity("it", "movie", "StreamingUnity (Movie)"),
        StreamingCommunity("en", "tv", "StreamingUnity (Tv)"),
        StreamingCommunity("it", "tv", "StreamingUnity (Tv)"),
    )
}

package app.kultr.dl.core

import app.kultr.dl.core.links.LinkTarget.Kind
import app.kultr.dl.core.links.Links
import app.kultr.dl.core.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinksTest {
    private fun check(url: String, source: Source, kind: Kind, id: String?) {
        val t = Links.classify(url)
        assertEquals(source, t.source, url)
        assertEquals(kind, t.kind, url)
        assertEquals(id, t.id, url)
    }

    @Test
    fun youtube() {
        check("https://www.youtube.com/watch?v=abc123XYZ_-&t=10", Source.YOUTUBE, Kind.TRACK, "abc123XYZ_-")
        check("https://youtu.be/abc123XYZ_-?si=x", Source.YOUTUBE, Kind.TRACK, "abc123XYZ_-")
        check("https://music.youtube.com/watch?v=abc123XYZ_-&list=RD", Source.YOUTUBE_MUSIC, Kind.TRACK, "abc123XYZ_-")
        check("https://music.youtube.com/playlist?list=OLAK5uy_x", Source.YOUTUBE_MUSIC, Kind.PLAYLIST, "OLAK5uy_x")
        check("https://music.youtube.com/browse/MPREb_abc", Source.YOUTUBE_MUSIC, Kind.ALBUM, "MPREb_abc")
        check("https://www.youtube.com/shorts/short1", Source.YOUTUBE, Kind.TRACK, "short1")
    }

    @Test
    fun catalogues() {
        check("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=abc", Source.SPOTIFY, Kind.TRACK, "4uLU6hMCjMI75M1A2tKUQC")
        check("https://open.spotify.com/intl-de/album/1ATL5GLyefJaxhQzSPVrLX", Source.SPOTIFY, Kind.ALBUM, "1ATL5GLyefJaxhQzSPVrLX")
        check("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", Source.SPOTIFY, Kind.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M")
        check("https://music.apple.com/us/album/some-album/1440857781?i=1440857795", Source.APPLE_MUSIC, Kind.TRACK, "1440857795")
        check("https://music.apple.com/us/album/some-album/1440857781", Source.APPLE_MUSIC, Kind.ALBUM, "1440857781")
        check("https://music.apple.com/gb/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb", Source.APPLE_MUSIC, Kind.PLAYLIST, "pl.f4d106fed2bd41149aaacabb233eb5eb")
        check("https://www.deezer.com/de/track/3135556", Source.DEEZER, Kind.TRACK, "3135556")
        check("https://www.deezer.com/album/302127", Source.DEEZER, Kind.ALBUM, "302127")
        check("https://tidal.com/browse/track/77646169", Source.TIDAL, Kind.TRACK, "77646169")
        check("https://listen.tidal.com/album/77646168", Source.TIDAL, Kind.ALBUM, "77646168")
        check("https://open.qobuz.com/track/12345678", Source.QOBUZ, Kind.TRACK, "12345678")
        check("https://www.qobuz.com/us-en/album/some-album-some-artist/abcdef123", Source.QOBUZ, Kind.ALBUM, "abcdef123")
        check("https://music.amazon.com/albums/B0ABC?trackAsin=B0DEF", Source.AMAZON_MUSIC, Kind.TRACK, "B0DEF")
        check("https://music.amazon.de/albums/B0ABC", Source.AMAZON_MUSIC, Kind.ALBUM, "B0ABC")
        check("https://soundcloud.com/artist/some-track", Source.SOUNDCLOUD, Kind.TRACK, null)
        check("https://soundcloud.com/artist/sets/some-set", Source.SOUNDCLOUD, Kind.PLAYLIST, null)
        check("https://artist.bandcamp.com/album/record", Source.BANDCAMP, Kind.ALBUM, null)
        check("https://example.org/some/page", Source.WEB, Kind.UNKNOWN, null)
    }

    @Test
    fun findsLinksInSharedText() {
        assertEquals("https://open.spotify.com/track/abc", Links.find("Listen: https://open.spotify.com/track/abc."))
        assertTrue(Links.isShortLink("https://spotify.link/xyz"))
        assertTrue(Links.isLink("https://tidal.com/track/1"))
        assertTrue(!Links.isLink("daft punk"))
    }
}

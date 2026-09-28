package app.kultr.dl.core

import app.kultr.dl.core.links.YtDlpJson
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.LinkResult
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.sources.AppleMusic
import app.kultr.dl.core.sources.Bandcamp
import app.kultr.dl.core.sources.Deezer
import app.kultr.dl.core.sources.Odesli
import app.kultr.dl.core.sources.Spotify
import app.kultr.dl.core.sources.WebPage
import app.kultr.dl.core.sources.YouTube
import app.kultr.dl.core.sources.YouTubeMusic
import app.kultr.dl.core.util.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Parsers against small hand-written responses shaped like each service's. */
class ParsersTest {
    private fun run(text: String, pageType: String? = null) = buildString {
        append("{\"text\":\"").append(text).append("\"")
        if (pageType != null) {
            append(",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"X\",\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"")
            append(pageType).append("\"}}}}")
        }
        append("}")
    }

    private fun ytmRow(videoId: String, title: String, subtitleRuns: List<String>) = """
        {"musicResponsiveListItemRenderer":{
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/abc=w60-h60-l90-rj","width":60}]}}},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title","navigationEndpoint":{"watchEndpoint":{"videoId":"$videoId"}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${subtitleRuns.joinToString(",")}]}}}
          ],
          "playlistItemData":{"videoId":"$videoId"}
        }}
    """

    @Test
    fun youtubeMusicSongs() {
        val rows = listOf(
            ytmRow("vid00000001", "Paper Boats", listOf(run("Some Band", "MUSIC_PAGE_TYPE_ARTIST"), run(" • "), run("Harbour", "MUSIC_PAGE_TYPE_ALBUM"), run(" • "), run("3:34"))),
            ytmRow("vid00000002", "Glass Houses", listOf(run("Song"), run(" • "), run("Other Person"), run(" • "), run("1.2M plays"), run(" • "), run("4:01"))),
        )
        val json = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"contents":[${rows.joinToString(",")}]}}]}}}}]}}}"""
        val tracks = YouTubeMusic.parseTracks(parseJson(json), Source.YOUTUBE_MUSIC)
        assertEquals(2, tracks.size)
        val first = tracks[0]
        assertEquals("yt:vid00000001", first.id)
        assertEquals("Paper Boats", first.title)
        assertEquals("Some Band", first.artist)
        assertEquals("Harbour", first.album)
        assertEquals(214_000L, first.durationMs)
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", first.artworkUrl)
        assertEquals("https://music.youtube.com/watch?v=vid00000001", first.streamUrl)
        assertEquals("Other Person", tracks[1].artist)
        assertEquals(241_000L, tracks[1].durationMs)
    }

    @Test
    fun youtubeVideos() {
        val json = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
            {"videoRenderer":{"videoId":"vid00000003","title":{"runs":[{"text":"Some Band - Paper Boats (Official Video)"}]},
              "ownerText":{"runs":[{"text":"SomeBandVEVO"}]},"lengthText":{"simpleText":"3:36"},
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/vid00000003/hq720.jpg?sqp=x"}]}}}
        ]}}]}}}}}"""
        val tracks = YouTube.parse(parseJson(json))
        assertEquals(1, tracks.size)
        assertEquals("Some Band", tracks[0].artist)
        assertEquals("Paper Boats", tracks[0].title)
        assertEquals(216_000L, tracks[0].durationMs)
        assertEquals("https://i.ytimg.com/vi/vid00000003/hq720.jpg", tracks[0].artworkUrl)
    }

    @Test
    fun appleMusicSearchAndAlbum() {
        val json = """{"resultCount":2,"results":[
            {"wrapperType":"collection","collectionId":111,"collectionName":"Harbour","artistName":"Some Band","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/a/100x100bb.jpg","trackCount":10,"releaseDate":"2012-03-01T08:00:00Z","collectionViewUrl":"https://music.apple.com/us/album/harbour/111?uo=4"},
            {"wrapperType":"track","kind":"song","trackId":222,"trackName":"Paper Boats","artistName":"Some Band","collectionName":"Harbour","trackTimeMillis":214000,"artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/a/100x100bb.jpg","releaseDate":"2012-03-01T08:00:00Z","primaryGenreName":"Alternative","trackNumber":3,"discNumber":1,"trackExplicitness":"notExplicit","trackViewUrl":"https://music.apple.com/us/album/paper-boats/111?i=222&uo=4"}
        ]}"""
        val (tracks, albums) = AppleMusic.parseResults(parseJson(json))
        assertEquals("apple:222", tracks.single().id)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/a/600x600bb.jpg", tracks.single().artworkUrl)
        assertEquals(2012, tracks.single().year)
        assertEquals(3, tracks.single().trackNumber)
        assertEquals("apple:album:111", albums.single().id)
        assertEquals(CollectionKind.ALBUM, albums.single().kind)
        assertTrue(tracks.single().needsMatch)
    }

    @Test
    fun deezerAlbum() {
        val json = """{"id":302127,"title":"Harbour","cover_xl":"https://cdn/cover.jpg","release_date":"2012-03-01","nb_tracks":2,
            "artist":{"name":"Some Band"},"genres":{"data":[{"name":"Rock"}]},
            "tracks":{"data":[
              {"id":1,"type":"track","title":"Paper Boats","duration":214,"artist":{"name":"Some Band"},"link":"https://www.deezer.com/track/1"},
              {"id":2,"type":"track","title":"Glass Houses","duration":241,"artist":{"name":"Some Band"}}
            ]}}"""
        val album = assertNotNull(Deezer.parseAlbumPage(parseJson(json)))
        assertEquals(2, album.tracks.size)
        assertEquals("Harbour", album.tracks[0].album)
        assertEquals("https://cdn/cover.jpg", album.tracks[1].artworkUrl)
        assertEquals(2, album.tracks[1].trackNumber)
        assertEquals("Rock", album.tracks[0].genre)
        assertEquals(214_000L, album.tracks[0].durationMs)
    }

    @Test
    fun spotifyEmbed() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{
            "type":"album","name":"Harbour","uri":"spotify:album:AAA","subtitle":"Some Band",
            "coverArt":{"sources":[{"url":"https://i.scdn.co/small","width":64},{"url":"https://i.scdn.co/big","width":640}]},
            "releaseDate":{"isoString":"2012-03-01T00:00:00Z"},
            "trackList":[
              {"uri":"spotify:track:T1","title":"Paper Boats","subtitle":"Some Band","duration":214000},
              {"uri":"spotify:track:T2","title":"Glass Houses","subtitle":"Some Band, Guest","duration":241000}
            ]}}}}}}</script></html>"""
        val data = assertNotNull(Spotify.nextData(html))
        val album = assertNotNull(Spotify.parseEmbedList(data, "album", "AAA"))
        assertEquals("Harbour", album.title)
        assertEquals("https://i.scdn.co/big", album.artworkUrl)
        assertEquals("spotify:T2", album.tracks[1].id)
        assertEquals("Some Band, Guest", album.tracks[1].artist)
        assertEquals(2, album.tracks[1].trackNumber)
        assertEquals(2012, album.year)

        val trackHtml = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{
            "type":"track","name":"Paper Boats","uri":"spotify:track:T1","artists":[{"name":"Some Band"},{"name":"Guest"}],"duration":214000,
            "coverArt":{"sources":[{"url":"https://i.scdn.co/big","width":640}]}}}}}}}</script>"""
        val track = assertNotNull(Spotify.parseEmbedTrack(assertNotNull(Spotify.nextData(trackHtml)), "T1"))
        assertEquals("Some Band, Guest", track.artist)
        assertEquals(214_000L, track.durationMs)
    }

    @Test
    fun bandcampOdesliAndPages() {
        val bc = """{"auto":{"results":[
            {"type":"t","id":1,"name":"Paper Boats","band_name":"Some Band","album_name":"Harbour","img":"https://f4.bcbits.com/img/a1_3.jpg","item_url_path":"https://someband.bandcamp.com/track/paper-boats"},
            {"type":"a","id":2,"name":"Harbour","band_name":"Some Band","img":"https://f4.bcbits.com/img/a1_3.jpg","item_url_path":"https://someband.bandcamp.com/album/harbour"},
            {"type":"b","id":3,"name":"Some Band"}]}}"""
        val (tracks, albums) = Bandcamp.parse(parseJson(bc))
        assertEquals("https://someband.bandcamp.com/track/paper-boats", tracks.single().streamUrl)
        assertEquals("https://f4.bcbits.com/img/a1_10.jpg", tracks.single().artworkUrl)
        assertEquals("Harbour", albums.single().title)

        val od = """{"entityUniqueId":"TIDAL_SONG::1","entitiesByUniqueId":{"TIDAL_SONG::1":{"type":"song","title":"Paper Boats","artistName":"Some Band","thumbnailUrl":"https://t/img.jpg"}},
            "linksByPlatform":{"tidal":{"url":"https://tidal.com/track/1"},"youtubeMusic":{"url":"https://music.youtube.com/watch?v=vid00000001"}}}"""
        val entity = assertNotNull(Odesli.parse(parseJson(od)))
        assertEquals("Paper Boats", entity.title)
        assertEquals("https://music.youtube.com/watch?v=vid00000001", entity.youtubeUrl)

        val page = """<html><head><title>x</title>
            <meta property="og:title" content="Harbour - Some Band | Qobuz">
            <meta property="og:image" content="https://static.qobuz.com/cover.jpg">
            <script type="application/ld+json">{"@context":"https://schema.org","@type":"MusicAlbum","name":"Harbour","byArtist":{"@type":"MusicGroup","name":"Some Band"},
              "track":{"@type":"ItemList","itemListElement":[{"@type":"ListItem","item":{"@type":"MusicRecording","name":"Paper Boats","duration":"PT3M34S"}}]}}</script>
            </head></html>"""
        val meta = WebPage.parse(page)
        assertEquals("MusicAlbum", meta.schemaType)
        assertEquals("Some Band", meta.byArtist)
        assertEquals(214_000L, meta.recordings.single().durationMs)
        assertEquals("https://static.qobuz.com/cover.jpg", meta.image)
        assertEquals("Harbour" to "Some Band", WebPage.splitTitle("Harbour - Some Band | Qobuz", "Qobuz"))
        assertEquals("Harbour" to "Some Band", WebPage.splitTitle("Harbour by Some Band on TIDAL", "Tidal"))
    }

    @Test
    fun ytDlpOutput() {
        val single = """{"id":"vid00000001","title":"Paper Boats","track":"Paper Boats","artist":"Some Band","album":"Harbour","duration":214.0,
            "thumbnail":"https://i.ytimg.com/vi/x/maxres.jpg","webpage_url":"https://www.youtube.com/watch?v=vid00000001","extractor_key":"Youtube","release_year":2012}"""
        val track = (YtDlpJson.parse(single) as LinkResult.Single).track
        assertEquals("yt:vid00000001", track.id)
        assertEquals("Some Band", track.artist)
        assertEquals(2012, track.year)

        val list = """{"_type":"playlist","id":"sets1","title":"Late Set","uploader":"dj","webpage_url":"https://soundcloud.com/dj/sets/late-set",
            "entries":[{"_type":"url","ie_key":"Soundcloud","id":"1","url":"https://soundcloud.com/dj/one","title":"One","uploader":"dj","duration":300}]}"""
        val collection = (YtDlpJson.parse(list) as LinkResult.Many).collection
        assertEquals(Source.SOUNDCLOUD, collection.source)
        assertEquals("soundcloud:1", collection.tracks.single().id)
        assertEquals("https://soundcloud.com/dj/one", collection.tracks.single().streamUrl)
    }
}

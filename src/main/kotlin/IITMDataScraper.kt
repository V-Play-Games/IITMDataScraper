import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.vpg.vjson.value.JSONArray.Companion.toJSON
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File

const val iitmBaseUrl = "https://study.iitm.ac.in/ds"
const val ytVideoUrl = "https://www.youtube.com/watch?v="
val coursePagePattern = "course_pages/(.+).html".toRegex()
val ytDlpPath = System.getenv("YT_DLP_PATH")
val subtitleDownloadCommand = "$ytDlpPath --write-sub --write-auto-sub --sub-lang \"en\" --skip-download"
val playlistMetadataCommand = "$ytDlpPath --flat-playlist --print \"%(id)s\t%(title)s\""

fun main(): Unit = runBlocking {
    println("Connecting to $iitmBaseUrl...")
    val document = Jsoup.connect("$iitmBaseUrl/academics.html").get()
    val anchorLinks = document.getElementsByTag("a").map { it.attr("href") }
    val tableRowLinks = document.getElementsByClass("table-hover-row").map { it.attr("data-url") }
    listOf(anchorLinks, tableRowLinks)
        .flatten()
        .mapNotNull { coursePagePattern.matchEntire(it)?.groupValues[1] }
        .distinct()
        .executeScrapeTask("Scrape Course Pages") { code ->
            "$iitmBaseUrl/course_pages/$code.html" to "courses/$code/course.html"
        }
        .executeTask("Extract Course Details") { _, file -> parseCourseDetails(Jsoup.parse(file)) }
        .mapToResult()
        .also { courseList ->
            courseList.filter { it.playlist != null }
                .executeTask("Extract Lectures Details") { course -> getLectures(course) }
                .flattenTaskResults()
                .executeTask("Download Subtitles") { course, lecture -> downloadSubtitles(course, lecture) }
                .filterSuccessful()
        }
        .toJSON()
        .also { File("scrape-output/result.json").also { it.parentFile.mkdirs() }.writeText(it.toPrettyString()) }
}

fun parseCourseDetails(doc: Document): Course {
    val courseMetadata = doc.getElementsByClass("text-lighter mb-1")
        .map { it.text() }
        .map { it.split(": ") }
        .filter { it.size == 2 }
        .associate { it[0].trim() to it[1].trim() }
    val courseTitle = doc.getElementsByClass("h2 font-weight-600 text-dark").text()
    val playlist = doc.select("a")
        .map { it.attr("href") }
        .firstOrNull { it.startsWith("https://www.youtube.com") }
    val weeks = doc.getElementsByClass("table")
        .first()
        ?.let { parseTable(it) }
        ?.map { it.last() }
    return Course(
        name = courseTitle,
        courseCode = courseMetadata["Course ID"].toString(),
        credits = courseMetadata["Course Credits"]?.toInt() ?: 0,
        weeks = weeks,
        playlist = playlist,
    )
}

fun getLectures(course: Course): List<Lecture> {
    val playlistData = File(baseScrapeCacheDir, "courses/${course.courseCode}/playlist.txt")
    if (!playlistData.exists()) {
        val process = Runtime.getRuntime()
            .exec(
                "$playlistMetadataCommand ${course.playlist}"
                    .split(" ").toTypedArray(), null, null
            )
        var output = ""
        process.inputStream.bufferedReader().forEachLine {
            output += it + "\n"
        }
        val outputCode = process.waitFor()
        if (outputCode != 0)
            throw RuntimeException("Error downloading subtitles for $playlistData\n${output}")
        playlistData.writeText(output)
    }
    return playlistData.readLines()
        .map { it.split("\t") }
        .map { it[0] to it[1] }
        .map { (id, title) -> Lecture(title, "$ytVideoUrl$id") }
        .also { course.lectures.addAll(it) }
}

suspend fun downloadSubtitles(course: Course, lecture: Lecture) {
    val subtitleCacheDir = File(baseScrapeCacheDir, "courses/${course.courseCode}/lectures")
    val lectureId = lecture.url.substringAfter("=")
    val subtitleFile = File(subtitleCacheDir, "$lectureId.en.vtt")
    if (!subtitleFile.exists()) {
        val process = Runtime.getRuntime().exec(
            "$subtitleDownloadCommand -o \"${subtitleCacheDir.path}/%(id)s.%(ext)s\" ${lecture.url}"
                .split(" ").toTypedArray(), null, null
        )
        val outputCode = process.waitFor()
        if (outputCode != 0)
            throw RuntimeException(
                "Error downloading subtitles for ${course.name}/${lecture.name} (${lecture.url})\n" +
                        process.inputStream.readAllBytes().let { String(it) }
            )
        delay((Math.random() * 10_000).toLong())
    }
    val normalizedSubtitleFile = File(subtitleCacheDir, "$lectureId.txt")
    if (!normalizedSubtitleFile.exists()) {
        normalizedSubtitleFile.writeText(vttToText(subtitleFile.readText()))
    }
    lecture.transcript = normalizedSubtitleFile.readText()
}

fun vttToText(vttContent: String): String {
    return vttContent.lines()
        .filter { line ->
            // Skip VTT header, timestamps, and empty lines
            !line.startsWith("WEBVTT") &&
                    !line.startsWith("Language: ") &&
                    !line.startsWith("Kind: ") &&
                    !line.startsWith("NOTE") &&
                    !line.contains("-->") &&
                    !line.matches(Regex("\\d+")) &&
                    line.isNotBlank()
        }
        .joinToString(" ")
        .replace(Regex("<[^>]*>"), "") // Remove HTML tags
        .replace(Regex("\\s+"), " ") // Normalize whitespace
        .trim()
}

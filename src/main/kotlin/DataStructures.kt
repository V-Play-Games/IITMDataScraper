import net.vpg.vjson.value.JSONObject
import net.vpg.vjson.value.SerializableObject

data class Course(
    val name: String,
    val courseCode: String,
    val credits: Int,
    val weeks: List<String>? = null,
    val lectures: MutableList<Lecture> = mutableListOf(),
    val playlist: String?
) : SerializableObject {
    override fun toObject() = JSONObject()
        .put("name", name)
        .put("courseCode", courseCode)
        .put("credits", credits)
        .put("playlist", playlist)
        .put("weeks", weeks?.mapIndexed { i, content -> Week(this, i, content) })
        .put("lectures", lectures)
    fun addLecture(lecture: Lecture) = lectures.add(lecture)
}

data class Week(val course: Course, val weekNum: Int, val content: String) : SerializableObject {
    override fun toObject() = JSONObject().put("weekNum", weekNum).put("content", content)
}

class Lecture(
    val name: String,
    val url: String
) : SerializableObject {
    var transcript: String? = null

    override fun toObject() = JSONObject()
        .put("name", name)
        .put("url", url)
        .put("transcript", transcript)
}

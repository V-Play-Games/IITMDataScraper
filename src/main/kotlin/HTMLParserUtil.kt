import net.vpg.vjson.value.JSONArray.Companion.toJSON
import org.jsoup.nodes.Element

fun tableToJSON(table: Element, headers: List<String> = parseHeaders(table)) =
    parseTable(table, headers)
        .drop(1)
        .map { row -> headers.mapIndexed { i, header -> header to row[i] }.toMap() }
        .toJSON()
        .toArray()

fun parseTable(table: Element, headers: List<String> = parseHeaders(table)): List<List<String>> {
    val rowspanTrack = MutableList(headers.size) { 0 }
    val rowspanValues = MutableList<String?>(headers.size) { null }
    return table.select("tr").map { row ->
        // Prepare rowList, pre-fill with values from rowspans
        val rowList = MutableList<String?>(headers.size) { null }
        headers.indices.forEach { col ->
            if (rowspanTrack[col] > 0) {
                rowList[col] = rowspanValues[col]
                rowspanTrack[col]--
            }
        }

        var colIndex = 0
        row.select("td, th").forEach { cell ->
            // Find next available colIndex
            while (colIndex < headers.size && rowList[colIndex] != null) colIndex++
            val value = cell.text()
            val colspan = cell.attr("colspan").toIntOrNull() ?: 1
            val rowspan = cell.attr("rowspan").toIntOrNull() ?: 1
            for (c in 0 until colspan) {
                val targetCol = colIndex + c
                if (targetCol < headers.size) {
                    rowList[targetCol] = value
                    if (rowspan > 1) {
                        rowspanTrack[targetCol] = rowspan - 1
                        rowspanValues[targetCol] = value
                    }
                }
            }
            colIndex += colspan
        }
        return@map rowList.map { it ?: "" }
    }
}

fun parseHeaders(table: Element): List<String> {
    val rows = table.select("tr")
    val headers = rows.first()!!
        .select("th, td")
        .map { header ->
            header.attr("colspan")
                .takeIf { it.isNotEmpty() }
                ?.toInt()
                ?.takeIf { it < 4 }
                ?.let { List(it) { i -> "${header.text()}$i" } }
                ?: listOf(header.text())
        }
        .flatten()
    return headers
}

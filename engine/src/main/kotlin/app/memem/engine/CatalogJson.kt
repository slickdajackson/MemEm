package app.memem.engine

import org.json.JSONObject

data class CatalogEntry(
    val id: String,
    val name: String,
    val boxes: Int,
    val style: String,
    val meaningDe: String,
    val meaningEn: String,
    val examplesDe: List<List<String>>,
    val examplesEn: List<List<String>>,
) {
    val meaning: String get() = meaningDe.ifBlank { meaningEn }
    fun meaningFor(language: String): String =
        if (language == "de") meaningDe.ifBlank { meaningEn } else meaningEn.ifBlank { meaningDe }
    fun examplesFor(language: String): List<List<String>> = examplesForLanguage(examplesDe, examplesEn, language)
}

/** Reads the same catalog the app ships, plus the few-shot files when they are present. */
fun parseCatalog(catalogJson: String, examplesJson: String = "", examplesEnJson: String = ""): List<CatalogEntry> {
    val extra = readExampleMap(examplesJson)
    val english = readExampleMap(examplesEnJson)
    val list = JSONObject(catalogJson).getJSONArray("templates")
    return buildList {
        for (index in 0 until list.length()) {
            val obj = list.getJSONObject(index)
            val id = obj.getString("id")
            val fields = obj.optJSONArray("fields")
            val style = fields?.optJSONObject(0)?.optString("style")?.ifBlank { null } ?: "upper"
            val boxes = obj.optInt("boxes", fields?.length() ?: 1).coerceAtLeast(1)
            add(
                CatalogEntry(
                    id = id,
                    name = obj.optString("name", id),
                    boxes = boxes,
                    style = style,
                    meaningDe = obj.optString("meaningDe"),
                    meaningEn = obj.optString("meaningEn"),
                    examplesDe = (extra[id] ?: emptyList()).take(5),
                    examplesEn = (english[id] ?: emptyList()).take(3),
                ),
            )
        }
    }
}

private fun readExampleMap(json: String): Map<String, List<List<String>>> {
    if (json.isBlank()) return emptyMap()
    val root = try {
        JSONObject(json)
    } catch (_: Exception) {
        return emptyMap()
    }
    return buildMap {
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val groups = readExampleGroups(root.optJSONArray(id))
            if (groups.isNotEmpty()) put(id, groups.take(5))
        }
    }
}

private fun readExampleGroups(array: org.json.JSONArray?): List<List<String>> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val group = array.optJSONArray(index) ?: continue
            val lines = buildList {
                for (line in 0 until group.length()) add(group.optString(line))
            }
            if (lines.any { it.isNotBlank() }) add(lines)
        }
    }
}

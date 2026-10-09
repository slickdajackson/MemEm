package app.memem.engine

/**
 * Weighted reciprocal rank fusion, same rule as reference/memechat/src/memechat/search.py `fuse`.
 * A template's rank in a kind is the rank of its best point. Later points of the same template
 * in that kind are ignored and do not consume a rank.
 */
enum class Kind { TEXT, IMAGE, IMAGE_TEXT }

data class ScoredPoint(
    val templateId: String,
    val score: Double,
    val lines: List<String> = emptyList(),
)

data class KindHit(
    val rank: Int,
    val score: Double,
    val lines: List<String>,
)

data class TemplateHit(
    val templateId: String,
    var score: Double = 0.0,
    val kinds: MutableMap<Kind, KindHit> = linkedMapOf(),
)

fun fuse(
    perKind: Map<Kind, List<ScoredPoint>>,
    weights: Map<Kind, Double>,
    k: Int = 60,
): List<TemplateHit> {
    val hits = linkedMapOf<String, TemplateHit>()
    for ((kind, points) in perKind) {
        var rank = 0
        val weight = weights[kind] ?: 0.0
        for (point in points) {
            val hit = hits.getOrPut(point.templateId) { TemplateHit(point.templateId) }
            if (kind in hit.kinds) continue
            rank += 1
            hit.kinds[kind] = KindHit(rank, point.score, point.lines)
            hit.score += weight / (k + rank)
        }
    }
    return hits.values.sortedByDescending { it.score }
}

fun selectTemplates(
    hits: List<TemplateHit>,
    boxes: Map<String, Int>,
    limit: Int = 8,
    maxBoxes: Int = 4,
): List<TemplateHit> {
    val fresh = hits.filter { (boxes[it.templateId] ?: 99) <= maxBoxes }
    return (fresh.ifEmpty { hits }).take(limit)
}

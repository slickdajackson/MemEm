package app.memem.engine

data class IndexPoint(
    val templateId: String,
    val kind: Kind,
    val lines: List<String>,
)

class MemIndex(
    val dim: Int,
    val space: String,
    val queryPrefix: String,
    val points: List<IndexPoint>,
    val vectors: FloatArray,
) {
    init {
        require(vectors.size == points.size * dim) {
            "vector length ${vectors.size} != ${points.size} * $dim"
        }
    }
}

private val LIMITS = mapOf(Kind.IMAGE to 100, Kind.TEXT to 300, Kind.IMAGE_TEXT to 300)
private val WEIGHTS = mapOf(Kind.TEXT to 1.0, Kind.IMAGE to 0.5, Kind.IMAGE_TEXT to 1.0)

fun dotRow(vectors: FloatArray, dim: Int, row: Int, query: FloatArray): Float {
    var sum = 0f
    var j = row * dim
    val end = j + dim
    var q = 0
    while (j + 7 < end) {
        sum += vectors[j] * query[q] +
            vectors[j + 1] * query[q + 1] +
            vectors[j + 2] * query[q + 2] +
            vectors[j + 3] * query[q + 3] +
            vectors[j + 4] * query[q + 4] +
            vectors[j + 5] * query[q + 5] +
            vectors[j + 6] * query[q + 6] +
            vectors[j + 7] * query[q + 7]
        j += 8
        q += 8
    }
    while (j < end) {
        sum += vectors[j] * query[q]
        j += 1
        q += 1
    }
    return sum
}

fun searchTemplates(
    index: MemIndex,
    query: FloatArray,
    boxes: Map<String, Int>,
    limit: Int = 8,
): List<TemplateHit> {
    require(query.size == index.dim)
    val best = HashMap<Kind, ArrayList<ScoredPoint>>(3)
    for (kind in LIMITS.keys) best[kind] = ArrayList(LIMITS.getValue(kind))
    val heaps = LIMITS.mapValues { (_, cap) -> TopK(cap) }
    for (i in index.points.indices) {
        val point = index.points[i]
        val heap = heaps[point.kind] ?: continue
        heap.offer(dotRow(index.vectors, index.dim, i, query), i)
    }
    val perKind = linkedMapOf<Kind, List<ScoredPoint>>()
    for (kind in listOf(Kind.TEXT, Kind.IMAGE, Kind.IMAGE_TEXT)) {
        val ranked = heaps.getValue(kind).sorted()
        perKind[kind] = ranked.map { (score, idx) ->
            val point = index.points[idx]
            ScoredPoint(point.templateId, score.toDouble(), point.lines)
        }
    }
    return selectTemplates(fuse(perKind, WEIGHTS), boxes, limit)
}

private class TopK(private val cap: Int) {
    private val data = ArrayList<Pair<Float, Int>>(cap)

    fun offer(score: Float, index: Int) {
        if (data.size < cap) {
            data.add(score to index)
            if (data.size == cap) data.sortBy { it.first }
            return
        }
        if (score <= data[0].first) return
        data[0] = score to index
        var i = 0
        while (true) {
            val left = i * 2 + 1
            val right = left + 1
            var smallest = i
            if (left < data.size && data[left].first < data[smallest].first) smallest = left
            if (right < data.size && data[right].first < data[smallest].first) smallest = right
            if (smallest == i) break
            val tmp = data[i]
            data[i] = data[smallest]
            data[smallest] = tmp
            i = smallest
        }
    }

    fun sorted(): List<Pair<Float, Int>> = data.sortedByDescending { it.first }
}

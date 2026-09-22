package com.lumina.studio.core.library

/**
 * M13 assisted-culling math (§44, pure JVM, no android.*).
 *
 * Honest scope: these are cheap heuristics over thumbnail-size pixels, NOT
 * photo analysis. Scores flag *candidates* (blurry / over / under /
 * duplicate); the UI only ever shows badges + checkboxes and the user
 * decides — nothing here can delete anything.
 *
 * Conventions: luma is 0..255 (Rec.601). [averageHash] is a 64-bit aHash
 * over an 8x8 box-resampled luma field. [laplacianVariance] is the
 * variance of the 4-neighbour Laplacian response; near-zero means no
 * high-frequency content (out of focus or perfectly smooth).
 */
data class ClipFractions(val highlight: Float, val shadow: Float)

object CullThresholds {
    /** Laplacian variance below this reads as blurry (luma 0..255 scale). */
    const val BLUR_VARIANCE_MAX = 100f

    /** Fraction of near-white pixels that reads as overexposed. */
    const val HIGHLIGHT_CLIP_MIN = 0.05f

    /** Fraction of near-black pixels that reads as underexposed. */
    const val SHADOW_CLIP_MIN = 0.40f

    /** aHash Hamming distance at or below this reads as a duplicate pair. */
    const val DUPLICATE_HAMMING_MAX = 5

    const val AHASH_DIM = 8
    const val HIGHLIGHT_LUMA = 250f
    const val SHADOW_LUMA = 5f
}

object CullSignals {
    fun lumaOf(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /**
     * 64-bit average hash. Bit i is 1 when cell i is at/above the mean
     * luma. Null on empty or size-mismatched input.
     */
    fun averageHash(argb: IntArray, width: Int, height: Int): Long? {
        if (width <= 0 || height <= 0 || argb.size != width * height) return null
        val dim = CullThresholds.AHASH_DIM
        val cells = FloatArray(dim * dim)
        for (cy in 0 until dim) {
            val y0 = (cy * height) / dim
            val y1 = ((cy + 1) * height) / dim
            for (cx in 0 until dim) {
                val x0 = (cx * width) / dim
                val x1 = ((cx + 1) * width) / dim
                var sum = 0f
                var n = 0
                for (y in y0 until y1.coerceAtLeast(y0 + 1)) {
                    if (y >= height) break
                    for (x in x0 until x1.coerceAtLeast(x0 + 1)) {
                        if (x >= width) break
                        sum += lumaOf(argb[y * width + x])
                        n++
                    }
                }
                cells[cy * dim + cx] = if (n > 0) sum / n else 0f
            }
        }
        var mean = 0f
        for (v in cells) mean += v
        mean /= cells.size.toFloat()
        var hash = 0L
        for (i in cells.indices) {
            if (cells[i] >= mean) hash = hash or (1L shl i)
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = (a xor b).countOneBits()

    /**
     * Variance of the 4-neighbour Laplacian (4c - l - r - u - d) over the
     * interior pixels. Null when the frame is smaller than 3x3 or
     * size-mismatched. A perfectly flat frame scores exactly 0.
     */
    fun laplacianVariance(gray: FloatArray, width: Int, height: Int): Float? {
        if (width < 3 || height < 3 || gray.size != width * height) return null
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val c = gray[y * width + x].toDouble()
                val response = 4.0 * c -
                    gray[y * width + x - 1] -
                    gray[y * width + x + 1] -
                    gray[(y - 1) * width + x] -
                    gray[(y + 1) * width + x]
                sum += response
                sumSq += response * response
                n++
            }
        }
        if (n <= 0) return null
        val mean = sum / n
        return (sumSq / n - mean * mean).coerceAtLeast(0.0).toFloat()
    }

    fun grayField(argb: IntArray, width: Int, height: Int): FloatArray? {
        if (width <= 0 || height <= 0 || argb.size != width * height) return null
        return FloatArray(argb.size) { lumaOf(argb[it]) }
    }

    fun clipping(argb: IntArray): ClipFractions {
        if (argb.isEmpty()) return ClipFractions(0f, 0f)
        var hi = 0
        var lo = 0
        for (p in argb) {
            val luma = lumaOf(p)
            if (luma >= CullThresholds.HIGHLIGHT_LUMA) hi++
            if (luma <= CullThresholds.SHADOW_LUMA) lo++
        }
        val n = argb.size.toFloat()
        return ClipFractions(hi / n, lo / n)
    }
}

/** Base flags for one photo; duplicates are assigned over the corpus. */
data class CullFlags(
    val blurry: Boolean = false,
    val overexposed: Boolean = false,
    val underexposed: Boolean = false,
    val duplicateOf: String? = null
) {
    val hasAny: Boolean get() = blurry || overexposed || underexposed || duplicateOf != null

    fun badges(duplicateName: String? = null): List<String> {
        val out = ArrayList<String>(4)
        if (blurry) out.add("Blurry")
        if (overexposed) out.add("Overexposed")
        if (underexposed) out.add("Underexposed")
        if (duplicateOf != null) {
            out.add(if (duplicateName.isNullOrBlank()) "Duplicate" else "Duplicate of $duplicateName")
        }
        return out
    }
}

/** Cached per-project cull score; duplicateOf is assigned live, not stored. */
data class CullScore(
    val projectId: String,
    val sourceKey: String,
    val hash: Long,
    val blurVariance: Float,
    val highlightClip: Float,
    val shadowClip: Float,
    val flags: CullFlags
)

object CullScoring {
    fun flagsFor(blurVariance: Float, clip: ClipFractions): CullFlags = CullFlags(
        blurry = blurVariance.isFinite() && blurVariance <= CullThresholds.BLUR_VARIANCE_MAX,
        overexposed = clip.highlight >= CullThresholds.HIGHLIGHT_CLIP_MIN,
        underexposed = clip.shadow >= CullThresholds.SHADOW_CLIP_MIN
    )

    fun scoreFrame(argb: IntArray, width: Int, height: Int): CullFlags? {
        val gray = CullSignals.grayField(argb, width, height) ?: return null
        val blur = CullSignals.laplacianVariance(gray, width, height) ?: return null
        return flagsFor(blur, CullSignals.clipping(argb))
    }

    /**
     * Deterministic duplicate assignment over [orderedIds]: each id maps
     * to the earliest earlier id whose hash is within
     * [CullThresholds.DUPLICATE_HAMMING_MAX]. Ids without a hash are skipped.
     */
    fun assignDuplicates(
        orderedIds: List<String>,
        hashes: Map<String, Long>
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val seen = ArrayList<Pair<String, Long>>()
        for (id in orderedIds) {
            val hash = hashes[id] ?: continue
            var match: String? = null
            for ((priorId, priorHash) in seen) {
                if (CullSignals.hammingDistance(hash, priorHash) <=
                    CullThresholds.DUPLICATE_HAMMING_MAX
                ) {
                    match = priorId
                    break
                }
            }
            if (match != null) result[id] = match
            seen.add(id to hash)
        }
        return result
    }
}

object CullScoreJson {
    private const val VERSION = 1

    fun encode(score: CullScore): String {
        val out = StringBuilder()
        out.append("{\"v\":").append(VERSION)
            .append(",\"id\":").append(MiniJson.quoted(score.projectId))
            .append(",\"key\":").append(MiniJson.quoted(score.sourceKey))
            .append(",\"hash\":").append(score.hash)
            .append(",\"blur\":").append(score.blurVariance)
            .append(",\"hi\":").append(score.highlightClip)
            .append(",\"sh\":").append(score.shadowClip)
            .append(",\"b\":").append(if (score.flags.blurry) 1 else 0)
            .append(",\"o\":").append(if (score.flags.overexposed) 1 else 0)
            .append(",\"u\":").append(if (score.flags.underexposed) 1 else 0)
        return out.append('}').toString()
    }

    /** Null on corrupt input — caller recomputes. Duplicate links are live. */
    fun decode(json: String): CullScore? {
        return try {
            decodeOrThrow(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeOrThrow(json: String): CullScore? {
        val root = MiniJson.parse(json) as? JsonVal.Obj ?: return null
        val id = root.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val key = root.string("key") ?: return null
        val hash = root.long("hash") ?: return null
        val blur = root.float("blur")?.takeIf { it.isFinite() } ?: return null
        val hi = root.float("hi")?.coerceIn(0f, 1f) ?: return null
        val sh = root.float("sh")?.coerceIn(0f, 1f) ?: return null
        val flags = CullFlags(
            blurry = root.long("b") == 1L,
            overexposed = root.long("o") == 1L,
            underexposed = root.long("u") == 1L
        )
        return CullScore(id, key, hash, blur, hi, sh, flags)
    }
}

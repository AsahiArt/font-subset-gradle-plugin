package asahiart.fontsubset.ttf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a subset font back without using [TtfSubsetter], so tests can check
 * cmap coverage and outline bytes independently of the writer.
 */
internal class ParsedFont(
    bytes: ByteArray,
) {
    val tables: Map<String, ByteArray>
    val cmapPlatform: Int
    val cmapEncoding: Int
    val cmap: Map<Int, Int>
    val glyphs: List<ByteArray>
    val charStrings: List<ByteArray>

    val post: ByteArray?
        get() = tables["post"]

    val postVersion: Int?
        get() =
            post?.takeIf { it.size >= 4 }?.let { table ->
                ByteBuffer.wrap(table).order(ByteOrder.BIG_ENDIAN).int
            }

    init {
        tables = readTables(bytes)
        val cmapTable = tables["cmap"] ?: error("subset font has no cmap")
        val cmapInfo = readCmap(cmapTable)
        cmapPlatform = cmapInfo.platformId
        cmapEncoding = cmapInfo.encodingId
        cmap = cmapInfo.mapping
        glyphs = readGlyphs(tables)
        charStrings = tables["CFF "]?.let { readCharStrings(it) } ?: emptyList()
    }

    private data class CmapInfo(
        val platformId: Int,
        val encodingId: Int,
        val mapping: Map<Int, Int>,
    )

    private fun readTables(bytes: ByteArray): Map<String, ByteArray> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val numTables = buf.getShort(4).toInt() and 0xFFFF
        val tables = LinkedHashMap<String, ByteArray>(numTables)
        val tag = ByteArray(4)
        for (i in 0 until numTables) {
            val base = 12 + i * 16
            buf.position(base)
            buf.get(tag)
            buf.int
            val offset = buf.int
            val length = buf.int
            tables[String(tag, Charsets.US_ASCII)] = bytes.copyOfRange(offset, offset + length)
        }
        return tables
    }

    private fun readCmap(cmap: ByteArray): CmapInfo {
        val buf = ByteBuffer.wrap(cmap).order(ByteOrder.BIG_ENDIAN)
        buf.short
        val numSubtables = buf.short.toInt() and 0xFFFF
        check(numSubtables >= 1) { "cmap has no subtables" }
        val platformId = buf.short.toInt() and 0xFFFF
        val encodingId = buf.short.toInt() and 0xFFFF
        val offset = buf.int
        buf.position(offset)
        val format = buf.short.toInt() and 0xFFFF
        val mapping =
            when (format) {
                4 -> readCmapFormat4(buf, offset)
                12 -> readCmapFormat12(buf, offset)
                else -> error("unsupported cmap format $format")
            }
        return CmapInfo(platformId, encodingId, mapping)
    }

    private fun readCmapFormat4(
        buf: ByteBuffer,
        offset: Int,
    ): Map<Int, Int> {
        buf.position(offset + 6)
        val segCount = (buf.short.toInt() and 0xFFFF) / 2
        buf.position(buf.position() + 6)
        val endCounts = IntArray(segCount) { buf.short.toInt() and 0xFFFF }
        buf.short
        val startCounts = IntArray(segCount) { buf.short.toInt() and 0xFFFF }
        val idDeltas = IntArray(segCount) { buf.short.toInt() }
        val idRangeOffsetBase = buf.position()
        val idRangeOffsets = IntArray(segCount) { buf.short.toInt() and 0xFFFF }
        val mapping = LinkedHashMap<Int, Int>()
        for (seg in 0 until segCount) {
            if (endCounts[seg] == 0xFFFF) break
            for (codePoint in startCounts[seg]..endCounts[seg]) {
                val glyphId =
                    if (idRangeOffsets[seg] == 0) {
                        (codePoint + idDeltas[seg]) and 0xFFFF
                    } else {
                        val rangeAddr = idRangeOffsetBase + seg * 2
                        val glyphAddr = rangeAddr + idRangeOffsets[seg] + (codePoint - startCounts[seg]) * 2
                        val raw = buf.getShort(glyphAddr).toInt() and 0xFFFF
                        if (raw == 0) 0 else (raw + idDeltas[seg]) and 0xFFFF
                    }
                if (glyphId != 0) mapping[codePoint] = glyphId
            }
        }
        return mapping
    }

    private fun readCmapFormat12(
        buf: ByteBuffer,
        offset: Int,
    ): Map<Int, Int> {
        buf.position(offset + 12)
        val numGroups = buf.int
        val mapping = LinkedHashMap<Int, Int>()
        repeat(numGroups) {
            val start = buf.int
            val end = buf.int
            val glyph = buf.int
            for (codePoint in start..end) {
                mapping[codePoint] = glyph + (codePoint - start)
            }
        }
        return mapping
    }

    private fun readGlyphs(tables: Map<String, ByteArray>): List<ByteArray> {
        val head = tables["head"] ?: return emptyList()
        val loca = tables["loca"] ?: return emptyList()
        val glyf = tables["glyf"] ?: return emptyList()
        val maxp = tables["maxp"] ?: return emptyList()
        val numGlyphs =
            ByteBuffer
                .wrap(maxp)
                .order(ByteOrder.BIG_ENDIAN)
                .getShort(4)
                .toInt() and 0xFFFF
        val locaFormat =
            ByteBuffer
                .wrap(head)
                .order(ByteOrder.BIG_ENDIAN)
                .getShort(50)
                .toInt()
        val locaBuf = ByteBuffer.wrap(loca).order(ByteOrder.BIG_ENDIAN)
        val offsets =
            IntArray(numGlyphs + 1) {
                if (locaFormat == 0) (locaBuf.short.toInt() and 0xFFFF) * 2 else locaBuf.int
            }
        return List(numGlyphs) { index ->
            glyf.copyOfRange(offsets[index], offsets[index + 1])
        }
    }

    private fun readCharStrings(cff: ByteArray): List<ByteArray> {
        val buf = ByteBuffer.wrap(cff).order(ByteOrder.BIG_ENDIAN)
        val headerSize = cff[2].toInt() and 0xFF
        var pos = headerSize
        pos = readIndex(buf, pos).end
        val topDict = readIndex(buf, pos).objects.single()
        val charStringsAt = dictOperand(topDict, CHAR_STRINGS_OP)
        return readIndex(buf, charStringsAt).objects
    }

    private class CffIndex(
        val objects: List<ByteArray>,
        val end: Int,
    )

    private fun readIndex(
        buf: ByteBuffer,
        start: Int,
    ): CffIndex {
        buf.position(start)
        val count = buf.short.toInt() and 0xFFFF
        if (count == 0) return CffIndex(emptyList(), start + 2)
        val offSize = buf.get().toInt() and 0xFF
        val offsets =
            IntArray(count + 1) {
                when (offSize) {
                    1 -> buf.get().toInt() and 0xFF
                    2 -> buf.short.toInt() and 0xFFFF
                    else -> error("unsupported CFF offSize $offSize")
                }
            }
        val base = buf.position()
        val objects =
            List(count) { index ->
                val from = base + offsets[index] - 1
                val to = base + offsets[index + 1] - 1
                ByteArray(to - from).also { data ->
                    buf.position(from)
                    buf.get(data)
                }
            }
        return CffIndex(objects, base + offsets[count] - 1)
    }

    private fun dictOperand(
        dict: ByteArray,
        operator: Int,
    ): Int {
        var index = 0
        var last = 0
        while (index < dict.size) {
            val byte = dict[index].toInt() and 0xFF
            when {
                byte == 29 -> {
                    last = ByteBuffer.wrap(dict, index + 1, 4).order(ByteOrder.BIG_ENDIAN).int
                    index += 5
                }
                byte == 28 -> {
                    last =
                        ByteBuffer
                            .wrap(dict, index + 1, 2)
                            .order(ByteOrder.BIG_ENDIAN)
                            .short
                            .toInt()
                    index += 3
                }
                byte in 32..246 -> {
                    last = byte - 139
                    index++
                }
                byte == operator -> return last
                byte in 0..27 -> index++
                else -> error("unsupported CFF dict byte $byte")
            }
        }
        error("missing CFF operator $operator")
    }

    private companion object {
        const val CHAR_STRINGS_OP: Int = 17
    }
}

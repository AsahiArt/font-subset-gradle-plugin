package asahiart.fontsubset.ttf

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tiny hand-built TTF/OTF fixtures. Kept in source so tests do not commit font binaries.
 *
 * The TrueType font has five glyphs:
 * 0 empty .notdef, 1 a simple outline with no cmap entry, 2 a simple component,
 * 3 a composite of glyph 2 mapped to U+0043, 4 a simple outline mapped to U+4E2D.
 * Horizontal metrics use a short hmtx tail (numberOfHMetrics = 3).
 */
internal object SyntheticFont {
    enum class CmapKind { FORMAT_4, FORMAT_12 }

    enum class PostKind { NAMES, FORMAT_3, ABSENT, TRUNCATED }

    const val ITALIC_ANGLE: Int = -5 * 65536
    const val UNDERLINE_POSITION: Int = -75
    const val UNDERLINE_THICKNESS: Int = 40
    const val IS_FIXED_PITCH: Int = 0
    const val MIN_MEM_TYPE42: Int = 11
    const val MAX_MEM_TYPE42: Int = 22
    const val MIN_MEM_TYPE1: Int = 33
    const val MAX_MEM_TYPE1: Int = 44

    val ttfCodePoints: Set<Int> = setOf(0x43, 0x4E2D, 0x5A)
    val otfCodePoints: Set<Int> = setOf(0x41, 0x5A)

    fun trueType(
        cmapKind: CmapKind,
        postKind: PostKind,
    ): ByteArray {
        val glyphs = sourceGlyphs()
        val tables =
            linkedMapOf(
                "cmap" to cmap(cmapKind, mapOf(0x43 to 3, 0x4E2D to 4)),
                "glyf" to concat(glyphs),
                "head" to head(indexToLocFormat = 0, xMin = 0, yMin = -3, xMax = 450, yMax = 700),
                "hhea" to hhea(numberOfHMetrics = 3, advanceWidthMax = 600),
                "hmtx" to
                    hmtx(
                        advances = intArrayOf(600, 500, 400),
                        lsbs = intArrayOf(1, 2, 3),
                        extraLsbs = intArrayOf(4, 5),
                    ),
                "loca" to shortLoca(glyphs),
                "maxp" to maxpTrueType(glyphs.size),
                "name" to nameTable(),
            )
        postBytes(postKind, glyphs.size)?.let { tables["post"] = it }
        return assemble(0x00010000, tables)
    }

    fun openType(postKind: PostKind): ByteArray {
        val tables =
            linkedMapOf(
                "CFF " to cff(),
                "cmap" to cmap(CmapKind.FORMAT_4, mapOf(0x41 to 1)),
                "head" to head(indexToLocFormat = 0, xMin = 0, yMin = 0, xMax = 500, yMax = 700),
                "hhea" to hhea(numberOfHMetrics = 2, advanceWidthMax = 500),
                "hmtx" to
                    hmtx(
                        advances = intArrayOf(500, 320),
                        lsbs = intArrayOf(10, 15),
                        extraLsbs = intArrayOf(),
                    ),
                "maxp" to maxpCff(2),
                "name" to nameTable(),
            )
        postBytes(postKind, glyphCount = 2)?.let { tables["post"] = it }
        return assemble(SFVERSION_OTTO, tables)
    }

    fun sourceGlyphs(): List<ByteArray> =
        listOf(
            ByteArray(0),
            simpleGlyph(listOf(50 to 0, 450 to 0, 450 to 700, 50 to 700)),
            simpleGlyph(listOf(0 to 0, 200 to 0, 100 to 300, 0 to 0)),
            compositeOfPart(),
            simpleGlyph(listOf(10 to 10, 300 to 10, 300 to 400, 10 to 400)),
        )

    fun otfCharStrings(): List<ByteArray> =
        listOf(
            byteArrayOf(14),
            byteArrayOf(1, 2, 14),
        )

    fun nameTable(): ByteArray = byteArrayOf(0, 0, 0, 0, 0, 6)

    private fun postBytes(
        kind: PostKind,
        glyphCount: Int,
    ): ByteArray? =
        when (kind) {
            PostKind.ABSENT -> null
            PostKind.FORMAT_3 -> postHeader(POST_VERSION_3)
            PostKind.TRUNCATED -> postHeader(POST_VERSION_2).copyOf(16)
            PostKind.NAMES -> postWithNames(glyphCount)
        }

    private fun postHeader(version: Int): ByteArray {
        val buf = ByteBuffer.allocate(POST_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(version)
        buf.putInt(ITALIC_ANGLE)
        buf.putShort(UNDERLINE_POSITION.toShort())
        buf.putShort(UNDERLINE_THICKNESS.toShort())
        buf.putInt(IS_FIXED_PITCH)
        buf.putInt(MIN_MEM_TYPE42)
        buf.putInt(MAX_MEM_TYPE42)
        buf.putInt(MIN_MEM_TYPE1)
        buf.putInt(MAX_MEM_TYPE1)
        return buf.array()
    }

    private fun postWithNames(glyphCount: Int): ByteArray {
        val names = listOf(".notdef", "Arect", "part", "Ccomp", "zhong").take(glyphCount)
        val custom = ArrayList<String>()
        val nameIds = IntArray(names.size)
        for (i in names.indices) {
            if (names[i] == ".notdef") {
                nameIds[i] = 0
            } else {
                nameIds[i] = MAC_GLYPH_COUNT + custom.size
                custom.add(names[i])
            }
        }
        val strings = ByteArrayOutputStream()
        for (name in custom) {
            val raw = name.toByteArray(Charsets.US_ASCII)
            strings.write(raw.size)
            strings.write(raw)
        }
        val stringBytes = strings.toByteArray()
        val buf =
            ByteBuffer
                .allocate(POST_HEADER_SIZE + 2 + nameIds.size * 2 + stringBytes.size)
                .order(ByteOrder.BIG_ENDIAN)
        buf.put(postHeader(POST_VERSION_2))
        buf.putShort(nameIds.size.toShort())
        for (id in nameIds) buf.putShort(id.toShort())
        buf.put(stringBytes)
        return buf.array()
    }

    private fun simpleGlyph(points: List<Pair<Int, Int>>): ByteArray {
        val count = points.size
        val x = IntArray(count)
        val y = IntArray(count)
        var prevX = 0
        var prevY = 0
        for (i in points.indices) {
            x[i] = points[i].first - prevX
            y[i] = points[i].second - prevY
            prevX = points[i].first
            prevY = points[i].second
        }
        val capacity = 14 + count * 5
        val buf = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(1)
        buf.putShort(points.minOf { it.first }.toShort())
        buf.putShort(points.minOf { it.second }.toShort())
        buf.putShort(points.maxOf { it.first }.toShort())
        buf.putShort(points.maxOf { it.second }.toShort())
        buf.putShort((count - 1).toShort())
        buf.putShort(0)
        repeat(count) { buf.put(1) }
        for (delta in x) buf.putShort(delta.toShort())
        for (delta in y) buf.putShort(delta.toShort())
        check(buf.position() == capacity)
        return buf.array()
    }

    private fun compositeOfPart(): ByteArray {
        val buf = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(-1)
        buf.putShort(5)
        buf.putShort(-3)
        buf.putShort(205)
        buf.putShort(297)
        buf.putShort(0x0003)
        buf.putShort(2)
        buf.putShort(5)
        buf.putShort(-3)
        return buf.array()
    }

    private fun cmap(
        kind: CmapKind,
        mapping: Map<Int, Int>,
    ): ByteArray =
        when (kind) {
            CmapKind.FORMAT_4 -> cmapFormat4(mapping)
            CmapKind.FORMAT_12 -> cmapFormat12(mapping)
        }

    private fun cmapFormat4(mapping: Map<Int, Int>): ByteArray {
        val segments = mapping.entries.sortedBy { it.key }
        val segCount = segments.size + 1
        val subtableSize = 16 + segCount * 8
        val buf = ByteBuffer.allocate(12 + subtableSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0)
        buf.putShort(1)
        buf.putShort(3)
        buf.putShort(1)
        buf.putInt(12)
        buf.putShort(4)
        buf.putShort(subtableSize.toShort())
        buf.putShort(0)
        buf.putShort((segCount * 2).toShort())
        val searchRange = 2 * floorPow2(segCount)
        buf.putShort(searchRange.toShort())
        buf.putShort(log2(floorPow2(segCount)).toShort())
        buf.putShort((segCount * 2 - searchRange).toShort())
        for (entry in segments) buf.putShort(entry.key.toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0)
        for (entry in segments) buf.putShort(entry.key.toShort())
        buf.putShort(0xFFFF.toShort())
        for (entry in segments) buf.putShort((entry.value - entry.key).toShort())
        buf.putShort(1)
        repeat(segCount) { buf.putShort(0) }
        return buf.array()
    }

    private fun cmapFormat12(mapping: Map<Int, Int>): ByteArray {
        val groups = mapping.entries.sortedBy { it.key }
        val subtableSize = 16 + groups.size * 12
        val buf = ByteBuffer.allocate(12 + subtableSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0)
        buf.putShort(1)
        buf.putShort(3)
        buf.putShort(10)
        buf.putInt(12)
        buf.putShort(12)
        buf.putShort(0)
        buf.putInt(subtableSize)
        buf.putInt(0)
        buf.putInt(groups.size)
        for (entry in groups) {
            buf.putInt(entry.key)
            buf.putInt(entry.key)
            buf.putInt(entry.value)
        }
        return buf.array()
    }

    private fun head(
        indexToLocFormat: Int,
        xMin: Int,
        yMin: Int,
        xMax: Int,
        yMax: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(54).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(1)
        buf.putShort(0)
        buf.putInt(0x00010000)
        buf.putInt(0)
        buf.putInt(0x5F0F3CF5)
        buf.putShort(0)
        buf.putShort(1000)
        buf.putLong(0)
        buf.putLong(0)
        buf.putShort(xMin.toShort())
        buf.putShort(yMin.toShort())
        buf.putShort(xMax.toShort())
        buf.putShort(yMax.toShort())
        buf.putShort(0)
        buf.putShort(8)
        buf.putShort(2)
        buf.putShort(indexToLocFormat.toShort())
        buf.putShort(0)
        return buf.array()
    }

    private fun hhea(
        numberOfHMetrics: Int,
        advanceWidthMax: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(1)
        buf.putShort(0)
        buf.putShort(800)
        buf.putShort(-200)
        buf.putShort(0)
        buf.putShort(advanceWidthMax.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(advanceWidthMax.toShort())
        buf.putShort(1)
        buf.putShort(0)
        buf.putShort(0)
        repeat(4) { buf.putShort(0) }
        buf.putShort(0)
        buf.putShort(numberOfHMetrics.toShort())
        return buf.array()
    }

    private fun maxpTrueType(numGlyphs: Int): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00010000)
        buf.putShort(numGlyphs.toShort())
        buf.putShort(4)
        buf.putShort(1)
        buf.putShort(4)
        buf.putShort(1)
        buf.putShort(1)
        repeat(6) { buf.putShort(0) }
        buf.putShort(1)
        buf.putShort(1)
        return buf.array()
    }

    private fun maxpCff(numGlyphs: Int): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00005000)
        buf.putShort(numGlyphs.toShort())
        return buf.array()
    }

    private fun hmtx(
        advances: IntArray,
        lsbs: IntArray,
        extraLsbs: IntArray,
    ): ByteArray {
        val buf = ByteBuffer.allocate(advances.size * 4 + extraLsbs.size * 2).order(ByteOrder.BIG_ENDIAN)
        for (i in advances.indices) {
            buf.putShort(advances[i].toShort())
            buf.putShort(lsbs[i].toShort())
        }
        for (lsb in extraLsbs) buf.putShort(lsb.toShort())
        return buf.array()
    }

    private fun shortLoca(glyphs: List<ByteArray>): ByteArray {
        val buf = ByteBuffer.allocate((glyphs.size + 1) * 2).order(ByteOrder.BIG_ENDIAN)
        var cursor = 0
        for (glyph in glyphs) {
            check(cursor % 2 == 0) { "short loca offset must be even" }
            buf.putShort((cursor / 2).toShort())
            cursor += glyph.size
        }
        buf.putShort((cursor / 2).toShort())
        return buf.array()
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (part in parts) {
            part.copyInto(out, pos)
            pos += part.size
        }
        return out
    }

    private fun cff(): ByteArray {
        val header = byteArrayOf(1, 0, 4, 1)
        val name = cffIndex(listOf("Test".toByteArray(Charsets.US_ASCII)))
        val stringIndex = byteArrayOf(0, 0)
        val globalSubrs = byteArrayOf(0, 0)
        val charset = byteArrayOf(0, 0, 1)
        val charStrings = cffIndex(otfCharStrings())
        val privateDict = byteArrayOf(139.toByte(), 20)
        val topDictIndexSize = 28
        val charsetAt = header.size + name.size + topDictIndexSize + stringIndex.size + globalSubrs.size
        val charStringsAt = charsetAt + charset.size
        val privateAt = charStringsAt + charStrings.size
        val topDict = topDict(charsetAt, charStringsAt, privateDict.size, privateAt)
        val topIndex = cffIndex(listOf(topDict))
        check(topIndex.size == topDictIndexSize) { "unexpected Top DICT INDEX size ${topIndex.size}" }
        return header + name + topIndex + stringIndex + globalSubrs + charset + charStrings + privateDict
    }

    private fun topDict(
        charsetAt: Int,
        charStringsAt: Int,
        privateSize: Int,
        privateAt: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(cffInt5(charsetAt))
        out.write(15)
        out.write(cffInt5(charStringsAt))
        out.write(17)
        out.write(cffInt5(privateSize))
        out.write(cffInt5(privateAt))
        out.write(18)
        val bytes = out.toByteArray()
        check(bytes.size == 23) { "unexpected Top DICT size ${bytes.size}" }
        return bytes
    }

    private fun cffInt5(value: Int): ByteArray =
        byteArrayOf(
            29,
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun cffIndex(objects: List<ByteArray>): ByteArray {
        val dataSize = objects.sumOf { it.size }
        val offSize = 1
        val buf = ByteBuffer.allocate(3 + (objects.size + 1) * offSize + dataSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(objects.size.toShort())
        buf.put(offSize.toByte())
        var offset = 1
        buf.put(offset.toByte())
        for (obj in objects) {
            offset += obj.size
            check(offset <= 0xFF)
            buf.put(offset.toByte())
        }
        for (obj in objects) buf.put(obj)
        return buf.array()
    }

    private fun assemble(
        sfVersion: Int,
        tables: Map<String, ByteArray>,
    ): ByteArray {
        val tags = tables.keys.sorted()
        val count = tags.size
        val searchRange = floorPow2(count) * 16
        val entrySelector = log2(floorPow2(count))
        val rangeShift = count * 16 - searchRange
        val padded = tags.associateWith { (tables.getValue(it).size + 3) and 3.inv() }
        val headerSize = 12 + count * 16
        val out = ByteBuffer.allocate(headerSize + padded.values.sum()).order(ByteOrder.BIG_ENDIAN)
        out.putInt(sfVersion)
        out.putShort(count.toShort())
        out.putShort(searchRange.toShort())
        out.putShort(entrySelector.toShort())
        out.putShort(rangeShift.toShort())
        var dataOffset = headerSize
        for (tag in tags) {
            val data = tables.getValue(tag)
            check(tag.length == 4)
            out.put(tag.toByteArray(Charsets.US_ASCII))
            out.putInt(0)
            out.putInt(dataOffset)
            out.putInt(data.size)
            dataOffset += padded.getValue(tag)
        }
        for (tag in tags) {
            val data = tables.getValue(tag)
            out.put(data)
            repeat(padded.getValue(tag) - data.size) { out.put(0) }
        }
        return out.array()
    }

    private fun floorPow2(n: Int): Int {
        var power = 1
        while (power * 2 <= n) power *= 2
        return power
    }

    private fun log2(n: Int): Int {
        var value = n
        var result = 0
        while (value > 1) {
            value = value shr 1
            result++
        }
        return result
    }

    private const val POST_HEADER_SIZE: Int = 32
    private const val POST_VERSION_2: Int = 0x00020000
    private const val POST_VERSION_3: Int = 0x00030000
    private const val MAC_GLYPH_COUNT: Int = 258
    private const val SFVERSION_OTTO: Int = 0x4F54544F
}

@file:Suppress(
    "detekt.CyclomaticComplexMethod",
    "detekt.LoopWithTooManyJumpStatements",
    "detekt.MagicNumber",
    "detekt.MapGetWithNotNullAssertionOperator",
    "detekt.NestedBlockDepth",
    "detekt.TooManyFunctions",
    "detekt.UnsafeCallOnNullableType",
)

package asahiart.fontsubset.ttf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin font subsetter.
 *
 * Supports:
 *  - TrueType outlines (glyf / loca) — sfVersion 0x00010000
 *  - CFF/OTF outlines (CFF  table)  — sfVersion 0x4F54544F ("OTTO")
 *  - cmap format 4 (Unicode BMP) and format 12 (full Unicode)
 *  - Simple and composite TTF glyphs (dependency resolution)
 *  - Required tables: head, hhea, maxp, cmap, hmtx
 *  - Pass-through tables: OS/2, name, post, kern, GSUB, GPOS, GDEF, …
 */
class TtfSubsetter {
    companion object {
        private const val SFVERSION_OTTO = 0x4F54544F // "OTTO"
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Subset [fontBytes] so it only contains glyphs needed for [codepoints].
     * .notdef (glyph 0) is always included.
     *
     * Supports both TrueType (TTF) and CFF/OpenType (OTF) fonts.
     *
     * @param fontBytes Raw bytes of a TTF or OTF file.
     * @param codepoints Unicode codepoints to keep (as Int, e.g. 0x4E2D for '中').
     * @return Raw bytes of the subsetted font.
     */
    fun subset(
        fontBytes: ByteArray,
        codepoints: Set<Int>,
    ): ByteArray {
        require(fontBytes.size >= 12) { "Not a valid font file (too small)" }

        val buf = ByteBuffer.wrap(fontBytes).order(ByteOrder.BIG_ENDIAN)
        val sfVersion = buf.getInt(0)
        val numTables = buf.getShort(4).toInt() and 0xFFFF

        val tables = parseTables(buf, numTables)

        return if (sfVersion == SFVERSION_OTTO) {
            subsetOtf(buf, sfVersion, tables, codepoints)
        } else {
            subsetTtf(buf, sfVersion, tables, codepoints)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TTF subsetting path
    // ──────────────────────────────────────────────────────────────────────────

    private fun subsetTtf(
        buf: ByteBuffer,
        sfVersion: Int,
        tables: Map<String, TableRecord>,
        codepoints: Set<Int>,
    ): ByteArray {
        // ── head ──────────────────────────────────────────────────────────────
        val headRec = tables["head"] ?: error("Missing 'head' table")
        val indexToLocFormat = buf.getShort(headRec.offset + 50).toInt()

        // ── maxp ──────────────────────────────────────────────────────────────
        val maxpRec = tables["maxp"] ?: error("Missing 'maxp' table")
        val numGlyphs = buf.getShort(maxpRec.offset + 4).toInt() and 0xFFFF

        // ── loca ──────────────────────────────────────────────────────────────
        val locaRec = tables["loca"] ?: error("Missing 'loca' table")
        val glyfOffsets = parseLoca(buf, locaRec, numGlyphs, indexToLocFormat)

        // ── cmap ──────────────────────────────────────────────────────────────
        val cmapRec = tables["cmap"] ?: error("Missing 'cmap' table")
        val charToGlyph = parseCmap(buf, cmapRec)

        // ── Collect needed glyph IDs ──────────────────────────────────────────
        val neededGlyphs = mutableSetOf(0) // .notdef always included
        for (cp in codepoints) {
            charToGlyph[cp]?.let { neededGlyphs.add(it) }
        }

        // Resolve composite glyph component dependencies
        val glyfRec = tables["glyf"] ?: error("Missing 'glyf' table")
        resolveComposites(buf, glyfRec, glyfOffsets, neededGlyphs)

        // ── Build old→new glyph ID mapping ────────────────────────────────────
        val sortedGlyphs = neededGlyphs.sorted()
        val oldToNew = HashMap<Int, Int>(sortedGlyphs.size * 2)
        sortedGlyphs.forEachIndexed { newId, oldId -> oldToNew[oldId] = newId }
        val newNumGlyphs = sortedGlyphs.size

        // ── Build modified tables ─────────────────────────────────────────────
        val (newGlyfData, newGlyfOffsets) =
            buildGlyf(buf, glyfRec, glyfOffsets, sortedGlyphs, oldToNew)

        val useLongLoca = newGlyfOffsets.last() > 0x1FFFE // > 65535 * 2
        val newLocaData = buildLoca(newGlyfOffsets, useLongLoca)
        val newCmapData = buildCmap(charToGlyph, codepoints, oldToNew)
        val newHmtxData = buildHmtx(buf, tables, sortedGlyphs)
        val newMaxpData = buildMaxp(buf, maxpRec, newNumGlyphs)
        val newHeadData = buildHead(buf, headRec, if (useLongLoca) 1 else 0)
        val newHheaData = buildHhea(buf, tables, newNumGlyphs)

        // ── Assemble final table map ──────────────────────────────────────────
        val allTables = LinkedHashMap<String, ByteArray>()

        allTables["glyf"] = newGlyfData
        allTables["loca"] = newLocaData
        allTables["cmap"] = newCmapData
        allTables["hmtx"] = newHmtxData
        allTables["maxp"] = newMaxpData
        allTables["head"] = newHeadData
        allTables["hhea"] = newHheaData

        // Pass-through tables (keep original bytes)
        val passthroughTags =
            listOf(
                "OS/2",
                "name",
                "post",
                "kern",
                "cvt ",
                "fpgm",
                "prep",
                "gasp",
                // NOTE: GSUB/GPOS/GDEF are intentionally excluded from subsets:
                // Subsetting retains only a handful of glyphs, and all lookup
                // tables in GSUB/GPOS still reference the original full glyph set.
                // Keeping them verbatim wastes megabytes for CJK fonts.
                // Compose/HarfBuzz renders isolated glyphs fine without shaping tables.
            )
        for (tag in passthroughTags) {
            tables[tag]?.let { rec ->
                allTables[tag] = readTable(buf, rec)
            }
        }

        return assembleFont(sfVersion, allTables)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OTF/CFF subsetting path
    // ──────────────────────────────────────────────────────────────────────────

    private fun subsetOtf(
        buf: ByteBuffer,
        sfVersion: Int,
        tables: Map<String, TableRecord>,
        codepoints: Set<Int>,
    ): ByteArray {
        // ── maxp ──────────────────────────────────────────────────────────────
        val maxpRec = tables["maxp"] ?: error("Missing 'maxp' table")

        // ── cmap ──────────────────────────────────────────────────────────────
        val cmapRec = tables["cmap"] ?: error("Missing 'cmap' table")
        val charToGlyph = parseCmap(buf, cmapRec)

        // ── Collect needed glyph IDs ──────────────────────────────────────────
        // CFF fonts don't have TTF composite glyphs, so no component resolution needed
        val neededGlyphs = mutableSetOf(0) // .notdef always included
        for (cp in codepoints) {
            charToGlyph[cp]?.let { neededGlyphs.add(it) }
        }

        val sortedGlyphs = neededGlyphs.sorted()
        val oldToNew = HashMap<Int, Int>(sortedGlyphs.size * 2)
        sortedGlyphs.forEachIndexed { newId, oldId -> oldToNew[oldId] = newId }
        val newNumGlyphs = sortedGlyphs.size

        // ── Subset CFF / CFF2 table ───────────────────────────────────────────
        val cff1Rec = tables["CFF "]
        val cff2Rec = tables["CFF2"]
        val (cffTableTag, newCffData) =
            when {
                cff1Rec != null -> {
                    val cffData = readTable(buf, cff1Rec)
                    "CFF " to CffSubsetter().subset(cffData, sortedGlyphs)
                }
                cff2Rec != null -> {
                    val cffData = readTable(buf, cff2Rec)
                    "CFF2" to CffSubsetter().subsetCff2(cffData, sortedGlyphs)
                }
                else -> error("Missing 'CFF ' or 'CFF2' table (not a CFF/OTF font?)")
            }

        // ── Build shared metric tables ────────────────────────────────────────
        val headRec = tables["head"] ?: error("Missing 'head' table")
        val indexToLocFormat = buf.getShort(headRec.offset + 50).toInt()

        val newCmapData = buildCmap(charToGlyph, codepoints, oldToNew)
        val newHmtxData = buildHmtx(buf, tables, sortedGlyphs)
        val newMaxpData = buildMaxp(buf, maxpRec, newNumGlyphs)
        val newHeadData = buildHead(buf, headRec, indexToLocFormat) // preserve original locaFormat
        val newHheaData = buildHhea(buf, tables, newNumGlyphs)

        // ── Assemble final table map ──────────────────────────────────────────
        val allTables = LinkedHashMap<String, ByteArray>()

        allTables[cffTableTag] = newCffData
        allTables["cmap"] = newCmapData
        allTables["hmtx"] = newHmtxData
        allTables["maxp"] = newMaxpData
        allTables["head"] = newHeadData
        allTables["hhea"] = newHheaData

        // Pass-through tables for OTF (no glyf/loca/cvt/fpgm/prep)
        val passthroughTags =
            buildList {
                addAll(listOf("OS/2", "name", "post", "kern"))
                // NOTE: GSUB/GPOS/GDEF are intentionally excluded from subsets:
                // Subsetting retains only a handful of glyphs, and all lookup
                // tables in GSUB/GPOS still reference the original full glyph set.
                // Keeping them verbatim wastes megabytes for CJK fonts.
                // Compose/HarfBuzz renders isolated glyphs fine without shaping tables.

                // Variable font tables: preserve axis definitions and variation data
                // so that weight/width/etc. axes remain functional after subsetting.

                // NOTE: HVAR and VVAR are GID-indexed (DeltaSetIndexMap references old GIDs)
                // and cannot be passed through verbatim after GID renumbering.
                // Dropping them is safe: outline variation still works via CFF2 blend operators;
                // only per-glyph metric variation (advance width/height changes with weight) is lost.
                // MVAR is font-wide (not GID-indexed) so it can be kept.
                if (cff2Rec != null) {
                    addAll(listOf("fvar", "STAT", "avar", "MVAR"))
                }
            }
        for (tag in passthroughTags) {
            tables[tag]?.let { rec ->
                allTables[tag] = readTable(buf, rec)
            }
        }

        return assembleFont(sfVersion, allTables)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Table parsing
    // ──────────────────────────────────────────────────────────────────────────

    private data class TableRecord(
        val tag: String,
        val offset: Int,
        val length: Int,
    )

    private fun parseTables(
        buf: ByteBuffer,
        numTables: Int,
    ): Map<String, TableRecord> {
        val result = HashMap<String, TableRecord>(numTables * 2)
        val tagBuf = ByteArray(4)
        for (i in 0 until numTables) {
            val base = 12 + i * 16
            buf.position(base)
            buf.get(tagBuf)
            val tag = String(tagBuf, Charsets.US_ASCII)
            buf.getInt() // skip checksum
            val offset = buf.getInt()
            val length = buf.getInt()
            result[tag] = TableRecord(tag, offset, length)
        }
        return result
    }

    /** Returns glyfOffsets[0..numGlyphs] inclusive (last entry = end of last glyph). */
    private fun parseLoca(
        buf: ByteBuffer,
        rec: TableRecord,
        numGlyphs: Int,
        format: Int,
    ): IntArray {
        buf.position(rec.offset)
        return IntArray(numGlyphs + 1) {
            if (format == 0) {
                (buf.getShort().toInt() and 0xFFFF) * 2
            } else {
                buf.getInt()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // cmap parsing
    // ──────────────────────────────────────────────────────────────────────────

    private fun parseCmap(
        buf: ByteBuffer,
        rec: TableRecord,
    ): Map<Int, Int> {
        val base = rec.offset
        buf.position(base)
        buf.getShort() // version
        val numSubtables = buf.getShort().toInt() and 0xFFFF

        var bestOffset = -1
        var bestScore = -1

        repeat(numSubtables) {
            val platformId = buf.getShort().toInt() and 0xFFFF
            val encodingId = buf.getShort().toInt() and 0xFFFF
            val subtableOffset = buf.getInt()

            // Prefer: Windows/Full-Unicode (f12) > Windows/BMP (f4) > Unicode platform
            val score =
                when {
                    platformId == 3 && encodingId == 10 -> 100 // Win, Unicode full (format 12)
                    platformId == 0 && encodingId == 6 -> 90 // Unicode, full
                    platformId == 0 && encodingId == 4 -> 85 // Unicode, 2.0+
                    platformId == 3 && encodingId == 1 -> 80 // Win, BMP (format 4)
                    platformId == 0 && encodingId == 3 -> 70 // Unicode, BMP
                    platformId == 0 && encodingId == 0 -> 60 // Unicode default
                    else -> -1
                }
            if (score > bestScore) {
                bestScore = score
                bestOffset = base + subtableOffset
            }
        }

        check(bestOffset >= 0) { "No usable cmap subtable found" }
        buf.position(bestOffset)
        return when (val format = buf.getShort().toInt() and 0xFFFF) {
            4 -> parseCmapFormat4(buf, bestOffset)
            12 -> parseCmapFormat12(buf, bestOffset)
            else -> error("Unsupported cmap format: $format")
        }
    }

    private fun parseCmapFormat4(
        buf: ByteBuffer,
        offset: Int,
    ): Map<Int, Int> {
        buf.position(offset + 6) // skip format(2) + length(2) + language(2)
        val segCount = (buf.getShort().toInt() and 0xFFFF) / 2
        buf.position(buf.position() + 6) // skip searchRange, entrySelector, rangeShift

        val endCounts = IntArray(segCount) { buf.getShort().toInt() and 0xFFFF }
        buf.getShort() // reservedPad
        val startCounts = IntArray(segCount) { buf.getShort().toInt() and 0xFFFF }
        val idDeltas = IntArray(segCount) { buf.getShort().toInt() } // signed!
        val idRangeOffsetBase = buf.position()
        val idRangeOffsets = IntArray(segCount) { buf.getShort().toInt() and 0xFFFF }

        val result = HashMap<Int, Int>()
        for (seg in 0 until segCount) {
            val end = endCounts[seg]
            if (end == 0xFFFF) break
            val start = startCounts[seg]
            for (c in start..end) {
                val glyphId: Int =
                    if (idRangeOffsets[seg] == 0) {
                        (c + idDeltas[seg]) and 0xFFFF
                    } else {
                        // Pointer arithmetic: address of idRangeOffset[seg] + idRangeOffset[seg] + offset
                        val rangeOffsetAddr = idRangeOffsetBase + seg * 2
                        val glyphAddr = rangeOffsetAddr + idRangeOffsets[seg] + (c - start) * 2
                        val raw = buf.getShort(glyphAddr).toInt() and 0xFFFF
                        if (raw == 0) 0 else (raw + idDeltas[seg]) and 0xFFFF
                    }
                if (glyphId != 0) result[c] = glyphId
            }
        }
        return result
    }

    private fun parseCmapFormat12(
        buf: ByteBuffer,
        offset: Int,
    ): Map<Int, Int> {
        buf.position(offset + 12) // skip format(2)+reserved(2)+length(4)+language(4)
        val numGroups = buf.getInt()
        val result = HashMap<Int, Int>(numGroups * 4)
        repeat(numGroups) {
            val startChar = buf.getInt()
            val endChar = buf.getInt()
            val startGlyph = buf.getInt()
            for (cp in startChar..endChar) {
                result[cp] = startGlyph + (cp - startChar)
            }
        }
        return result
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Composite glyph resolution
    // ──────────────────────────────────────────────────────────────────────────

    private fun resolveComposites(
        buf: ByteBuffer,
        glyfRec: TableRecord,
        glyfOffsets: IntArray,
        glyphs: MutableSet<Int>,
    ) {
        val queue = ArrayDeque(glyphs.toList())
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val glyphLen = glyfOffsets[id + 1] - glyfOffsets[id]
            if (glyphLen <= 0) continue // empty glyph

            buf.position(glyfRec.offset + glyfOffsets[id])
            val numberOfContours = buf.getShort().toInt()
            if (numberOfContours >= 0) continue // simple glyph, no components

            // Composite glyph: parse components
            buf.position(glyfRec.offset + glyfOffsets[id] + 10) // skip header (2+8 bytes)
            var flags: Int
            do {
                flags = buf.getShort().toInt() and 0xFFFF
                val componentId = buf.getShort().toInt() and 0xFFFF
                if (glyphs.add(componentId)) queue.add(componentId)

                // Advance past arguments
                val argWords = flags and 0x0001 != 0
                buf.position(buf.position() + if (argWords) 4 else 2)

                // Advance past transformation
                when {
                    flags and 0x0080 != 0 -> buf.position(buf.position() + 8) // 2x2 matrix
                    flags and 0x0040 != 0 -> buf.position(buf.position() + 4) // x+y scale
                    flags and 0x0008 != 0 -> buf.position(buf.position() + 2) // uniform scale
                }
            } while (flags and 0x0020 != 0) // MORE_COMPONENTS
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Build new tables
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns Pair(newGlyfData, newGlyfOffsets).
     * newGlyfOffsets has length sortedGlyphs.size + 1.
     */
    private fun buildGlyf(
        buf: ByteBuffer,
        glyfRec: TableRecord,
        glyfOffsets: IntArray,
        sortedGlyphs: List<Int>,
        oldToNew: Map<Int, Int>,
    ): Pair<ByteArray, IntArray> {
        val chunks = ArrayList<ByteArray>(sortedGlyphs.size)
        val newOffsets = IntArray(sortedGlyphs.size + 1)
        var cursor = 0

        for ((newId, oldId) in sortedGlyphs.withIndex()) {
            newOffsets[newId] = cursor
            val len = glyfOffsets[oldId + 1] - glyfOffsets[oldId]
            if (len <= 0) continue // empty glyph — no data written, offset stays same

            val raw = ByteArray(len)
            buf.position(glyfRec.offset + glyfOffsets[oldId])
            buf.get(raw)

            // Patch composite glyph component IDs
            val gb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            if (gb.getShort(0).toInt() < 0) {
                var pos = 10
                var flags: Int
                do {
                    flags = gb.getShort(pos).toInt() and 0xFFFF
                    val oldComp = gb.getShort(pos + 2).toInt() and 0xFFFF
                    val newComp =
                        oldToNew[oldComp]
                            ?: error("Composite component $oldComp not in glyph subset")
                    gb.putShort(pos + 2, newComp.toShort())

                    pos += 4
                    val argWords = flags and 0x0001 != 0
                    pos += if (argWords) 4 else 2
                    when {
                        flags and 0x0080 != 0 -> pos += 8
                        flags and 0x0040 != 0 -> pos += 4
                        flags and 0x0008 != 0 -> pos += 2
                    }
                } while (flags and 0x0020 != 0)
            }

            // Pad each glyph to 4-byte boundary (required by TTF spec)
            val padded = pad4(raw)
            chunks.add(padded)
            cursor += padded.size
        }
        newOffsets[sortedGlyphs.size] = cursor

        // Concatenate all glyph data
        val glyfData = ByteArray(cursor)
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(glyfData, pos)
            pos += chunk.size
        }
        return Pair(glyfData, newOffsets)
    }

    private fun buildLoca(
        glyfOffsets: IntArray,
        useLong: Boolean,
    ): ByteArray =
        if (useLong) {
            ByteBuffer
                .allocate(glyfOffsets.size * 4)
                .order(ByteOrder.BIG_ENDIAN)
                .also { b -> glyfOffsets.forEach { b.putInt(it) } }
                .array()
        } else {
            ByteBuffer
                .allocate(glyfOffsets.size * 2)
                .order(ByteOrder.BIG_ENDIAN)
                .also { b -> glyfOffsets.forEach { b.putShort((it / 2).toShort()) } }
                .array()
        }

    /**
     * Builds a cmap table with a single format-12 subtable (Windows, full Unicode).
     */
    private fun buildCmap(
        charToGlyph: Map<Int, Int>,
        codepoints: Set<Int>,
        oldToNew: Map<Int, Int>,
    ): ByteArray {
        // Collect valid mappings, sorted by codepoint
        val mappings = TreeMap<Int, Int>()
        for (cp in codepoints) {
            val oldId = charToGlyph[cp] ?: continue
            val newId = oldToNew[oldId] ?: continue
            mappings[cp] = newId
        }

        // Group into contiguous runs for format-12 sequential groups
        data class Group(
            val startChar: Int,
            val endChar: Int,
            val startGlyph: Int,
        )
        val groups = ArrayList<Group>()
        var prevChar = -2
        var prevGlyph = -2
        var groupStart = -1
        var groupGlyph = -1
        for ((cp, glyph) in mappings) {
            if (cp == prevChar + 1 && glyph == prevGlyph + 1) {
                // extend current group
            } else {
                if (groupStart >= 0) groups.add(Group(groupStart, prevChar, groupGlyph))
                groupStart = cp
                groupGlyph = glyph
            }
            prevChar = cp
            prevGlyph = glyph
        }
        if (groupStart >= 0) groups.add(Group(groupStart, prevChar, groupGlyph))

        val numGroups = groups.size
        // Layout: cmap header(4) + 1 subtable record(8) + format-12 header(16) + groups(12 each)
        val subtableSize = 16 + numGroups * 12
        val totalSize = 4 + 8 + subtableSize

        val b = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        // cmap header
        b.putShort(0) // version
        b.putShort(1) // numSubtables
        // Subtable record: Windows (3), Unicode full (10), offset after header+record = 12
        b.putShort(3)
        b.putShort(10)
        b.putInt(12)
        // Format 12
        b.putShort(12) // format
        b.putShort(0) // reserved
        b.putInt(subtableSize)
        b.putInt(0) // language
        b.putInt(numGroups)
        for (g in groups) {
            b.putInt(g.startChar)
            b.putInt(g.endChar)
            b.putInt(g.startGlyph)
        }
        return b.array()
    }

    private fun buildHmtx(
        buf: ByteBuffer,
        tables: Map<String, TableRecord>,
        sortedGlyphs: List<Int>,
    ): ByteArray {
        val hmtxRec = tables["hmtx"] ?: error("Missing 'hmtx' table")
        val hheaRec = tables["hhea"] ?: error("Missing 'hhea' table")
        val numHMetrics = buf.getShort(hheaRec.offset + 34).toInt() and 0xFFFF

        // We output one (advanceWidth + lsb) per glyph → numberOfHMetrics = newNumGlyphs
        val out = ByteBuffer.allocate(sortedGlyphs.size * 4).order(ByteOrder.BIG_ENDIAN)
        for (oldId in sortedGlyphs) {
            if (oldId < numHMetrics) {
                val base = hmtxRec.offset + oldId * 4
                out.putShort(buf.getShort(base)) // advanceWidth
                out.putShort(buf.getShort(base + 2)) // lsb
            } else {
                // Monospace tail: advanceWidth from last full metric, lsb from lsb array
                val lastMetricBase = hmtxRec.offset + (numHMetrics - 1) * 4
                out.putShort(buf.getShort(lastMetricBase)) // advanceWidth
                val lsbBase =
                    hmtxRec.offset + numHMetrics * 4 + (oldId - numHMetrics) * 2
                out.putShort(buf.getShort(lsbBase)) // lsb
            }
        }
        return out.array()
    }

    private fun buildMaxp(
        buf: ByteBuffer,
        rec: TableRecord,
        newNumGlyphs: Int,
    ): ByteArray {
        val data = readTable(buf, rec)
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putShort(4, newNumGlyphs.toShort())
        return data
    }

    private fun buildHead(
        buf: ByteBuffer,
        rec: TableRecord,
        locaFormat: Int,
    ): ByteArray {
        val data = readTable(buf, rec)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(50, locaFormat.toShort()) // indexToLocFormat
        bb.putInt(8, 0) // zero checkSumAdjustment — recalculated in assembleFont
        return data
    }

    private fun buildHhea(
        buf: ByteBuffer,
        tables: Map<String, TableRecord>,
        newNumGlyphs: Int,
    ): ByteArray {
        val rec = tables["hhea"] ?: error("Missing 'hhea' table")
        val data = readTable(buf, rec)
        // numberOfHMetrics (offset 34) = newNumGlyphs (every glyph has explicit advance)
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putShort(34, newNumGlyphs.toShort())
        return data
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Font assembly
    // ──────────────────────────────────────────────────────────────────────────

    private fun assembleFont(
        sfVersion: Int,
        tables: Map<String, ByteArray>,
    ): ByteArray {
        val sortedTags = tables.keys.sorted()
        val n = sortedTags.size

        val searchRange = floorPow2(n) * 16
        val entrySelector = log2Floor(floorPow2(n))
        val rangeShift = n * 16 - searchRange

        // Compute padded sizes for each table
        val paddedSize = LinkedHashMap<String, Int>()
        for (tag in sortedTags) paddedSize[tag] = pad4Size(tables[tag]!!.size)

        val headerSize = 12 + n * 16
        val totalSize = headerSize + paddedSize.values.sum()

        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Offset table
        out.putInt(sfVersion)
        out.putShort(n.toShort())
        out.putShort(searchRange.toShort())
        out.putShort(entrySelector.toShort())
        out.putShort(rangeShift.toShort())

        // Table directory: write records first (with placeholders), track offsets
        var dataOffset = headerSize
        for (tag in sortedTags) {
            val tableData = tables[tag]!!
            out.put(tag.padEnd(4).substring(0, 4).toByteArray(Charsets.US_ASCII))
            out.putInt(calcChecksum(tableData))
            out.putInt(dataOffset)
            out.putInt(tableData.size)
            dataOffset += paddedSize[tag]!!
        }

        // Table data (with padding)
        for (tag in sortedTags) {
            val tableData = tables[tag]!!
            out.put(tableData)
            repeat(paddedSize[tag]!! - tableData.size) { out.put(0) }
        }

        // Fix head.checkSumAdjustment
        val headIdx = sortedTags.indexOf("head")
        if (headIdx >= 0) {
            val headDataOffset =
                headerSize +
                    sortedTags.take(headIdx).sumOf { paddedSize[it]!! }
            val fileChecksum = calcFileChecksum(out.array())
            val adjustment = (0xB1B0AFBAL - fileChecksum).toInt()
            out.putInt(headDataOffset + 8, adjustment)
        }

        return out.array()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun readTable(
        buf: ByteBuffer,
        rec: TableRecord,
    ): ByteArray {
        val data = ByteArray(rec.length)
        buf.position(rec.offset)
        buf.get(data)
        return data
    }

    /** Pads [data] to a multiple of 4 bytes (TTF spec requirement for each table). */
    private fun pad4(data: ByteArray): ByteArray {
        val padded = pad4Size(data.size)
        return if (padded == data.size) data else data.copyOf(padded)
    }

    private fun pad4Size(size: Int): Int = (size + 3) and 0x7FFFFFFC

    private fun calcChecksum(data: ByteArray): Int {
        var sum = 0L
        val padded = pad4Size(data.size)
        for (i in 0 until padded step 4) {
            val b0 = if (i < data.size) data[i].toLong() and 0xFF else 0L
            val b1 = if (i + 1 < data.size) data[i + 1].toLong() and 0xFF else 0L
            val b2 = if (i + 2 < data.size) data[i + 2].toLong() and 0xFF else 0L
            val b3 = if (i + 3 < data.size) data[i + 3].toLong() and 0xFF else 0L
            sum += (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
        return (sum and 0xFFFFFFFFL).toInt()
    }

    private fun calcFileChecksum(data: ByteArray): Long {
        var sum = 0L
        for (i in data.indices step 4) {
            val b0 = data[i].toLong() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toLong() and 0xFF else 0L
            val b2 = if (i + 2 < data.size) data[i + 2].toLong() and 0xFF else 0L
            val b3 = if (i + 3 < data.size) data[i + 3].toLong() and 0xFF else 0L
            sum += (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
        return sum and 0xFFFFFFFFL
    }

    private fun floorPow2(n: Int): Int {
        var p = 1
        while (p * 2 <= n) p *= 2
        return p
    }

    private fun log2Floor(n: Int): Int {
        var r = 0
        var v = n
        while (v > 1) {
            v = v shr 1
            r++
        }
        return r
    }
}

// Alias so we don't need java.util.TreeMap import at the top
private typealias TreeMap<K, V> = java.util.TreeMap<K, V>

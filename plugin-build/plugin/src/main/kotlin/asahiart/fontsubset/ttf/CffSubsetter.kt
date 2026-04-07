@file:Suppress(
    "detekt.CyclomaticComplexMethod",
    "detekt.LargeClass",
    "detekt.LongMethod",
    "detekt.LongParameterList",
    "detekt.MagicNumber",
    "detekt.MaxLineLength",
    "detekt.NestedBlockDepth",
    "detekt.ReturnCount",
    "detekt.TooManyFunctions",
    "detekt.UnusedPrivateMember",
    "ktlint:standard:if-else-wrapping",
)

package asahiart.fontsubset.ttf

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CFF (Compact Font Format) subsetter, used for OTF fonts (sfVersion = "OTTO").
 *
 * Handles:
 *  - CFF version 1 (`CFF ` table): simple fonts and CID-keyed fonts (FDArray/FDSelect)
 *  - CFF version 2 (`CFF2` table): variable fonts, same CID-like structure
 *  - Charset formats 0, 1, 2
 *  - Subroutines kept verbatim (no subr subsetting — reduces size via CharStrings only)
 */
internal class CffSubsetter {
    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Subset the CFF table.
     *
     * @param cffData    Raw bytes of the `CFF ` table.
     * @param sortedGids New-GID-ordered list of old GIDs (index = new GID, value = old GID).
     *                   GID 0 (.notdef) must always be first.
     * @return Subsetted CFF bytes.
     */
    fun subset(
        cffData: ByteArray,
        sortedGids: List<Int>,
    ): ByteArray {
        require(cffData.size >= 4) { "CFF data too small" }
        require((cffData[0].toInt() and 0xFF) == 1) {
            "Only CFF version 1 is supported (got ${cffData[0].toInt() and 0xFF})"
        }

        val buf = ByteBuffer.wrap(cffData).order(ByteOrder.BIG_ENDIAN)
        val hdrSize = cffData[2].toInt() and 0xFF

        // ── Parse all indices in the canonical CFF order ───────────────────────
        var pos = hdrSize
        val nameIndex = readIndex(buf, pos).also { pos = it.end }
        val topDictIndex = readIndex(buf, pos).also { pos = it.end }
        val stringIndex = readIndex(buf, pos).also { pos = it.end }
        val globalSubrIndex = readIndex(buf, pos) // keep pos here

        require(topDictIndex.objects.isNotEmpty()) { "Empty CFF Top DICT INDEX" }
        val topDictData = topDictIndex.objects[0]
        val topDict = parseDict(topDictData)

        // ── Extract Top DICT fields ────────────────────────────────────────────
        val charStringsOffset = topDict[17]?.firstInt() ?: error("Missing CharStrings (op 17)")
        val charsetOffset = topDict[15]?.firstInt() ?: 0
        val privateInfo = topDict[18]
        val privateDictSize = privateInfo?.get(0)?.toInt() ?: 0
        val privateDictOffset = privateInfo?.get(1)?.toInt() ?: 0

        // CID: FDArray + FDSelect
        val fdArrayOffset = topDict[0x0C24]?.firstInt() ?: -1
        val fdSelectOffset = topDict[0x0C25]?.firstInt() ?: -1
        val isCid = fdArrayOffset >= 0

        // ── Parse CharStrings INDEX ────────────────────────────────────────────
        val charStringsIndex = readIndex(buf, charStringsOffset)
        val numGlyphs = charStringsIndex.objects.size

        // ── Parse Charset ──────────────────────────────────────────────────────
        val charset = parseCharset(buf, charsetOffset, numGlyphs)

        // ── Build new CharStrings (only needed GIDs, in new order) ────────────
        val newCharStrings =
            sortedGids.map { oldGid ->
                if (oldGid < charStringsIndex.objects.size) {
                    charStringsIndex.objects[oldGid]
                } else {
                    ByteArray(0)
                }
            }

        // ── Build new Charset ──────────────────────────────────────────────────
        val newCharsetBytes = buildCharset(sortedGids, charset)

        // ── Build new indices (keep name, string, globalSubr verbatim) ────────
        val newNameIndexBytes = buildIndex(nameIndex.objects)
        val newStringIndexBytes = buildIndex(stringIndex.objects)
        val newGlobalSubrIndexBytes = buildIndex(globalSubrIndex.objects)
        val newCharStringsIndexBytes = buildIndex(newCharStrings)

        return if (isCid) {
            subsetCid(
                buf,
                cffData,
                hdrSize,
                newNameIndexBytes,
                newStringIndexBytes,
                newGlobalSubrIndexBytes,
                newCharsetBytes,
                newCharStringsIndexBytes,
                topDictData,
                sortedGids,
                fdArrayOffset,
                fdSelectOffset,
                numGlyphs,
            )
        } else {
            subsetSimple(
                buf,
                cffData,
                hdrSize,
                newNameIndexBytes,
                newStringIndexBytes,
                newGlobalSubrIndexBytes,
                newCharsetBytes,
                newCharStringsIndexBytes,
                topDictData,
                privateDictSize,
                privateDictOffset,
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Simple (non-CID) font subsetting
    // ──────────────────────────────────────────────────────────────────────────

    private fun subsetSimple(
        buf: ByteBuffer,
        cffData: ByteArray,
        hdrSize: Int,
        newNameIndexBytes: ByteArray,
        newStringIndexBytes: ByteArray,
        newGlobalSubrIndexBytes: ByteArray,
        newCharsetBytes: ByteArray,
        newCharStringsIndexBytes: ByteArray,
        topDictData: ByteArray,
        privateDictSize: Int,
        privateDictOffset: Int,
    ): ByteArray {
        val (newPrivateDictBytes, newLocalSubrBytes) =
            buildPrivateBlock(buf, privateDictOffset, privateDictSize)

        // Two-pass offset computation
        val topDictBodyPass1 =
            buildTopDictBody(
                topDictData,
                charsetOff = 0,
                charStringsOff = 0,
                privateSz = newPrivateDictBytes.size,
                privateOff = 0,
                fdArrayOff = -1,
                fdSelectOff = -1,
            )
        val topDictIndexPass1 = buildIndex(listOf(topDictBodyPass1))

        var cursor = hdrSize
        cursor += newNameIndexBytes.size
        cursor += topDictIndexPass1.size
        cursor += newStringIndexBytes.size
        cursor += newGlobalSubrIndexBytes.size
        val charsetAbsOffset = cursor
        cursor += newCharsetBytes.size
        val charStringsAbsOffset = cursor
        cursor += newCharStringsIndexBytes.size
        val privateAbsOffset = cursor

        val finalTopDictBody =
            buildTopDictBody(
                topDictData,
                charsetOff = charsetAbsOffset,
                charStringsOff = charStringsAbsOffset,
                privateSz = newPrivateDictBytes.size,
                privateOff = privateAbsOffset,
                fdArrayOff = -1,
                fdSelectOff = -1,
            )
        val finalTopDictIndexBytes = buildIndex(listOf(finalTopDictBody))

        check(finalTopDictIndexBytes.size == topDictIndexPass1.size) {
            "CFF Top DICT INDEX size changed (${topDictIndexPass1.size} → ${finalTopDictIndexBytes.size})"
        }

        val out = ByteArrayOutputStream()
        out.write(cffData, 0, hdrSize)
        out.write(newNameIndexBytes)
        out.write(finalTopDictIndexBytes)
        out.write(newStringIndexBytes)
        out.write(newGlobalSubrIndexBytes)
        out.write(newCharsetBytes)
        out.write(newCharStringsIndexBytes)
        out.write(newPrivateDictBytes)
        out.write(newLocalSubrBytes)
        return out.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CID font subsetting
    //
    // Layout of output CFF for CID fonts:
    //   [hdr] [nameIndex] [topDictIndex] [stringIndex] [gsubrIndex]
    //   [charset] [charStrings]
    //   [fdSelect]                  ← format 0: tiny, 1 + numNewGlyphs bytes
    //   [privDict[0]] [lsubr[0]]    ← Private DICT + Local Subrs for needed FD 0
    //   [privDict[1]] [lsubr[1]]    ← Private DICT + Local Subrs for needed FD 1
    //   ...
    //   [fdArray]                   ← rebuilt INDEX with updated Private offsets
    // ──────────────────────────────────────────────────────────────────────────

    private fun subsetCid(
        buf: ByteBuffer,
        cffData: ByteArray,
        hdrSize: Int,
        newNameIndexBytes: ByteArray,
        newStringIndexBytes: ByteArray,
        newGlobalSubrIndexBytes: ByteArray,
        newCharsetBytes: ByteArray,
        newCharStringsIndexBytes: ByteArray,
        topDictData: ByteArray,
        sortedGids: List<Int>,
        fdArrayOffset: Int,
        fdSelectOffset: Int,
        numOrigGlyphs: Int,
    ): ByteArray {
        // ── Parse FDSelect: maps each old GID → FD index ──────────────────────
        val fdSelectArr = parseFdSelect(buf, fdSelectOffset, numOrigGlyphs)

        // ── Parse FDArray ─────────────────────────────────────────────────────
        val fdArrayObjs = readIndex(buf, fdArrayOffset).objects

        // ── Determine which FDs are needed by our selected glyphs ─────────────
        val neededFdSet =
            sortedGids
                .map { oldGid ->
                    if (oldGid < fdSelectArr.size) fdSelectArr[oldGid] else 0
                }.toSortedSet()
        val neededFdList = neededFdSet.toList() // sorted ascending
        val oldFdToNew = neededFdList.withIndex().associate { (newIdx, oldIdx) -> oldIdx to newIdx }

        // ── Build new FDSelect (format 0) ─────────────────────────────────────
        // 1 byte format + 1 byte per new glyph
        val newFdSelectBytes =
            ByteBuffer
                .allocate(1 + sortedGids.size)
                .also { bb ->
                    bb.put(0.toByte()) // format 0
                    for (oldGid in sortedGids) {
                        val oldFd = if (oldGid < fdSelectArr.size) fdSelectArr[oldGid] else 0
                        bb.put((oldFdToNew[oldFd] ?: 0).toByte())
                    }
                }.array()

        // ── Extract Private DICT + Local Subrs for each needed FD ─────────────
        data class FdInfo(
            val originalDictData: ByteArray,
            val privDictData: ByteArray, // original private dict bytes
            val localSubrIndexData: ByteArray, // rebuilt local subr INDEX bytes (may be empty)
        )

        val fdInfos: List<FdInfo> =
            neededFdList.map { oldFdIdx ->
                val fdDictData = if (oldFdIdx < fdArrayObjs.size) fdArrayObjs[oldFdIdx] else ByteArray(0)
                val fdDict = parseDict(fdDictData)
                val privInfo = fdDict[18]
                if (privInfo != null && privInfo.size >= 2) {
                    val privSize = privInfo[0].toInt()
                    val privOff = privInfo[1].toInt()
                    if (privSize > 0 && privOff > 0) {
                        val privData = ByteArray(privSize)
                        buf.position(privOff)
                        buf.get(privData)
                        val privDict = parseDict(privData)
                        val subrRelOff = privDict[19]?.firstInt() ?: 0
                        val lsubrIndexData =
                            if (subrRelOff != 0) {
                                val lsubrAbs = privOff + subrRelOff
                                buildIndex(readIndex(buf, lsubrAbs).objects)
                            } else {
                                ByteArray(0)
                            }
                        FdInfo(fdDictData, privData, lsubrIndexData)
                    } else {
                        FdInfo(fdDictData, ByteArray(0), ByteArray(0))
                    }
                } else {
                    FdInfo(fdDictData, ByteArray(0), ByteArray(0))
                }
            }

        // ── Two-pass layout computation ────────────────────────────────────────
        // Pass 1: compute sizes using placeholder (0) offsets
        val topDictBodyPass1 =
            buildTopDictBody(
                topDictData,
                charsetOff = 0,
                charStringsOff = 0,
                privateSz = 0,
                privateOff = 0,
                fdArrayOff = 0,
                fdSelectOff = 0,
            )
        val topDictIndexPass1 = buildIndex(listOf(topDictBodyPass1))

        var cursor = hdrSize
        cursor += newNameIndexBytes.size
        cursor += topDictIndexPass1.size
        cursor += newStringIndexBytes.size
        cursor += newGlobalSubrIndexBytes.size

        val charsetAbsOffset = cursor
        cursor += newCharsetBytes.size
        val charStringsAbsOffset = cursor
        cursor += newCharStringsIndexBytes.size
        val fdSelectAbsOffset = cursor
        cursor += newFdSelectBytes.size

        // Compute per-FD layout: each FD gets [privDict][lsubr] block
        data class FdLayout(
            val privAbsOffset: Int,
            val newPrivDictData: ByteArray,
            val lsubrData: ByteArray,
        )

        val fdLayouts: List<FdLayout> =
            fdInfos.map { info ->
                val privAbsOffset = cursor
                val hasLocalSubrs = info.localSubrIndexData.isNotEmpty()
                // Rebuild private dict: update Subrs relative offset to point right after dict.
                // Two-pass: first pass (offset=0) gives stable size; second pass uses that size.
                val newPrivDictData =
                    if (hasLocalSubrs && info.privDictData.isNotEmpty()) {
                        val pass1 = rebuildPrivateDictWithSubrsOffset(info.privDictData, 0)
                        rebuildPrivateDictWithSubrsOffset(info.privDictData, pass1.size)
                    } else {
                        info.privDictData
                    }
                cursor += newPrivDictData.size
                cursor += info.localSubrIndexData.size
                FdLayout(privAbsOffset, newPrivDictData, info.localSubrIndexData)
            }

        val fdArrayAbsOffset = cursor

        // ── Build new FDArray: one entry per needed FD ─────────────────────────
        val newFdArrayObjs =
            fdInfos.zip(fdLayouts).map { (info, layout) ->
                rebuildFdDict(info.originalDictData, layout.newPrivDictData.size, layout.privAbsOffset)
            }
        val newFdArrayIndexBytes = buildIndex(newFdArrayObjs)

        // ── Pass 2: rebuild Top DICT with real offsets ─────────────────────────
        val finalTopDictBody =
            buildTopDictBody(
                topDictData,
                charsetOff = charsetAbsOffset,
                charStringsOff = charStringsAbsOffset,
                privateSz = 0,
                privateOff = 0,
                fdArrayOff = fdArrayAbsOffset,
                fdSelectOff = fdSelectAbsOffset,
            )
        val finalTopDictIndexBytes = buildIndex(listOf(finalTopDictBody))

        check(finalTopDictIndexBytes.size == topDictIndexPass1.size) {
            "CID CFF Top DICT INDEX size changed (${topDictIndexPass1.size} → ${finalTopDictIndexBytes.size})"
        }

        // ── Assemble ──────────────────────────────────────────────────────────
        val out = ByteArrayOutputStream()
        out.write(cffData, 0, hdrSize)
        out.write(newNameIndexBytes)
        out.write(finalTopDictIndexBytes)
        out.write(newStringIndexBytes)
        out.write(newGlobalSubrIndexBytes)
        out.write(newCharsetBytes)
        out.write(newCharStringsIndexBytes)
        out.write(newFdSelectBytes)
        for (layout in fdLayouts) {
            out.write(layout.newPrivDictData)
            out.write(layout.lsubrData)
        }
        out.write(newFdArrayIndexBytes)
        return out.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CFF INDEX
    // ──────────────────────────────────────────────────────────────────────────

    private data class CffIndex(
        val objects: List<ByteArray>,
        val end: Int,
    )

    /** Read a CFF INDEX starting at [startOffset]. Returns objects + byte offset of next structure. */
    private fun readIndex(
        buf: ByteBuffer,
        startOffset: Int,
    ): CffIndex {
        buf.position(startOffset)
        val count = buf.getShort().toInt() and 0xFFFF
        if (count == 0) return CffIndex(emptyList(), startOffset + 2)

        val offSize = buf.get().toInt() and 0xFF
        val offsets = IntArray(count + 1) { readOffset(buf, offSize) }
        val dataBase = buf.position() // offsets are 1-indexed relative to here
        val objects =
            List(count) { i ->
                val len = offsets[i + 1] - offsets[i]
                val data = ByteArray(len)
                buf.position(dataBase + offsets[i] - 1)
                buf.get(data)
                data
            }
        val end = dataBase + offsets[count] - 1
        return CffIndex(objects, end)
    }

    private fun readOffset(
        buf: ByteBuffer,
        offSize: Int,
    ): Int =
        when (offSize) {
            1 -> buf.get().toInt() and 0xFF
            2 -> buf.getShort().toInt() and 0xFFFF
            3 -> {
                val b0 = buf.get().toInt() and 0xFF
                val b1 = buf.get().toInt() and 0xFF
                val b2 = buf.get().toInt() and 0xFF
                (b0 shl 16) or (b1 shl 8) or b2
            }
            4 -> buf.getInt()
            else -> error("Invalid CFF offSize: $offSize")
        }

    /** Build a CFF INDEX from a list of objects. */
    fun buildIndex(objects: List<ByteArray>): ByteArray {
        if (objects.isEmpty()) return byteArrayOf(0, 0)

        val totalData = objects.sumOf { it.size }
        val maxOffset = totalData + 1 // 1-indexed
        val offSize =
            when {
                maxOffset <= 0xFF -> 1
                maxOffset <= 0xFFFF -> 2
                maxOffset <= 0xFFFFFF -> 3
                else -> 4
            }

        val buf =
            ByteBuffer
                .allocate(2 + 1 + (objects.size + 1) * offSize + totalData)
                .order(ByteOrder.BIG_ENDIAN)

        buf.putShort(objects.size.toShort())
        buf.put(offSize.toByte())

        var off = 1
        writeOffset(buf, off, offSize)
        for (obj in objects) {
            off += obj.size
            writeOffset(buf, off, offSize)
        }
        for (obj in objects) buf.put(obj)
        return buf.array()
    }

    private fun writeOffset(
        buf: ByteBuffer,
        value: Int,
        offSize: Int,
    ) {
        when (offSize) {
            1 -> buf.put(value.toByte())
            2 -> buf.putShort(value.toShort())
            3 -> {
                buf.put((value shr 16).toByte())
                buf.put((value shr 8).toByte())
                buf.put(value.toByte())
            }
            4 -> buf.putInt(value)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CFF DICT parsing
    // ──────────────────────────────────────────────────────────────────────────

    /** Parse a CFF DICT into a map of operator → operand list. */
    private fun parseDict(data: ByteArray): Map<Int, List<Number>> {
        val result = LinkedHashMap<Int, List<Number>>()
        val stack = ArrayList<Number>(8)
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b == 12 -> {
                    i++
                    val op = 0x0C00 or (data[i].toInt() and 0xFF)
                    result[op] = stack.toList()
                    stack.clear()
                    i++
                }
                b in 0..27 -> {
                    result[b] = stack.toList()
                    stack.clear()
                    i++
                }
                b == 28 -> {
                    val v = (data[i + 1].toInt() shl 8) or (data[i + 2].toInt() and 0xFF)
                    stack.add(v.toShort())
                    i += 3
                }
                b == 29 -> {
                    val v =
                        ((data[i + 1].toInt() and 0xFF) shl 24) or
                            ((data[i + 2].toInt() and 0xFF) shl 16) or
                            ((data[i + 3].toInt() and 0xFF) shl 8) or
                            (data[i + 4].toInt() and 0xFF)
                    stack.add(v)
                    i += 5
                }
                b == 30 -> {
                    // Real number — skip nibbles until 0xF terminator
                    i++
                    while (i < data.size) {
                        val nibbles = data[i++].toInt() and 0xFF
                        if ((nibbles and 0x0F) == 0x0F || (nibbles ushr 4) == 0x0F) break
                    }
                    stack.add(0) // placeholder, we don't need the actual value
                }
                b in 32..246 -> {
                    stack.add(b - 139)
                    i++
                }
                b in 247..250 -> {
                    stack.add((b - 247) * 256 + (data[i + 1].toInt() and 0xFF) + 108)
                    i += 2
                }
                b in 251..254 -> {
                    stack.add(-(b - 251) * 256 - (data[i + 1].toInt() and 0xFF) - 108)
                    i += 2
                }
                else -> i++ // 255: reserved
            }
        }
        return result
    }

    private fun List<Number>.firstInt(): Int = first().toInt()

    // ──────────────────────────────────────────────────────────────────────────
    // CFF DICT encoding
    // ──────────────────────────────────────────────────────────────────────────

    /** Encode a CFF integer (for use in DICT operands). */
    private fun encodeInt(value: Int): ByteArray =
        when (value) {
            in -107..107 -> byteArrayOf((value + 139).toByte())
            in 108..1131 -> byteArrayOf(((value - 108) / 256 + 247).toByte(), ((value - 108) % 256).toByte())
            in -1131..-108 -> byteArrayOf(((-value - 108) / 256 + 251).toByte(), ((-value - 108) % 256).toByte())
            in -32768..32767 -> byteArrayOf(28, (value shr 8).toByte(), value.toByte())
            else -> byteArrayOf(29, (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        }

    /** Always 5 bytes — used for offset placeholders so Top DICT size is deterministic. */
    private fun encodeInt5(value: Int): ByteArray =
        byteArrayOf(29, (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    /**
     * Rebuild Top DICT body, replacing offset-bearing operators with new values.
     * All offset operands are encoded with 5 bytes to keep size deterministic across two passes.
     *
     * Operators patched: 15 (charset), 17 (CharStrings), 18 (Private),
     *                    0x0C24 (FDArray), 0x0C25 (FDSelect)
     */
    private fun buildTopDictBody(
        origData: ByteArray,
        charsetOff: Int,
        charStringsOff: Int,
        privateSz: Int,
        privateOff: Int,
        fdArrayOff: Int,
        fdSelectOff: Int,
    ): ByteArray {
        // Operators whose values must be replaced with new offsets
        val patchedOps = setOf(15, 16, 17, 18, 0x0C24, 0x0C25)
        val out = ByteArrayOutputStream()
        val dict = parseDict(origData)

        // We re-encode entry by entry, preserving original bytes for non-patched entries.
        // Parse entry byte ranges simultaneously.
        data class RawEntry(
            val opBytes: Int,
            val op: Int,
            val start: Int,
            val end: Int,
        )
        val entries = ArrayList<RawEntry>()
        var i = 0
        var entryStart = 0
        while (i < origData.size) {
            val b = origData[i].toInt() and 0xFF
            when {
                b == 12 -> {
                    entries.add(RawEntry(2, 0x0C00 or (origData[i + 1].toInt() and 0xFF), entryStart, i + 2))
                    i += 2
                    entryStart =
                        i
                }
                b in 0..21 -> {
                    entries.add(RawEntry(1, b, entryStart, i + 1))
                    i++
                    entryStart = i
                }
                b == 28 -> i += 3
                b == 29 -> i += 5
                b == 30 -> {
                    i++
                    while (i <
                        origData.size
                    ) {
                        val n = origData[i++].toInt() and 0xFF
                        if ((n and 0x0F) == 0x0F || (n ushr 4) == 0x0F) break
                    }
                }
                b in 32..246 -> i++
                b in 247..254 -> i += 2
                else -> i++
            }
        }

        for (entry in entries) {
            when (entry.op) {
                15 -> {
                    out.write(encodeInt5(charsetOff))
                    out.write(15)
                }
                16 -> {
                    out.write(encodeInt5(0))
                    out.write(16)
                } // Encoding = 0 (Standard)
                17 -> {
                    out.write(encodeInt5(charStringsOff))
                    out.write(17)
                }
                18 -> {
                    out.write(encodeInt5(privateSz))
                    out.write(encodeInt5(privateOff))
                    out.write(18)
                }
                0x0C24 ->
                    if (fdArrayOff >= 0) {
                        out.write(encodeInt5(fdArrayOff))
                        out.write(byteArrayOf(12, 36))
                    }
                0x0C25 ->
                    if (fdSelectOff >= 0) {
                        out.write(encodeInt5(fdSelectOff))
                        out.write(byteArrayOf(12, 37))
                    }
                else -> out.write(origData, entry.start, entry.end - entry.start) // verbatim
            }
        }

        // If charset/charStrings/private were not in original Top DICT, add them
        if (15 !in dict) {
            out.write(encodeInt5(charsetOff))
            out.write(15)
        }
        if (17 !in dict) {
            out.write(encodeInt5(charStringsOff))
            out.write(17)
        }
        if (18 !in dict && privateSz > 0) {
            out.write(encodeInt5(privateSz))
            out.write(encodeInt5(privateOff))
            out.write(18)
        }

        return out.toByteArray()
    }

    /**
     * Rebuild a CID Font Dict (FDArray entry) with an updated Private DICT pointer.
     * Only op 18 (Private) is replaced; all other entries are kept verbatim.
     */
    private fun rebuildFdDict(
        origData: ByteArray,
        newPrivSize: Int,
        newPrivOff: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val entries = parseDictRawEntries(origData)
        var hadPrivate = false
        for (entry in entries) {
            when (entry.op) {
                18 -> {
                    out.write(encodeInt5(newPrivSize))
                    out.write(encodeInt5(newPrivOff))
                    out.write(18)
                    hadPrivate = true
                }
                else -> out.write(origData, entry.start, entry.end - entry.start)
            }
        }
        if (!hadPrivate && newPrivSize > 0) {
            out.write(encodeInt5(newPrivSize))
            out.write(encodeInt5(newPrivOff))
            out.write(18)
        }
        return out.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Charset
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parse CFF Charset → GID-indexed array of SIDs (or CIDs for CID fonts).
     * Result[0] = 0 (.notdef = SID 0 always).
     */
    private fun parseCharset(
        buf: ByteBuffer,
        offset: Int,
        numGlyphs: Int,
    ): IntArray {
        if (offset in 0..2) {
            // Predefined charsets 0/1/2 — map GID → GID (SID = GID in these charsets)
            return IntArray(numGlyphs) { it }
        }
        buf.position(offset)
        val format = buf.get().toInt() and 0xFF
        val sid = IntArray(numGlyphs)
        sid[0] = 0
        when (format) {
            0 -> {
                for (i in 1 until numGlyphs) sid[i] = buf.getShort().toInt() and 0xFFFF
            }
            1 -> {
                var gid = 1
                while (gid < numGlyphs) {
                    val first = buf.getShort().toInt() and 0xFFFF
                    val nLeft = buf.get().toInt() and 0xFF
                    for (j in 0..nLeft) {
                        if (gid < numGlyphs) sid[gid++] = first + j
                    }
                }
            }
            2 -> {
                var gid = 1
                while (gid < numGlyphs) {
                    val first = buf.getShort().toInt() and 0xFFFF
                    val nLeft = buf.getShort().toInt() and 0xFFFF
                    for (j in 0..nLeft) {
                        if (gid < numGlyphs) sid[gid++] = first + j
                    }
                }
            }
        }
        return sid
    }

    /**
     * Build a Charset for [sortedGids] (index = new GID, value = old GID).
     * Uses format 0 (simplest, always valid).
     */
    private fun buildCharset(
        sortedGids: List<Int>,
        oldCharset: IntArray,
    ): ByteArray {
        // Format 0: 1 byte format + (numGlyphs-1) * 2 bytes SID (no .notdef entry)
        val buf = ByteBuffer.allocate(1 + (sortedGids.size - 1) * 2).order(ByteOrder.BIG_ENDIAN)
        buf.put(0) // format
        for (i in 1 until sortedGids.size) {
            val oldGid = sortedGids[i]
            val sid = if (oldGid < oldCharset.size) oldCharset[oldGid] else 0
            buf.putShort(sid.toShort())
        }
        return buf.array()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FDSelect
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parse CFF FDSelect into a GID-indexed array of FD indices.
     * Supports format 0 (one byte per glyph) and format 3 (range-based).
     */
    private fun parseFdSelect(
        buf: ByteBuffer,
        offset: Int,
        numGlyphs: Int,
    ): IntArray {
        buf.position(offset)
        val format = buf.get().toInt() and 0xFF
        return when (format) {
            0 -> IntArray(numGlyphs) { buf.get().toInt() and 0xFF }
            3 -> {
                val result = IntArray(numGlyphs)
                val nRanges = buf.getShort().toInt() and 0xFFFF

                // Read all ranges: (startGlyph: 2 bytes, fd: 1 byte)
                data class Range(
                    val start: Int,
                    val fd: Int,
                )
                val ranges = Array(nRanges) { Range(buf.getShort().toInt() and 0xFFFF, buf.get().toInt() and 0xFF) }
                val sentinel = buf.getShort().toInt() and 0xFFFF // end of last range
                for (ri in ranges.indices) {
                    val start = ranges[ri].start
                    val end = if (ri + 1 < nRanges) ranges[ri + 1].start else sentinel
                    val fd = ranges[ri].fd
                    for (gid in start until end) {
                        if (gid < numGlyphs) result[gid] = fd
                    }
                }
                result
            }
            else -> error("Unsupported FDSelect format: $format")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private DICT + Local Subrs
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build a new Private DICT + Local Subrs block.
     *
     * @return Pair(privateDictBytes, localSubrBytes) — placed consecutively in output.
     *         If local subrs exist, the Subrs offset in Private DICT is patched to
     *         point to privateDictBytes.size (i.e., immediately after the Private DICT).
     */
    private fun buildPrivateBlock(
        buf: ByteBuffer,
        privateDictOffset: Int,
        privateDictSize: Int,
    ): Pair<ByteArray, ByteArray> {
        if (privateDictSize == 0) return Pair(ByteArray(0), ByteArray(0))

        val pdData = ByteArray(privateDictSize)
        buf.position(privateDictOffset)
        buf.get(pdData)

        val privateDict = parseDict(pdData)

        // Operator 19 = Subrs (relative offset within Private DICT)
        val subrRelOffset = privateDict[19]?.firstInt() ?: return Pair(pdData, ByteArray(0))

        val localSubrAbsOffset = privateDictOffset + subrRelOffset
        val localSubrIndex = readIndex(buf, localSubrAbsOffset)
        if (localSubrIndex.objects.isEmpty()) return Pair(pdData, ByteArray(0))

        val localSubrBytes = buildIndex(localSubrIndex.objects)
        // Two-pass: compute stable rebuilt-dict size first, then encode the correct offset.
        val pass1 = rebuildPrivateDictWithSubrsOffset(pdData, 0)
        val newPdData = rebuildPrivateDictWithSubrsOffset(pdData, pass1.size)

        return Pair(newPdData, localSubrBytes)
    }

    /**
     * Rebuild Private DICT bytes, replacing the Subrs (op 19) operand with [newOffset].
     * All other entries are copied verbatim.
     */
    private fun rebuildPrivateDictWithSubrsOffset(
        origData: ByteArray,
        newOffset: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(origData.size)
        val entries = parseDictRawEntries(origData)
        for ((start, end, op) in entries) {
            if (op == 19) {
                out.write(encodeInt5(newOffset)) // always 5 bytes — keeps dict size stable across passes
                out.write(19)
            } else {
                out.write(origData, start, end - start)
            }
        }
        return out.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DICT raw entry scanner
    // ──────────────────────────────────────────────────────────────────────────

    private data class RawDictEntry(
        val start: Int,
        val end: Int,
        val op: Int,
    )

    private fun parseDictRawEntries(data: ByteArray): List<RawDictEntry> {
        val entries = ArrayList<RawDictEntry>()
        var i = 0
        var entryStart = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b == 12 -> {
                    val op = 0x0C00 or (data[i + 1].toInt() and 0xFF)
                    entries.add(RawDictEntry(entryStart, i + 2, op))
                    i += 2
                    entryStart = i
                }
                b in 0..27 -> { // single-byte operators (CFF1: 0-21; CFF2 adds 24 etc.)
                    entries.add(RawDictEntry(entryStart, i + 1, b))
                    i++
                    entryStart = i
                }
                b == 28 -> i += 3
                b == 29 -> i += 5
                b == 30 -> {
                    i++
                    while (i <
                        data.size
                    ) {
                        val n = data[i++].toInt() and 0xFF
                        if ((n and 0x0F) == 0x0F || (n ushr 4) == 0x0F) break
                    }
                }
                b in 32..246 -> i++
                b in 247..254 -> i += 2
                else -> i++
            }
        }
        return entries
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CFF2 subsetting (variable OTF fonts: "CFF2" table)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Subset a CFF2 table (variable fonts, sfVersion = "OTTO", SFNT table tag = "CFF2").
     *
     * CFF2 differences from CFF1:
     *  - INDEX count field is 4 bytes (uint32) instead of 2 bytes (uint16)
     *  - No Name/String INDEXes; Top DICT is embedded at [headerSize] with length [topDictLength]
     *  - Global Subrs INDEX immediately follows Top DICT
     *  - VariationStore at Top DICT op 24 — passed through verbatim (required for blend operators)
     *  - No Charset table; glyphs are identified by their CharStrings index
     *  - FDArray always present (same CID-like structure as CFF1 CID)
     *
     * @param cffData    Raw bytes of the `CFF2` table.
     * @param sortedGids New-GID-ordered list of old GIDs (index = new GID, value = old GID).
     *                   GID 0 (.notdef) must always be first.
     * @return Subsetted CFF2 bytes.
     */
    fun subsetCff2(
        cffData: ByteArray,
        sortedGids: List<Int>,
    ): ByteArray {
        require(cffData.size >= 5) { "CFF2 data too small" }
        require((cffData[0].toInt() and 0xFF) == 2) {
            "Expected CFF2 (version 2), got version ${cffData[0].toInt() and 0xFF}"
        }

        val buf = ByteBuffer.wrap(cffData).order(ByteOrder.BIG_ENDIAN)
        val hdrSize = cffData[2].toInt() and 0xFF
        val origTopDictLength = ((cffData[3].toInt() and 0xFF) shl 8) or (cffData[4].toInt() and 0xFF)

        // Top DICT bytes (at fixed position in header)
        val topDictData = cffData.copyOfRange(hdrSize, hdrSize + origTopDictLength)
        val topDict = parseDict(topDictData)

        // Global Subrs INDEX (CFF2 INDEX: 4-byte count) — immediately after Top DICT
        val globalSubrsStart = hdrSize + origTopDictLength
        val globalSubrsBytes = buildIndex2(readIndex2(buf, globalSubrsStart).objects)

        // Key offsets from Top DICT
        val charStringsOffset = topDict[17]?.firstInt() ?: error("CFF2: missing CharStrings (op 17)")
        val fdArrayOffset = topDict[0x0C24]?.firstInt() ?: error("CFF2: missing FDArray (op 12,36)")
        val fdSelectOffset = topDict[0x0C25]?.firstInt() ?: error("CFF2: missing FDSelect (op 12,37)")
        val varStoreOffset = topDict[24]?.firstInt()

        // CharStrings INDEX (CFF2 INDEX: 4-byte count)
        val charStringsIndex = readIndex2(buf, charStringsOffset)
        val numGlyphs = charStringsIndex.objects.size

        // FDSelect (same binary format as CFF1: format 0 or 3)
        val fdSelectArr = parseFdSelect(buf, fdSelectOffset, numGlyphs)

        // FDArray INDEX (CFF2 INDEX: 4-byte count)
        val fdArrayObjs = readIndex2(buf, fdArrayOffset).objects

        // Determine which FDs are needed for the selected glyphs
        val neededFdSet = sortedGids.map { if (it < fdSelectArr.size) fdSelectArr[it] else 0 }.toSortedSet()
        val neededFdList = neededFdSet.toList()
        val oldFdToNew = neededFdList.withIndex().associate { (newIdx, oldFd) -> oldFd to newIdx }

        // New FDSelect (format 0: 1 byte format + 1 byte per glyph)
        val newFdSelectBytes =
            ByteBuffer
                .allocate(1 + sortedGids.size)
                .also { bb ->
                    bb.put(0.toByte())
                    for (oldGid in sortedGids) {
                        val oldFd = if (oldGid < fdSelectArr.size) fdSelectArr[oldGid] else 0
                        bb.put((oldFdToNew[oldFd] ?: 0).toByte())
                    }
                }.array()

        // Extract Private DICT + Local Subrs for each needed FD
        data class FdInfo(
            val originalDictData: ByteArray,
            val privDictData: ByteArray,
            val localSubrIndexData: ByteArray,
        )

        val fdInfos: List<FdInfo> =
            neededFdList.map { oldFd ->
                val fdDictData = if (oldFd < fdArrayObjs.size) fdArrayObjs[oldFd] else ByteArray(0)
                val fdDict = parseDict(fdDictData)
                val privInfo = fdDict[18]
                if (privInfo != null && privInfo.size >= 2) {
                    val privSize = privInfo[0].toInt()
                    val privOff = privInfo[1].toInt()
                    val privData = cffData.copyOfRange(privOff, privOff + privSize)
                    val privDict = parseDict(privData)
                    val subrsRelOff = privDict[19]?.firstInt() ?: 0
                    val lsubrData =
                        if (subrsRelOff != 0) {
                            buildIndex2(readIndex2(buf, privOff + subrsRelOff).objects)
                        } else {
                            ByteArray(0)
                        }
                    FdInfo(fdDictData, privData, lsubrData)
                } else {
                    FdInfo(fdDictData, ByteArray(0), ByteArray(0))
                }
            }

        // New CharStrings (keep only the needed glyphs)
        val newCharStrings =
            sortedGids.map { oldGid ->
                if (oldGid < charStringsIndex.objects.size) charStringsIndex.objects[oldGid] else ByteArray(0)
            }
        val newCharStringsIndexBytes = buildIndex2(newCharStrings)

        // VariationStore: pass through verbatim (blend operators in charstrings need it)
        val varStoreData: ByteArray =
            if (varStoreOffset != null) {
                // CFF2 VariationStore: uint16 content length (not including the length field itself)
                val vsContentLen =
                    ((cffData[varStoreOffset].toInt() and 0xFF) shl 8) or
                        (cffData[varStoreOffset + 1].toInt() and 0xFF)
                cffData.copyOfRange(varStoreOffset, varStoreOffset + 2 + vsContentLen)
            } else {
                ByteArray(0)
            }

        // ── Two-pass layout ───────────────────────────────────────────────────
        // New Top DICT uses encodeInt5 for all offset operators → deterministic fixed size.
        // Pass 1: determine new Top DICT size using placeholder offsets.
        val topDictPass1 = buildCff2TopDictBody(topDictData, 0, 0, 0, if (varStoreOffset != null) 0 else null)
        val newTopDictLength = topDictPass1.size

        // Pass 2: compute all real absolute offsets
        var cursor = hdrSize + newTopDictLength
        cursor += globalSubrsBytes.size
        val newFdSelectAbsOff = cursor
        cursor += newFdSelectBytes.size
        val newCharStringsAbsOff = cursor
        cursor += newCharStringsIndexBytes.size
        val newVarStoreAbsOff: Int? =
            if (varStoreData.isNotEmpty()) {
                val off = cursor
                cursor += varStoreData.size
                off
            } else {
                null
            }

        data class FdLayout(
            val privAbsOffset: Int,
            val newPrivDictData: ByteArray,
            val lsubrData: ByteArray,
        )

        val fdLayouts: List<FdLayout> =
            fdInfos.map { info ->
                val privAbsOff = cursor
                val hasLocalSubrs = info.localSubrIndexData.isNotEmpty()
                val newPrivDictData =
                    if (hasLocalSubrs && info.privDictData.isNotEmpty()) {
                        val p1 = rebuildPrivateDictWithSubrsOffset(info.privDictData, 0)
                        rebuildPrivateDictWithSubrsOffset(info.privDictData, p1.size)
                    } else {
                        info.privDictData
                    }
                cursor += newPrivDictData.size
                cursor += info.localSubrIndexData.size
                FdLayout(privAbsOff, newPrivDictData, info.localSubrIndexData)
            }

        val newFdArrayAbsOff = cursor
        val newFdArrayObjs =
            fdInfos.zip(fdLayouts).map { (info, layout) ->
                rebuildFdDict(info.originalDictData, layout.newPrivDictData.size, layout.privAbsOffset)
            }
        val newFdArrayIndexBytes = buildIndex2(newFdArrayObjs)

        // Build final Top DICT with real offsets
        val finalTopDict =
            buildCff2TopDictBody(
                topDictData,
                charStringsOff = newCharStringsAbsOff,
                fdArrayOff = newFdArrayAbsOff,
                fdSelectOff = newFdSelectAbsOff,
                varStoreOff = newVarStoreAbsOff,
            )
        check(finalTopDict.size == newTopDictLength) {
            "CFF2 Top DICT size unstable: expected $newTopDictLength, got ${finalTopDict.size}"
        }

        // ── Assemble ─────────────────────────────────────────────────────────
        val out = ByteArrayOutputStream()
        // Header: copy major/minor/headerSize, update topDictLength
        out.write(cffData[0].toInt() and 0xFF) // major = 2
        out.write(cffData[1].toInt() and 0xFF) // minor = 0
        out.write(hdrSize) // headerSize unchanged
        out.write(newTopDictLength ushr 8) // topDictLength (high byte)
        out.write(newTopDictLength and 0xFF) // topDictLength (low byte)
        out.write(finalTopDict)
        out.write(globalSubrsBytes)
        out.write(newFdSelectBytes)
        out.write(newCharStringsIndexBytes)
        if (varStoreData.isNotEmpty()) out.write(varStoreData)
        for (layout in fdLayouts) {
            out.write(layout.newPrivDictData)
            out.write(layout.lsubrData)
        }
        out.write(newFdArrayIndexBytes)
        return out.toByteArray()
    }

    /**
     * Rebuild a CFF2 Top DICT body, replacing all offset-bearing operators with new values.
     * Offsets are always encoded as 5 bytes (encodeInt5) to keep the Top DICT size stable
     * across two-pass layout computation.
     *
     * Operators patched: 17 (CharStrings), 24 (VariationStore), 0x0C24 (FDArray), 0x0C25 (FDSelect).
     * All other entries are copied verbatim.
     */
    private fun buildCff2TopDictBody(
        origData: ByteArray,
        charStringsOff: Int,
        fdArrayOff: Int,
        fdSelectOff: Int,
        varStoreOff: Int?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        for ((start, end, op) in parseDictRawEntries(origData)) {
            when (op) {
                17 -> {
                    out.write(encodeInt5(charStringsOff))
                    out.write(17)
                }
                24 ->
                    if (varStoreOff != null) {
                        out.write(encodeInt5(varStoreOff))
                        out.write(24)
                    }
                0x0C24 -> {
                    out.write(encodeInt5(fdArrayOff))
                    out.write(byteArrayOf(12, 36))
                }
                0x0C25 -> {
                    out.write(encodeInt5(fdSelectOff))
                    out.write(byteArrayOf(12, 37))
                }
                else -> out.write(origData, start, end - start)
            }
        }
        return out.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CFF2 INDEX (4-byte count)
    // ──────────────────────────────────────────────────────────────────────────

    /** Read a CFF2 INDEX (4-byte count) starting at [startOffset]. */
    private fun readIndex2(
        buf: ByteBuffer,
        startOffset: Int,
    ): CffIndex {
        buf.position(startOffset)
        val count = buf.getInt()
        if (count == 0) return CffIndex(emptyList(), startOffset + 4)
        val offSize = buf.get().toInt() and 0xFF
        val offsets = IntArray(count + 1) { readOffset(buf, offSize) }
        val dataBase = buf.position()
        val objects =
            List(count) { i ->
                val len = offsets[i + 1] - offsets[i]
                val data = ByteArray(len)
                buf.position(dataBase + offsets[i] - 1)
                buf.get(data)
                data
            }
        return CffIndex(objects, dataBase + offsets[count] - 1)
    }

    /** Build a CFF2 INDEX (4-byte count) from a list of objects. */
    private fun buildIndex2(objects: List<ByteArray>): ByteArray {
        if (objects.isEmpty()) return byteArrayOf(0, 0, 0, 0)
        val totalData = objects.sumOf { it.size }
        val maxOffset = totalData + 1
        val offSize =
            when {
                maxOffset <= 0xFF -> 1
                maxOffset <= 0xFFFF -> 2
                maxOffset <= 0xFFFFFF -> 3
                else -> 4
            }
        val buf =
            ByteBuffer
                .allocate(4 + 1 + (objects.size + 1) * offSize + totalData)
                .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(objects.size) // 4-byte count
        buf.put(offSize.toByte())
        var off = 1
        writeOffset(buf, off, offSize)
        for (obj in objects) {
            off += obj.size
            writeOffset(buf, off, offSize)
        }
        for (obj in objects) buf.put(obj)
        return buf.array()
    }
}

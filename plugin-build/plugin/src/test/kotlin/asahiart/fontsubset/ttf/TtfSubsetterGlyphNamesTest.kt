package asahiart.fontsubset.ttf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class TtfSubsetterGlyphNamesTest {
    private val subsetter = TtfSubsetter()

    @Test
    fun `option off matches the 1_0_0 byte output`() {
        val ttf = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_4, SyntheticFont.PostKind.NAMES)
        val off = subsetter.subset(ttf, SyntheticFont.ttfCodePoints)
        val explicitOff = subsetter.subset(ttf, SyntheticFont.ttfCodePoints, dropGlyphNames = false)
        assertArrayEquals(off, explicitOff)
        assertEquals(TTF_OFF_SHA256, sha256(off))
        assertEquals(TTF_OFF_SIZE, off.size)

        val fromFormat12 =
            subsetter.subset(
                SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_12, SyntheticFont.PostKind.NAMES),
                SyntheticFont.ttfCodePoints,
                dropGlyphNames = false,
            )
        assertArrayEquals(off, fromFormat12)
        assertArrayEquals(ParsedFont(ttf).post, ParsedFont(off).post)

        val otf = SyntheticFont.openType(SyntheticFont.PostKind.NAMES)
        val otfOff = subsetter.subset(otf, SyntheticFont.otfCodePoints, dropGlyphNames = false)
        assertArrayEquals(otfOff, subsetter.subset(otf, SyntheticFont.otfCodePoints))
        assertEquals(OTF_OFF_SHA256, sha256(otfOff))
        assertEquals(OTF_OFF_SIZE, otfOff.size)
        assertArrayEquals(ParsedFont(otf).post, ParsedFont(otfOff).post)
    }

    @Test
    fun `dropping glyph names rewrites only post and the head checksum`() {
        val source = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_4, SyntheticFont.PostKind.NAMES)
        val off = subsetter.subset(source, SyntheticFont.ttfCodePoints, dropGlyphNames = false)
        val on = subsetter.subset(source, SyntheticFont.ttfCodePoints, dropGlyphNames = true)
        assertFalse(off.contentEquals(on))

        val offFont = ParsedFont(off)
        val onFont = ParsedFont(on)
        assertPostFormat3(onFont.post)
        assertArrayEquals(offFont.post!!.copyOfRange(4, POST_HEADER_SIZE), onFont.post!!.copyOfRange(4, POST_HEADER_SIZE))
        assertSameTablesExceptPostAndChecksum(offFont, onFont)
        assertTrueTypeOutlines(offFont)
        assertTrueTypeOutlines(onFont)
        assertReparsed(on, SyntheticFont.ttfCodePoints, onFont)
    }

    @Test
    fun `format 12 input drops names without changing cmap or outlines`() {
        val source = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_12, SyntheticFont.PostKind.NAMES)
        val off = ParsedFont(subsetter.subset(source, SyntheticFont.ttfCodePoints, dropGlyphNames = false))
        val on = ParsedFont(subsetter.subset(source, SyntheticFont.ttfCodePoints, dropGlyphNames = true))
        assertPostFormat3(on.post)
        assertSameTablesExceptPostAndChecksum(off, on)
        assertTrueTypeOutlines(on)
    }

    @Test
    fun `cff outlines stay intact when glyph names are dropped`() {
        val source = SyntheticFont.openType(SyntheticFont.PostKind.NAMES)
        val offBytes = subsetter.subset(source, SyntheticFont.otfCodePoints, dropGlyphNames = false)
        val onBytes = subsetter.subset(source, SyntheticFont.otfCodePoints, dropGlyphNames = true)
        val off = ParsedFont(offBytes)
        val on = ParsedFont(onBytes)
        assertPostFormat3(on.post)
        assertArrayEquals(off.post!!.copyOfRange(4, POST_HEADER_SIZE), on.post!!.copyOfRange(4, POST_HEADER_SIZE))
        assertSameTablesExceptPostAndChecksum(off, on)
        assertEquals(mapOf(0x41 to 1), on.cmap)
        assertGlyphsEqual(SyntheticFont.otfCharStrings(), on.charStrings)
        assertGlyphsEqual(off.charStrings, on.charStrings)
        assertArrayEquals(off.tables.getValue("CFF "), on.tables.getValue("CFF "))
        assertReparsed(onBytes, SyntheticFont.otfCodePoints, on)
    }

    @Test
    fun `fonts without glyph name payload stay byte identical`() {
        val absent = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_4, SyntheticFont.PostKind.ABSENT)
        val absentOff = subsetter.subset(absent, SyntheticFont.ttfCodePoints, dropGlyphNames = false)
        val absentOn = subsetter.subset(absent, SyntheticFont.ttfCodePoints, dropGlyphNames = true)
        assertArrayEquals(absentOff, absentOn)
        assertEquals(TTF_ABSENT_SHA256, sha256(absentOff))
        assertEquals(TTF_ABSENT_SIZE, absentOff.size)
        assertFalse(ParsedFont(absentOn).tables.containsKey("post"))

        val format3 = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_4, SyntheticFont.PostKind.FORMAT_3)
        val format3Off = subsetter.subset(format3, SyntheticFont.ttfCodePoints, dropGlyphNames = false)
        val format3On = subsetter.subset(format3, SyntheticFont.ttfCodePoints, dropGlyphNames = true)
        assertArrayEquals(format3Off, format3On)
        assertEquals(TTF_FORMAT3_SHA256, sha256(format3Off))
        assertEquals(TTF_FORMAT3_SIZE, format3Off.size)
        assertPostFormat3(ParsedFont(format3On).post)
    }

    @Test
    fun `dropping names rejects a post table shorter than the format 3 header`() {
        val source = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_4, SyntheticFont.PostKind.TRUNCATED)
        val off = subsetter.subset(source, SyntheticFont.ttfCodePoints, dropGlyphNames = false)
        assertEquals(16, ParsedFont(off).post!!.size)
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                subsetter.subset(source, SyntheticFont.ttfCodePoints, dropGlyphNames = true)
            }
        assertTrue(error.message!!.contains("32"))
    }

    private fun assertReparsed(
        bytes: ByteArray,
        codepoints: Set<Int>,
        original: ParsedFont,
    ) {
        val again = ParsedFont(subsetter.subset(bytes, codepoints, dropGlyphNames = true))
        val passthrough = ParsedFont(subsetter.subset(bytes, codepoints, dropGlyphNames = false))
        assertEquals(original.cmap, again.cmap)
        assertEquals(original.cmap, passthrough.cmap)
        assertGlyphsEqual(original.glyphs, again.glyphs)
        assertGlyphsEqual(original.glyphs, passthrough.glyphs)
        assertGlyphsEqual(original.charStrings, again.charStrings)
        assertGlyphsEqual(original.charStrings, passthrough.charStrings)
        assertPostFormat3(again.post)
        assertPostFormat3(passthrough.post)
    }

    private fun assertTrueTypeOutlines(font: ParsedFont) {
        assertEquals(3, font.cmapPlatform)
        assertEquals(10, font.cmapEncoding)
        assertEquals(mapOf(0x43 to 2, 0x4E2D to 3), font.cmap)
        assertGlyphsEqual(expectedGlyphs(), font.glyphs)
        assertArrayEquals(expectedHmtx(), font.tables.getValue("hmtx"))
        assertEquals(4, shortAt(font.tables.getValue("maxp"), 4))
        assertEquals(4, shortAt(font.tables.getValue("hhea"), 34))
        assertArrayEquals(SyntheticFont.nameTable(), font.tables.getValue("name"))
    }

    private fun assertPostFormat3(post: ByteArray?) {
        checkNotNull(post)
        assertEquals(POST_HEADER_SIZE, post.size)
        val buf = ByteBuffer.wrap(post).order(ByteOrder.BIG_ENDIAN)
        assertEquals(POST_VERSION_3, buf.int)
        assertEquals(SyntheticFont.ITALIC_ANGLE, buf.int)
        assertEquals(SyntheticFont.UNDERLINE_POSITION, buf.short.toInt())
        assertEquals(SyntheticFont.UNDERLINE_THICKNESS, buf.short.toInt())
        assertEquals(SyntheticFont.IS_FIXED_PITCH, buf.int)
        assertEquals(SyntheticFont.MIN_MEM_TYPE42, buf.int)
        assertEquals(SyntheticFont.MAX_MEM_TYPE42, buf.int)
        assertEquals(SyntheticFont.MIN_MEM_TYPE1, buf.int)
        assertEquals(SyntheticFont.MAX_MEM_TYPE1, buf.int)
    }

    private fun assertSameTablesExceptPostAndChecksum(
        off: ParsedFont,
        on: ParsedFont,
    ) {
        assertEquals(off.tables.keys, on.tables.keys)
        for (tag in off.tables.keys) {
            val left = off.tables.getValue(tag)
            val right = on.tables.getValue(tag)
            if (tag == "post") {
                assertTrue(left.size > right.size)
            } else if (tag == "head") {
                assertEquals(left.size, right.size)
                assertArrayEquals(left.copyOfRange(0, 8), right.copyOfRange(0, 8))
                assertArrayEquals(left.copyOfRange(12, left.size), right.copyOfRange(12, right.size))
            } else {
                assertArrayEquals(tag, left, right)
            }
        }
    }

    private fun assertGlyphsEqual(
        expected: List<ByteArray>,
        actual: List<ByteArray>,
    ) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertArrayEquals("glyph $index", expected[index], actual[index])
        }
    }

    private fun expectedGlyphs(): List<ByteArray> {
        val source = SyntheticFont.sourceGlyphs()
        val composite = source[3].copyOf()
        ByteBuffer.wrap(composite).order(ByteOrder.BIG_ENDIAN).putShort(12, 1)
        return listOf(source[0], pad4(source[2]), pad4(composite), pad4(source[4]))
    }

    private fun expectedHmtx(): ByteArray = metric(600, 1) + metric(400, 3) + metric(400, 4) + metric(400, 5)

    private fun metric(
        advance: Int,
        lsb: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(advance.toShort())
        buf.putShort(lsb.toShort())
        return buf.array()
    }

    private fun pad4(data: ByteArray): ByteArray {
        val padded = (data.size + 3) and 3.inv()
        return if (padded == data.size) data else data.copyOf(padded)
    }

    private fun shortAt(
        data: ByteArray,
        offset: Int,
    ): Int =
        ByteBuffer
            .wrap(data)
            .order(ByteOrder.BIG_ENDIAN)
            .getShort(offset)
            .toInt() and 0xFFFF

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private companion object {
        const val POST_HEADER_SIZE: Int = 32
        const val POST_VERSION_3: Int = 0x00030000
        const val TTF_OFF_SHA256: String = "e2832ffd72fc89fc30137cbcfb84d7a01a2627cc6ca521aa8e40ceafb7296eae"
        const val TTF_OFF_SIZE: Int = 528
        const val OTF_OFF_SHA256: String = "04da6ecd11420cfa7412984909d9b138c6cc4ecdedad911d6f2431340f62e859"
        const val OTF_OFF_SIZE: Int = 400
        const val TTF_ABSENT_SHA256: String = "c489b81d94e54dbcd9edc0c507084c42bf863b4e1b010c257845988309b8ba7d"
        const val TTF_ABSENT_SIZE: Int = 444
        const val TTF_FORMAT3_SHA256: String = "1c48b71367406b8f87fc8b7ce183ef7eed75205350ba33e7d28fd38b655b05c6"
        const val TTF_FORMAT3_SIZE: Int = 492
    }
}

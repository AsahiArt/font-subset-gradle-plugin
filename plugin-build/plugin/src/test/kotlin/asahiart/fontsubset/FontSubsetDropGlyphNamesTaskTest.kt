package asahiart.fontsubset

import asahiart.fontsubset.ttf.ParsedFont
import asahiart.fontsubset.ttf.SyntheticFont
import asahiart.fontsubset.ttf.TtfSubsetter
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FontSubsetDropGlyphNamesTaskTest {
    @Test
    fun `extension defaults dropGlyphNames to false`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("io.github.asahiart.fontsubset")
        val extension = project.extensions.getByType(FontSubsetExtension::class.java)
        val untouched = project.objects.newInstance(FontConfig::class.java)
        assertFalse(untouched.dropGlyphNames)

        extension.font {
            source = "in.ttf"
            output = "out.ttf"
            characters = "C"
            dropGlyphNames = true
        }
        assertTrue(
            extension.fontConfigs
                .get()
                .single()
                .dropGlyphNames,
        )
    }

    @Test
    fun `task passes dropGlyphNames through to the subset`() {
        withTempDir { dir ->
            val font = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_4, SyntheticFont.PostKind.NAMES)
            File(dir, "in.ttf").writeBytes(font)
            val project = ProjectBuilder.builder().withProjectDir(dir).build()
            val task = project.tasks.register("subsetFonts", FontSubsetTask::class.java).get()
            task.projectDir.set(dir)

            task.fontConfigs.set(listOf(fontConfig(project, "off.ttf", dropGlyphNames = false)))
            task.subsetFonts()
            task.fontConfigs.set(listOf(fontConfig(project, "on.ttf", dropGlyphNames = true)))
            task.subsetFonts()

            val off = File(dir, "off.ttf").readBytes()
            val on = File(dir, "on.ttf").readBytes()
            val subsetter = TtfSubsetter()
            assertArrayEquals(subsetter.subset(font, SyntheticFont.ttfCodePoints, dropGlyphNames = false), off)
            assertArrayEquals(subsetter.subset(font, SyntheticFont.ttfCodePoints, dropGlyphNames = true), on)
            assertEquals(0x00030000, ParsedFont(on).postVersion)
            assertEquals(32, ParsedFont(on).post!!.size)
            assertEquals(ParsedFont(off).cmap, ParsedFont(on).cmap)
            assertGlyphsEqual(ParsedFont(off).glyphs, ParsedFont(on).glyphs)
        }
    }

    @Test
    fun `json config honors dropGlyphNames`() {
        withTempDir { dir ->
            val font = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_4, SyntheticFont.PostKind.NAMES)
            File(dir, "in.ttf").writeBytes(font)
            File(dir, "fonts.json").writeText(
                """
                [
                  {
                    "source": "in.ttf",
                    "output": "out.ttf",
                    "characters": "C中Z",
                    "dropGlyphNames": true
                  }
                ]
                """.trimIndent(),
            )
            val project = ProjectBuilder.builder().withProjectDir(dir).build()
            val task = project.tasks.register("subsetFonts", FontSubsetTask::class.java).get()
            task.projectDir.set(dir)
            task.fontConfigs.set(emptyList())
            task.configFile.set(File(dir, "fonts.json"))
            task.subsetFonts()

            val output = File(dir, "out.ttf").readBytes()
            val direct = TtfSubsetter().subset(font, SyntheticFont.ttfCodePoints, dropGlyphNames = true)
            assertArrayEquals(direct, output)
            assertEquals(0x00030000, ParsedFont(output).postVersion)
            assertEquals(32, ParsedFont(output).post!!.size)
        }
    }

    @Test
    fun `subset false copies the font even when dropGlyphNames is set`() {
        withTempDir { dir ->
            val font = SyntheticFont.trueType(SyntheticFont.CmapKind.FORMAT_4, SyntheticFont.PostKind.NAMES)
            File(dir, "in.ttf").writeBytes(font)
            val project = ProjectBuilder.builder().withProjectDir(dir).build()
            val task = project.tasks.register("subsetFonts", FontSubsetTask::class.java).get()
            task.projectDir.set(dir)
            task.fontConfigs.set(
                listOf(
                    fontConfig(project, "out.ttf", dropGlyphNames = true).apply {
                        subset = false
                    },
                ),
            )
            task.subsetFonts()
            assertArrayEquals(font, File(dir, "out.ttf").readBytes())
        }
    }

    private fun fontConfig(
        project: Project,
        outputName: String,
        dropGlyphNames: Boolean,
    ): FontConfig =
        project.objects.newInstance(FontConfig::class.java).apply {
            source = "in.ttf"
            output = outputName
            characters = "C中Z"
            this.dropGlyphNames = dropGlyphNames
        }

    private fun assertGlyphsEqual(
        expected: List<ByteArray>,
        actual: List<ByteArray>,
    ) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertArrayEquals(expected[index], actual[index])
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("font-subset").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}

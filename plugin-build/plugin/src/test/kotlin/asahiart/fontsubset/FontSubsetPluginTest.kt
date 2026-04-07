package asahiart.fontsubset

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontSubsetPluginTest {
    @Test
    fun `plugin registers task and extension`() {
        val project = ProjectBuilder.builder().build()

        project.pluginManager.apply("asahiart.fontsubset")

        assertTrue(project.tasks.getByName("subsetFonts") is FontSubsetTask)
        assertNotNull(project.extensions.getByName("fontSubset"))
    }
}

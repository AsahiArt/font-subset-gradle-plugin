@file:Suppress("detekt.UnnecessaryAbstractClass")

package asahiart.fontsubset

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import javax.inject.Inject

/**
 * DSL extension, used in build.gradle.kts:
 *
 * ```kotlin
 * fontSubset {
 *     font {
 *         source = "src/main/assets/fonts/MyFont.ttf"
 *         output = "src/main/assets/fonts/MyFont-subset.ttf"
 *         characters = "你好世界Hello"
 *     }
 *     font {
 *         source = "src/main/assets/fonts/Another.ttf"
 *         output = "src/main/assets/fonts/Another-subset.ttf"
 *         charactersFile = "font-chars.txt"   // path relative to project dir
 *     }
 *     // Or load all fonts from a JSON config file:
 *     configFile = "font-subset.json"
 * }
 * ```
 */
abstract class FontSubsetExtension
    @Inject
    constructor(
        private val project: Project,
    ) {
        @get:Nested
        abstract val fontConfigs: ListProperty<FontConfig>

        /** Optional path to a JSON config file (relative to project dir). */
        var configFile: String = ""

        fun font(action: Action<FontConfig>) {
            val config = project.objects.newInstance(FontConfig::class.java)
            action.execute(config)
            fontConfigs.add(config)
        }

        fun font(configure: FontConfig.() -> Unit) {
            val config = project.objects.newInstance(FontConfig::class.java)
            config.configure()
            fontConfigs.add(config)
        }
    }

/**
 * Configuration for a single font subsetting operation.
 */
abstract class FontConfig {
    /** Source font file path, relative to project root. */
    @get:Input
    var source: String = ""

    /** Output font file path, relative to project root. */
    @get:Input
    var output: String = ""

    /**
     * Inline characters/string to include.
     * All unique Unicode codepoints in this string will be kept.
     */
    @get:Input
    var characters: String = ""

    /**
     * Path to a plain-text file (relative to project root) whose content
     * provides additional characters to include.
     */
    @get:Input
    var charactersFile: String = ""
}

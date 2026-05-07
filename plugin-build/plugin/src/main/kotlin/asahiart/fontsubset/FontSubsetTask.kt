@file:Suppress(
    "detekt.MagicNumber",
    "detekt.UseOrEmpty",
)

package asahiart.fontsubset

import asahiart.fontsubset.ttf.TtfSubsetter
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

abstract class FontSubsetTask
    @Inject
    constructor(
        private val objects: ObjectFactory,
    ) : DefaultTask() {
        /** Inline font configs (set from build script, not from configFile). */
        @get:Nested
        abstract val fontConfigs: ListProperty<FontConfig>

        /** Optional JSON config file — read at execution time so changes always take effect. */
        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val configFile: RegularFileProperty

        /** Source font files — changing a source font triggers re-subset. */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sourceFonts: ConfigurableFileCollection

        /**
         * Declared output files so Gradle can do proper up-to-date checks.
         * Set by the plugin at configuration time from the font configs.
         */
        @get:OutputFiles
        abstract val outputFonts: ConfigurableFileCollection

        @get:Internal
        abstract val projectDir: DirectoryProperty

        @TaskAction
        fun subsetFonts() {
            // Read font configs: prefer configFile (re-read at execution time for freshness),
            // fall back to inline fontConfigs from the build script.
            val configs =
                buildList {
                    addAll(fontConfigs.get())
                    if (configFile.isPresent) {
                        val file = configFile.get().asFile
                        if (file.exists()) {
                            @Suppress("UNCHECKED_CAST")
                            val entries = JsonSlurper().parse(file) as List<Map<String, Any?>>
                            entries.forEach { entry ->
                                add(
                                    objects.newInstance(FontConfig::class.java).apply {
                                        source = entry["source"]?.toString().orEmpty()
                                        output = entry["output"]?.toString().orEmpty()
                                        characters = entry["characters"]?.toString().orEmpty()
                                        charactersFile = entry["charactersFile"]?.toString().orEmpty()
                                        subset = entry["subset"] as? Boolean ?: true
                                    },
                                )
                            }
                        }
                    }
                }

            if (configs.isEmpty()) {
                logger.lifecycle("FontSubset: No fonts configured, skipping.")
                return
            }

            val subsetter = TtfSubsetter()
            var processed = 0
            var skipped = 0

            for (config in configs) {
                val result = processFont(config, subsetter)
                if (result) processed++ else skipped++
            }

            logger.lifecycle("FontSubset: Done — $processed subsetted, $skipped skipped.")
        }

        private fun processFont(
            config: FontConfig,
            subsetter: TtfSubsetter,
        ): Boolean {
            require(config.source.isNotBlank()) { "FontSubset: font.source must not be blank" }
            require(config.output.isNotBlank()) { "FontSubset: font.output must not be blank" }

            val root = projectDir.get().asFile
            val sourceFile = File(root, config.source)
            require(sourceFile.exists()) {
                "FontSubset: source font not found: ${sourceFile.absolutePath}"
            }

            val outputFile = File(root, config.output)

            // If subsetting is disabled, just copy source → output as-is.
            if (!config.subset) {
                outputFile.parentFile?.mkdirs()
                sourceFile.copyTo(outputFile, overwrite = true)
                val kb = sourceFile.length() / 1024
                logger.lifecycle(
                    "FontSubset: ↷ ${sourceFile.name} (subset=false, copied as-is, ${kb}KB) → ${outputFile.name}",
                )
                return false
            }

            val charsBuf = StringBuilder()
            if (config.characters.isNotBlank()) {
                charsBuf.append(config.characters)
            }
            if (config.charactersFile.isNotBlank()) {
                val charsFile = File(root, config.charactersFile)
                if (charsFile.exists()) {
                    charsBuf.append(charsFile.readText(Charsets.UTF_8))
                } else {
                    logger.warn("FontSubset: charactersFile not found: ${charsFile.absolutePath}")
                }
            }

            if (charsBuf.isBlank()) {
                logger.warn("FontSubset: No characters specified for '${config.source}', skipping.")
                return false
            }

            val codepoints: Set<Int> =
                buildSet {
                    charsBuf.toString().codePoints().forEach { add(it) }
                }

            logger.lifecycle(
                "FontSubset: Subsetting '${sourceFile.name}' " +
                    "(${codepoints.size} codepoints) → '${outputFile.name}' ...",
            )

            val fontBytes = sourceFile.readBytes()
            val subsetBytes = subsetter.subset(fontBytes, codepoints)

            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(subsetBytes)

            val origKb = fontBytes.size / 1024
            val newKb = subsetBytes.size / 1024
            val pct =
                if (fontBytes.isNotEmpty()) {
                    ((fontBytes.size - subsetBytes.size).toDouble() / fontBytes.size * 100).toInt()
                } else {
                    0
                }

            logger.lifecycle(
                "FontSubset: ✓ ${sourceFile.name} ${origKb}KB → ${outputFile.name} ${newKb}KB " +
                    "($pct% reduction)",
            )
            return true
        }
    }

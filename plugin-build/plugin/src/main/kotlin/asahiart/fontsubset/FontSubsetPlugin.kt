@file:Suppress(
    "detekt.CyclomaticComplexMethod",
    "detekt.LongMethod",
    "detekt.SpreadOperator",
    "detekt.UseOrEmpty",
)

package asahiart.fontsubset

import groovy.json.JsonSlurper
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class FontSubsetPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension =
            project.extensions.create(
                "fontSubset",
                FontSubsetExtension::class.java,
                project,
            )

        val taskProvider = project.tasks.register("subsetFonts", FontSubsetTask::class.java)

        project.afterEvaluate {
            val allConfigs = extension.fontConfigs.get().toMutableList()
            val configFileObject: File? =
                if (extension.configFile.isNotBlank()) {
                    val configFile = project.file(extension.configFile)
                    if (configFile.exists()) {
                        @Suppress("UNCHECKED_CAST")
                        val entries = JsonSlurper().parse(configFile) as List<Map<String, String>>
                        entries.forEach { entry ->
                            allConfigs +=
                                project.objects.newInstance(FontConfig::class.java).apply {
                                    source = entry["source"].orEmpty()
                                    output = entry["output"].orEmpty()
                                    characters = entry["characters"].orEmpty()
                                    charactersFile = entry["charactersFile"].orEmpty()
                                }
                        }
                        configFile
                    } else {
                        project.logger.warn("FontSubset: configFile not found: ${configFile.absolutePath}")
                        null
                    }
                } else {
                    null
                }

            taskProvider.get().also { task ->
                task.group = "build"
                task.description = "Subsets configured fonts to only include specified characters"
                task.fontConfigs.set(extension.fontConfigs.get())
                task.projectDir.set(project.projectDir)
                if (configFileObject != null) {
                    task.configFile.set(configFileObject)
                }
                val outputFiles =
                    allConfigs
                        .filter { it.output.isNotBlank() }
                        .map { File(project.projectDir, it.output) }
                task.outputFonts.from(*outputFiles.toTypedArray())
                val sourceFiles =
                    allConfigs
                        .filter { it.source.isNotBlank() }
                        .map { File(project.projectDir, it.source) }
                        .filter { it.exists() }
                task.sourceFonts.from(*sourceFiles.toTypedArray())
            }

            project.tasks.configureEach { configuredTask ->
                val hook =
                    configuredTask.name == "preBuild" ||
                        configuredTask.name == "generateDebugResources" ||
                        configuredTask.name == "generateReleaseResources" ||
                        configuredTask.name.startsWith("copyNonXmlValueResources") ||
                        configuredTask.name.startsWith("convertXmlValueResources")
                if (hook) {
                    configuredTask.dependsOn(taskProvider)
                }
            }
        }
    }
}

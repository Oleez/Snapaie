package com.snapaie.android.core

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Nothing the user reads may name the model, its licence, or the runtime.
 *
 * This has regressed three times. Each time the wording was fixed by hand, and each time
 * a new string reintroduced it somewhere the last sweep had not thought to look — most
 * recently through the manifest's release notes, which are rendered verbatim on the
 * download card and so were invisible to a grep of the UI sources.
 *
 * A person deciding whether to spend two gigabytes needs to know the size, that it is a
 * one-off, and that nothing leaves the phone. The name of the weights tells them nothing
 * and makes an app about reading books look like a developer tool.
 */
class PlainLanguageTest {

    /** Words that must never reach the screen, with what to say instead. */
    private val banned = mapOf(
        "gemma" to "name the feature, not the weights",
        "qwen" to "name the feature, not the weights",
        "litert" to "an engine name means nothing to a reader",
        "apache" to "the licence is not the user's decision to make",
        "e2b" to "a model variant is not user-facing",
        "e4b" to "a model variant is not user-facing",
        "hugging face" to "where it is hosted is not the user's concern",
        "quantiz" to "implementation detail",
    )

    /** Manifest fields that are rendered straight onto the download card. */
    private val shownFields = listOf("releaseNotes", "displayName", "description")

    private fun offences(text: String): List<String> =
        banned.filterKeys { text.contains(it, ignoreCase = true) }
            .map { (word, why) -> "\"$word\" — $why" }

    @Test
    fun `the bundled manifest's user-visible text names nothing technical`() {
        val manifest = File("src/main/assets/model/default-manifest.json")
        if (!manifest.isFile) return
        val problems = mutableListOf<String>()
        manifest.readLines().forEachIndexed { index, line ->
            val field = shownFields.firstOrNull { line.contains("\"$it\"") } ?: return@forEachIndexed
            literalsIn(line).drop(1).forEach { value ->
                offences(value).forEach { problems += "manifest:${index + 1} $field shows \"$value\" — $it" }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `no displayed string literal names the model`() {
        val problems = mutableListOf<String>()
        sourceFiles().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                literalsIn(code).forEach { literal ->
                    offences(literal).forEach { problems += "${file.path}:${index + 1} \"$literal\" — $it" }
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("technical detail reaching the screen:\n" + problems.joinToString("\n"))
        }
    }

    /**
     * UI sources and string resources only. The data layer legitimately says "litert" in
     * identifiers, imports and error logs, none of which a reader ever sees.
     */
    private fun sourceFiles(): List<File> =
        listOf(
            File("src/main/java/com/snapaie/android/ui"),
            File("src/main/java/com/snapaie/android/entry"),
            File("src/main/res/values"),
        ).filter { it.isDirectory }
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }

    /**
     * The quoted runs on a line. Crude on purpose: the banned words contain no quotes or
     * escapes, so splitting is enough and a stray odd quote can only produce a false
     * negative, never a false alarm that blocks a build.
     */
    private fun literalsIn(line: String): List<String> =
        line.split('"').filterIndexed { index, _ -> index % 2 == 1 }
}

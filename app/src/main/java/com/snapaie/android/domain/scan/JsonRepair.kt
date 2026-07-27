package com.snapaie.android.domain.scan

/**
 * Repair ladder for loose JSON emitted by small local models (ported from the
 * extension's layered recovery): fence strip -> outermost balanced object ->
 * bare array wrap. Each step yields a candidate string for the caller to parse.
 */
object JsonRepair {

    /** Ordered candidate JSON strings extracted from [raw]; empty when nothing looks like JSON. */
    fun candidates(raw: String, arrayWrapKey: String? = null): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = mutableListOf<String>()

        val unfenced = stripFences(trimmed)
        if (unfenced.startsWith("{") && unfenced.endsWith("}")) out += unfenced

        balancedObject(unfenced)?.let { if (it !in out) out += it }
        balancedObject(trimmed)?.let { if (it !in out) out += it }

        if (arrayWrapKey != null) {
            bareArray(unfenced)?.let { out += "{\"$arrayWrapKey\":$it}" }
        }
        return out
    }

    fun stripFences(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^```json\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^```\\s*"), "")
        s = s.replace(Regex("```\\s*$"), "")
        return s.trim()
    }

    /** First balanced top-level `{ ... }`, string/escape aware. */
    fun balancedObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** First balanced top-level `[ ... ]`, string/escape aware. */
    fun bareArray(s: String): String? {
        val start = s.indexOf('[')
        if (start < 0) return null
        val objStart = s.indexOf('{')
        if (objStart in 0 until start) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == ']' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

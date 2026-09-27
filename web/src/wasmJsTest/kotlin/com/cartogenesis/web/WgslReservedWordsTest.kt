package com.cartogenesis.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No WGSL module this build compiles names a word the WGSL specification reserves.
 *
 * A reserved word anywhere in a module is a shader-creation error (WGSL §16.2: a module "must not
 * contain a reserved word"), and a module that does not compile is declined without a sound: the
 * ice sheet's kernel named a local `from` and never once ran on a browser's device. This reads the
 * sources as text, so it runs wherever the tests run, device or none; whether a device compiles
 * them is `WgslCompilesTest`'s question, which a browser without WebGPU cannot answer.
 *
 * The list is the specification's own, as it stands in the W3C Candidate Recommendation Draft of
 * 21 September 2026, word for word. Keywords (`let`, `fn`, `loop` and the rest) are not on it: the
 * compiler already refuses one used as a name, and a keyword used as a keyword is the language.
 */
class WgslReservedWordsTest {

    @Test
    fun `no module names a reserved word`() {
        for (module in WGSL_MODULES) {
            val found = reservedWordsIn(module.source)
            assertEquals(
                emptyList(), found,
                "the ${module.name} WGSL names reserved word(s) $found, which no browser compiles"
            )
        }
    }

    /**
     * The scanner is not blind: the ice kernel's margin cell named `from` again, as it was, and a
     * local of the erosion kernel named `target`, are each found, and nothing else is.
     */
    @Test
    fun `a reserved word put back into a module is found`() {
        val iceAsItWas = Regex("\\bmarginCell\\b").replace(ICE_SHEET_WGSL, "from")
        assertTrue(iceAsItWas != ICE_SHEET_WGSL, "the ice kernel no longer has a marginCell local")
        assertEquals(listOf("from"), reservedWordsIn(iceAsItWas))

        val erosionPlanted = Regex("\\bdrop\\b").replace(EROSION_RATES_WGSL, "target")
        assertTrue(erosionPlanted != EROSION_RATES_WGSL, "the erosion kernel no longer has a drop")
        assertEquals(listOf("target"), reservedWordsIn(erosionPlanted))
    }

    /** What is and is not a word to the scanner: comments and numbers are not, names are. */
    @Test
    fun `comments and numeric suffixes are not words`() {
        val source = """
            // from a comment, which the compiler never reads as a token
            /* nor from /* a nested */ block comment */
            let a = 1u + 0x1Fu + 2.5e-3f;
            let shared = a;
        """.trimIndent()
        assertEquals(listOf("shared"), reservedWordsIn(source))
    }

    private companion object {

        /** Every reserved word in [source], in order and once each. */
        fun reservedWordsIn(source: String): List<String> =
            namesIn(withoutComments(source)).filter { it in RESERVED_WORDS }.distinct()

        /**
         * [source] with its comments blanked out. WGSL's block comments nest (§3.3), so a depth is
         * kept rather than stopping at the first closing mark.
         */
        fun withoutComments(source: String): String {
            val kept = StringBuilder(source.length)
            var index = 0
            var depth = 0
            while (index < source.length) {
                val pair = source.substring(index, minOf(index + 2, source.length))
                when {
                    pair == "/*" -> {
                        depth++
                        index += 2
                        kept.append("  ")
                    }
                    depth > 0 && pair == "*/" -> {
                        depth--
                        index += 2
                        kept.append("  ")
                    }
                    depth > 0 -> {
                        kept.append(' ')
                        index++
                    }
                    pair == "//" -> {
                        while (index < source.length && source[index] != '\n') index++
                    }
                    else -> {
                        kept.append(source[index])
                        index++
                    }
                }
            }
            return kept.toString()
        }

        /**
         * The names in [source]: runs that start with a letter or an underscore. A run that starts
         * with a digit is a number and is skipped whole, so the `u` of `1u` and the `e` of `2.5e-3`
         * are not taken for names.
         */
        fun namesIn(source: String): List<String> =
            Regex("[0-9][0-9A-Za-z_.]*|[A-Za-z_][A-Za-z0-9_]*").findAll(source)
                .map { it.value }
                .filter { !it[0].isDigit() }
                .toList()

        /** WGSL §16.2, "Reserved Words": every entry of the `_reserved` production. */
        val RESERVED_WORDS = setOf(
            "NULL", "Self", "abstract", "active", "alignas", "alignof", "as", "asm", "asm_fragment",
            "async", "attribute", "auto", "await", "become", "cast", "catch", "class", "co_await",
            "co_return", "co_yield", "coherent", "column_major", "common", "compile",
            "compile_fragment", "concept", "const_cast", "consteval", "constexpr", "constinit",
            "crate", "debugger", "decltype", "delete", "demote", "demote_to_helper", "do",
            "dynamic_cast", "enum", "explicit", "export", "extends", "extern", "external",
            "fallthrough", "filter", "final", "finally", "friend", "from", "fxgroup", "get", "goto",
            "groupshared", "highp", "impl", "implements", "import", "inline", "instanceof",
            "interface", "layout", "lowp", "macro", "macro_rules", "match", "mediump", "meta",
            "mod", "module", "move", "mut", "mutable", "namespace", "new", "nil", "noexcept",
            "noinline", "nointerpolation", "non_coherent", "noncoherent", "noperspective", "null",
            "nullptr", "of", "operator", "package", "packoffset", "partition", "pass", "patch",
            "pixelfragment", "precise", "precision", "premerge", "priv", "protected", "pub",
            "public", "readonly", "ref", "regardless", "register", "reinterpret_cast", "require",
            "resource", "restrict", "self", "set", "shared", "sizeof", "smooth", "snorm", "static",
            "static_assert", "static_cast", "std", "subroutine", "super", "target", "template",
            "this", "thread_local", "throw", "trait", "try", "type", "typedef", "typeid",
            "typename", "typeof", "union", "unless", "unorm", "unsafe", "unsized", "use", "using",
            "varying", "virtual", "volatile", "wgsl", "where", "with", "writeonly", "yield"
        )
    }
}

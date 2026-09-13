package com.cartogenesis.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Asking GitHub whether there is a newer release, and nothing else.
 *
 * There is no self-update here and there will not be one. The application does not write to its
 * own installation, does not download an installer, and does not restart itself; it reads one JSON
 * document, compares two version numbers, and if the answer is yes it offers a button that opens
 * the release page in a browser. That is the whole feature, and it is the reason the network reach
 * of this application is a single `GET` of a public, unauthenticated endpoint.
 *
 * Everything here except the fetch itself is pure, which is what makes the guard the spec asks for
 * possible: `UpdatesTest` puts an older release, the same release, a newer one, a malformed body
 * and an offline answer through [evaluate] without a socket being opened.
 */
internal object Updates {

    /** Public, unauthenticated, and rate-limited per IP, which a manual check cannot exceed. */
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/bhanright/Cartogenesis/releases/latest"

    /** Where a reader is sent when there is nothing to report, or the check could not run. */
    const val RELEASES_PAGE = "https://github.com/bhanright/Cartogenesis/releases"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The answer to "is there a newer release?", in the four shapes it can take.
     *
     * [Unknown] rather than an error type: a check that could not run is not a failure of the
     * application, and the dialog says so in a sentence rather than showing a stack trace.
     */
    sealed interface Status {
        data class Available(
            val version: String,
            val title: String,
            /** The first few lines of the release notes; see [summarise]. */
            val notes: String,
            val page: String
        ) : Status

        data class UpToDate(val version: String) : Status

        data class Unknown(val reason: String) : Status
    }

    @Serializable
    private class Release(
        @SerialName("tag_name") val tag: String = "",
        @SerialName("name") val name: String? = null,
        @SerialName("body") val body: String? = null,
        @SerialName("html_url") val page: String? = null,
        @SerialName("draft") val draft: Boolean = false,
        @SerialName("prerelease") val prerelease: Boolean = false
    )

    /**
     * Compares [current] against the release described by [body].
     *
     * [body] is null when the fetch did not happen at all — offline, no HTTP client, a refused
     * request — which is the [Status.Unknown] case that matters most, because it is the common one
     * and the one a naive implementation turns into an exception dialog.
     */
    fun evaluate(current: String, body: String?): Status {
        if (body == null) return Status.Unknown("Could not reach GitHub. Check the connection.")
        val release = runCatching { json.decodeFromString(Release.serializer(), body) }.getOrNull()
            ?: return Status.Unknown("GitHub's answer was not in a form this build understands.")
        val theirs = parseVersion(release.tag)
            ?: return Status.Unknown(
                "The latest release is tagged \"${release.tag}\", which is not a version."
            )
        val mine = parseVersion(current)
            ?: return Status.Unknown("This build's own version, \"$current\", is not a version.")
        // A draft or a pre-release should never be offered as "the latest": GitHub's own endpoint
        // excludes drafts, but a repository can publish a pre-release as latest by hand.
        if (release.draft || release.prerelease) return Status.UpToDate(current)
        return if (compare(theirs, mine) > 0) {
            Status.Available(
                version = release.tag.removePrefix("v"),
                title = release.name?.takeIf { it.isNotBlank() } ?: release.tag,
                notes = summarise(release.body),
                page = release.page?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE
            )
        } else {
            Status.UpToDate(current)
        }
    }

    /**
     * `v1.2.0`, `1.2.0`, `1.2` and `1.2.0-rc1` all parse; anything else does not.
     *
     * Three numbers, missing ones read as zero, and everything from the first hyphen or plus
     * discarded — semver says a pre-release sorts *below* its release, and this build never offers
     * one, so the tail is noise here rather than information. A component that is not a number at
     * all makes the whole tag unparseable rather than zero: `v1.x.0` is a mistake somebody should
     * see, not a version equal to 1.0.0.
     */
    fun parseVersion(tag: String): IntArray? {
        val cleaned = tag.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.size > VERSION_PARTS) return null
        val numbers = IntArray(VERSION_PARTS)
        parts.forEachIndexed { index, part ->
            val value = part.trim().toIntOrNull() ?: return null
            if (value < 0) return null
            numbers[index] = value
        }
        return numbers
    }

    /** Major, minor, patch — the three [parseVersion] fills in, missing ones read as zero. */
    private const val VERSION_PARTS = 3

    /** Negative, zero or positive as [left] sorts before, with, or after [right]. */
    fun compare(left: IntArray, right: IntArray): Int {
        for (part in 0 until VERSION_PARTS) {
            if (left[part] != right[part]) return left[part].compareTo(right[part])
        }
        return 0
    }

    /** Convenience for the guard: compares two tags, or null if either is not a version. */
    fun compareTags(left: String, right: String): Int? =
        compare(parseVersion(left) ?: return null, parseVersion(right) ?: return null)

    /**
     * The first few lines of the release notes.
     *
     * Release bodies are markdown and can run to pages. The dialog is not a changelog viewer — the
     * button next to this opens the real one — so it shows the opening, with the markdown bullet
     * and heading marks taken off so the text reads as prose in the application's own faces.
     */
    fun summarise(body: String?, lines: Int = 6): String {
        if (body.isNullOrBlank()) return "No notes were published with this release."
        val kept = body.replace("\r\n", "\n").split('\n')
            // Up to three leading hashes, which is every markdown heading level this ever sees.
            .map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim() }
            .map { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }
            .filter { it.isNotBlank() }
            .take(lines)
        if (kept.isEmpty()) return "No notes were published with this release."
        return kept.joinToString("\n")
    }
}

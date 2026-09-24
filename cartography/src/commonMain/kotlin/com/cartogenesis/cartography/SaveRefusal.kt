package com.cartogenesis.cartography

/**
 * Why a save was refused, in the kinds a reader is owed different sentences for.
 *
 * A save is refused whole or opened whole: nothing here is regenerated to cover a gap, because a
 * world quietly remade on this machine from the settings in a damaged file is a different world
 * wearing the saved one's name. Each kind says what the reader can do about it, which is why they
 * are kinds and not one "could not open".
 */
enum class SaveProblem(private val sentence: String) {
    /** Not a Cartogenesis save at all. */
    NOT_A_SAVE("it is not a Cartogenesis save"),

    /**
     * The file stops before its world does, or holds only zeros where a save begins: what a copy
     * cut short looks like, and what a file a sync client has not finished bringing down looks like.
     */
    INCOMPLETE(
        "it is incomplete; if the library is in a synced folder, the file may not have finished " +
            "downloading yet"
    ),

    /** The bytes are all there and disagree with themselves or with this build's format. */
    DAMAGED("it is damaged"),

    /** A save from an older or a newer build, refused by name rather than misread. */
    WRONG_VERSION("it was written by a different version of Cartogenesis"),

    /** Larger than this build can hold, refused before anything that size is allocated. */
    TOO_LARGE("it is larger than this build can open"),

    /** Compressed in a way this platform cannot expand. */
    CANNOT_EXPAND("it is compressed in a way this platform cannot expand"),

    /**
     * The storage itself refused to read the file: gone, locked, or an online-only placeholder
     * whose sync client cannot fetch it.
     */
    UNREADABLE(
        "it could not be read; if the library is in a synced folder, the file may be online-only " +
            "and not yet downloaded"
    );

    /** The whole sentence for a reader, with [detail] saying which part of the file it was. */
    fun describe(detail: String): String = if (detail.isBlank()) sentence else "$sentence ($detail)"
}

/**
 * Thrown inside the codec when a save is refused, carrying which [problem] it was. [LoadOutcome]
 * is how it leaves the codec: a caller never has to catch this to learn why a file did not open.
 */
class WorldFormatException(val problem: SaveProblem, val detail: String) :
    IllegalArgumentException(problem.describe(detail))

/** A refusal as a reader sees it: the kind, and the sentence that explains it. */
data class SaveRefusal(val problem: SaveProblem, val detail: String) {
    val message: String get() = problem.describe(detail)
}

/**
 * What opening a save came to: the world, or the reason it was refused.
 *
 * A cancelled load is neither. It is not caught anywhere on the way here, so it reaches the caller
 * as the cancellation it is instead of as "no such save".
 */
sealed interface LoadOutcome {
    class Loaded(val save: WorldSave) : LoadOutcome
    class Refused(val refusal: SaveRefusal) : LoadOutcome
}

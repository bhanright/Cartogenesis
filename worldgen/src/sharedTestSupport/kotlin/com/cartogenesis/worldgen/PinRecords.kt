package com.cartogenesis.worldgen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * One change record mode wants made to a pinned figure in the source tree, or one thing it will
 * not change and a person has to look at ([Operation.NOTICE]).
 *
 * [file] is the source file's absolute path and [line] the one-based line the pin was read from
 * (0 where only [anchor] finds it). What [old], [new] and [anchor] hold depends on [operation]:
 * a value and its replacement for the two literal operations, whole lines with their indentation
 * trimmed for the line operations. [test] is the test that measured it and [kind] which of the
 * suite's pinning mechanisms holds it, for the review summary.
 */
internal data class PinRecord(
    val kind: String,
    val operation: Operation,
    val test: String,
    val file: String,
    val line: Int,
    val anchor: String,
    val old: String,
    val new: String
) {
    enum class Operation {
        /** [old] and [new] are string values; the Kotlin string literal holding [old] is rewritten. */
        STRING_LITERAL,

        /** [old] and [new] are source tokens (a number, say) on a line containing [anchor]. */
        CODE_TOKEN,

        /** The line reading [old], trimmed, becomes [new] at the same indentation. */
        REPLACE_LINE,

        /** The line reading [old], trimmed, is removed. */
        DELETE_LINE,

        /** [new] is written after the line reading [anchor], trimmed, at its indentation. */
        INSERT_AFTER,

        /** [new] is written before the line reading [anchor], trimmed, at its indentation. */
        INSERT_BEFORE,

        /** Nothing is written: [new] says what a person has to decide. */
        NOTICE
    }

    fun toJson(): String = buildJsonObject {
        put("kind", kind)
        put("operation", operation.name)
        put("test", test)
        put("file", file)
        put("line", line)
        put("anchor", anchor)
        put("old", old)
        put("new", new)
    }.toString()

    companion object {
        fun fromJson(text: String): PinRecord {
            val fields: JsonObject = Json.parseToJsonElement(text).jsonObject
            fun string(name: String) = (fields[name] as JsonPrimitive).content
            return PinRecord(
                string("kind"), Operation.valueOf(string("operation")), string("test"), string("file"),
                fields.getValue("line").jsonPrimitive.int, string("anchor"), string("old"), string("new")
            )
        }
    }
}

/**
 * Record mode for the suite's pinned figures: with it on, a pin that no longer holds writes what
 * was measured, as the replacement for the literal in the source, instead of failing, and
 * `PinRecordRewriter` then writes every such replacement into the source for a person to review
 * as a diff.
 *
 * It is on when the system property [RECORD_PROPERTY] is `true`, which the test tasks set under
 * `-Precord`; the records go to [DIRECTORY_PROPERTY]'s directory, one file per test JVM so that
 * workers running side by side never interleave a line. Only a figure a test measured is ever
 * recorded. A choice — a sample seed found by scanning, a bar derived from its control, a finding's
 * name, arming a clause that no longer fails — is a [notice] and is never rewritten.
 */
internal object PinRecords {

    const val RECORD_PROPERTY = "cartogenesis.record"
    const val DIRECTORY_PROPERTY = "cartogenesis.recordDirectory"

    /**
     * Where the records go instead of the directory the test task names, and record mode forced on:
     * the self-tests set it for the length of one case, so that they can show a stale pin failing,
     * recorded and rewritten without the task being run with `-Precord`.
     */
    @Volatile
    var redirectedTo: File? = null

    val recording: Boolean get() = redirectedTo != null || System.getProperty(RECORD_PROPERTY) == "true"

    /** The file this JVM's records are appended to: named for the process, so workers keep apart. */
    val recordFile: File?
        get() = redirectedTo ?: System.getProperty(DIRECTORY_PROPERTY)?.let {
            File(it, "records-${ProcessHandle.current().pid()}.jsonl")
        }

    @Synchronized
    fun write(record: PinRecord) {
        println("PIN RECORD ${record.operation} ${record.file.substringAfterLast(File.separatorChar)}:${record.line} " +
            "[${record.old}] -> [${record.new}] (${record.test})")
        val file = recordFile ?: return
        file.parentFile?.mkdirs()
        file.appendText(record.toJson() + "\n")
    }

    /**
     * A text signature that no longer matches: the string literal holding [old] at the call site of
     * the helper [helper] is to read [new]. The call site is taken from the stack, the first frame
     * outside [helper] and this object.
     */
    fun staleString(kind: String, helper: Class<*>, old: String, new: String) {
        val site = callerOutside(helper)
        write(PinRecord(kind, PinRecord.Operation.STRING_LITERAL, testName(), pathOf(site), site?.lineNumber ?: 0, "", old, new))
    }

    /** Something record mode will not decide, written beside the records for the review. */
    fun notice(kind: String, helper: Class<*>, message: String) {
        val site = callerOutside(helper)
        write(PinRecord(kind, PinRecord.Operation.NOTICE, testName(), pathOf(site), site?.lineNumber ?: 0, "", "", message))
    }

    /** Where a pin was written: the file the frame is in, and its line. */
    class SourceLine(val path: String, val line: Int)

    /** The source line that called into [helper]: for a table of pins, where each entry was written. */
    fun sourceLineOutside(helper: Class<*>): SourceLine {
        val site = callerOutside(helper)
        return SourceLine(pathOf(site), site?.lineNumber ?: 0)
    }

    /** The first frame on the stack that is neither in [helper] (or its lambdas) nor in this object. */
    fun callerOutside(helper: Class<*>): StackTraceElement? =
        Throwable().stackTrace.firstOrNull { frame -> !isIn(frame, helper) && !isIn(frame, PinRecords::class.java) }

    /** Whether [frame] is in [type] or in one of its lambdas and companions, and not a class that shares its name's start. */
    private fun isIn(frame: StackTraceElement, type: Class<*>): Boolean =
        frame.className == type.name || frame.className.startsWith(type.name + "$")

    /**
     * The test running on this thread, as `Class.method`: the frame whose method carries a `@Test`
     * annotation, JUnit 4's or JUnit 5's, and failing that the outermost frame of this project's.
     * Not simply the outermost, because a rule wrapping the test (`SharedWorlds`) is outermost.
     */
    fun testName(): String {
        val frames = Throwable().stackTrace.filter { it.className.startsWith(PROJECT_PACKAGE) }
        val frame = frames.firstOrNull(::isTestMethod) ?: frames.lastOrNull() ?: return "unknown test"
        return frame.className.substringAfterLast('.').substringBefore('$') + "." + frame.methodName
    }

    private fun isTestMethod(frame: StackTraceElement): Boolean = try {
        Class.forName(frame.className).declaredMethods.any { method ->
            method.name == frame.methodName && method.annotations.any { it.annotationClass.simpleName == "Test" }
        }
    } catch (notLoadable: ReflectiveOperationException) {
        false
    } catch (notLinkable: LinkageError) {
        false
    }

    /**
     * The absolute path of the source file a class of this project is written in, found under the
     * test JVM's working directory (the module's) by its package and file name; the bare file name
     * if it is not there.
     */
    fun pathOf(className: String, fileName: String): String {
        val relative = className.substringBeforeLast('.').replace('.', '/') + "/" + fileName
        return sourceFiles.firstOrNull { it.invariantSeparatorsPath.endsWith("/$relative") }?.absolutePath ?: fileName
    }

    private fun pathOf(frame: StackTraceElement?): String =
        if (frame?.fileName == null) "unknown" else pathOf(frame.className, frame.fileName!!)

    private val sourceFiles: List<File> by lazy {
        File(System.getProperty("user.dir"), "src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private const val PROJECT_PACKAGE = "com.cartogenesis."
}

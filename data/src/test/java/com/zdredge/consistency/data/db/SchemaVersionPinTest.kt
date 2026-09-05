package com.zdredge.consistency.data.db

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * **Version 1 is pinned, and this is what makes the pin mean something.**
 *
 * Room stamps every schema with an identity hash and refuses to open a database whose stored hash
 * differs from the compiled one. So the dangerous change is not drift between the code and the
 * exported file -- the export regenerates on every build and those two cannot disagree -- but a
 * schema **changed without the version being bumped**. Both sides move together, every other test
 * stays green, and the failure surfaces as the app on a real phone refusing to open a database that
 * already holds history.
 *
 * That was verified rather than assumed. Adding a column to `ItemEntity` and leaving the version at
 * 1 left all sixty-one instrumented tests passing while the identity hash silently changed. So the
 * hash is recorded here. Changing the schema now fails this test, which is the prompt to do the
 * thing that was being skipped: bump the version, write the migration, then update the constant
 * deliberately.
 *
 * This reads the committed schema file directly rather than the copy packaged into the test APK,
 * and runs on the JVM with no device. Both are deliberate. The packaged copy can lag behind an
 * incremental build (see the note in `data/build.gradle.kts`), and a check that can read a stale
 * artifact is not a check. There is no SQLite involved here, so nothing is gained by putting it on
 * the device -- unlike the DAO and migration tests, which need the real thing.
 */
class SchemaVersionPinTest {

    @Test
    fun schemaVersion1IdentityHashIsUnchanged() {
        val schema = schemaFile(version = 1).readText()

        assertEquals(
            "Schema v1 changed. Room refuses to open an existing v1 database whose identity hash " +
                "no longer matches, so this needs a version bump and a migration -- not a new " +
                "constant here. Update the constant only once that is done.",
            V1_IDENTITY_HASH,
            schema.field("identityHash"),
        )
        assertEquals("1", schema.field("version"))
    }

    /**
     * A regex rather than a JSON parser: `org.json` is part of android.jar and is stubbed to throw
     * in JVM unit tests, and pulling in a parser to read two scalar fields would be a dependency
     * bought for nothing.
     */
    private fun String.field(name: String): String =
        Regex("\"$name\"\\s*:\\s*\"?([^\",\\s]+)\"?").find(this)?.groupValues?.get(1)
            ?: error("no \"$name\" in the exported schema")

    /**
     * A new schema file appearing is how a version bump shows up on disk. Failing here is not a
     * problem to route around -- it is the reminder that the new version needs a migration and a
     * migration test before it ships.
     */
    @Test
    fun onlyTheVersionsThisTestKnowsAboutHaveBeenExported() {
        val exported = schemaDir().listFiles()
            .orEmpty()
            .map { it.name }
            .filter { it.endsWith(".json") }
            .sorted()

        assertEquals(
            "a new exported schema means a new database version; it needs a migration and a " +
                "migration test, then this list updated",
            listOf("1.json"),
            exported,
        )
    }

    private fun schemaDir() = File("schemas/com.zdredge.consistency.data.db.ConsistencyDatabase")

    private fun schemaFile(version: Int) = File(schemaDir(), "$version.json").also {
        check(it.isFile) { "exported schema not found at ${it.absolutePath}" }
    }

    private companion object {
        /** Pinned when v1 was finished in M3. Read the test above before changing this. */
        const val V1_IDENTITY_HASH = "f80d6929ba81b4a2c4d2382989fd4b57"
    }
}

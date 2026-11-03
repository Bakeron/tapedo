package io.github.bakeron.tapedo.data.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Room rewrites `app/schemas/.../<version>.json` on every build from the current entities.
 * A schema change without a new database version would silently overwrite 1.json - and phones with the
 * released app would refuse to open the database. This test turns that into a red build.
 *
 * Changing the schema on purpose: raise `version` in [TapedoDatabase], add a migration (and its test),
 * then add the new version and hash here.
 */
class SchemaFrozenTest {

    private val schemaDir = File("schemas/io.github.bakeron.tapedo.data.db.TapedoDatabase")

    /** Version -> identityHash, as released. */
    private val released = mapOf(
        1 to "ef23b9d2e02b2c7a47f12931f9936b9c",
    )

    @Test
    fun `released schemas are unchanged and no new version appeared without a migration`() {
        val files = schemaDir.listFiles { f -> f.extension == "json" }.orEmpty()
        val versions = files.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals(
            "schema files in ${schemaDir.absolutePath} - a new version needs a migration and an entry here",
            released.keys.sorted(),
            versions,
        )
        for ((version, hash) in released) {
            val db = Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
                .jsonObject.getValue("database").jsonObject
            assertEquals(version, db.getValue("version").jsonPrimitive.int)
            assertEquals(
                "schema $version changed without a new version + migration",
                hash,
                db.getValue("identityHash").jsonPrimitive.content,
            )
        }
    }
}

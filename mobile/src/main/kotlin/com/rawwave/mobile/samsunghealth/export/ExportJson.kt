package com.rawwave.mobile.samsunghealth.export

import kotlinx.serialization.json.Json

/**
 * The two JSON dialects this export uses, kept in one place so every file is encoded the same way.
 */
object ExportJson {

    /**
     * One record per line.
     *
     * Compact on purpose: an all-history export can hold a very large number of records, and pretty
     * printing every one of them would inflate the archive for no benefit. Nulls are omitted here
     * only because every data type's own `fields` map always lists all of its fields explicitly, so
     * "Samsung supplied nothing for this field" is still visible rather than implied.
     */
    val records: Json = Json {
        prettyPrint = false
        explicitNulls = false
        encodeDefaults = false
    }

    /**
     * The documents: `manifest.json`, `catalog.json`, `export_semantics.json`.
     *
     * These are read by people and by models, so they are pretty-printed and nulls are written
     * explicitly - "Samsung did not supply this" and "our writer forgot this key" must never look
     * the same in the archive's own index.
     */
    val documents: Json = Json {
        prettyPrint = true
        explicitNulls = true
        encodeDefaults = true
    }
}

package com.dc2f

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import mu.KotlinLogging
import java.nio.file.*

private val logger = KotlinLogging.logger {}

class FileAssetDeserializer<T : BaseFileAsset>(vc: Class<T>, val producer: (path: ContentPath) -> T) : StdDeserializer<T>(vc) {

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        val value = _parseString(p, ctxt)
        val context = ctxt.findInjectableValue(ContentLoaderDeserializeContext::class.java, null, null) as ContentLoaderDeserializeContext
        // currently 'value' can only be in the current folder.
        val path = context.currentContentPath.resolve(value)
        logger.debug("Resolved $value to $path")
//        //OLD_TODO: very lazy here.. it should be somehow possible to resolve actual file from ContentPath
//        //   - fwiw, i think this is already solved. (by using `ContentPath.resolve` instead of `child`)
//
//        val fsPath = if (value.startsWith('/')) {
//            context.root.resolve(value.trimStart('/'))
//        } else {
//            context.currentFsPath.resolve(value)
//        }
//        if (!Files.exists(fsPath)) {
//            throw IllegalArgumentException("Unable to find asset $path - expected to find it at $fsPath.")
//        }
//        logger.debug { "Found file asset at $fsPath" }

        return producer(path)
    }

}

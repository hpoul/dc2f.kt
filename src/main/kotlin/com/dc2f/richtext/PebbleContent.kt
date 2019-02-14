package com.dc2f.richtext

import com.dc2f.*
import com.dc2f.render.RenderContext
import com.dc2f.util.*
import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.error.PebbleException
import com.mitchellbosecke.pebble.extension.AbstractExtension
import com.mitchellbosecke.pebble.extension.Function
import com.mitchellbosecke.pebble.extension.escaper.SafeString
import com.mitchellbosecke.pebble.template.*
import java.io.StringWriter
import java.nio.file.Path

data class PebbleContext(
    val node: ContentDef,
    val renderContext: RenderContext<*>,
    val arguments: Any?
) : HashMap<String, Any?>() {
    init {
        putAll(arrayOf("node" to node, "renderContext" to renderContext, "arguments" to arguments))
    }
}

class PebbleRenderExtension : AbstractExtension() {
    override fun getFunctions(): Map<String, Function> =
        mapOf(
            "render" to object : Function {
                override fun getArgumentNames(): List<String> =
                    listOf("content", "args")

                override fun execute(
                    args: MutableMap<String, Any>,
                    self: PebbleTemplate?,
                    context: EvaluationContext,
                    lineNumber: Int
                ): Any {
                    val renderContext = context.getVariable("renderContext") as RenderContext<*>
                    val content = args["content"]
                    return SafeString(render(content, renderContext))
                }

            }
        )

    fun render(content: Any?, renderContext: RenderContext<*>): String =
        when (content) {
            is Renderable -> content.renderContent(renderContext)
            is ContentReference -> render(content.referencedContent, renderContext)
            is ContentDef -> renderContext.renderNode(content)
            else -> throw IllegalArgumentException("Unable to render ${content?.toStringReflective()} (unknown type)")
        }
}

@PropertyType("peb")
class Pebble(
    val content: String,
    val path: Path,
    private val contentPath: ContentPath
) : RichText {

    companion object : Parsable<Pebble> {

        val engine: PebbleEngine by lazy {
            PebbleEngine.Builder()
                .extension(PebbleRenderExtension())
                .strictVariables(true)
                .cacheActive(false)
                .build()
        }

        override fun parseContent(
            context: LoaderContext,
            file: Path,
            contentPath: ContentPath
        ): Pebble =
            Pebble(file.readString(), file, contentPath)
    }

    override fun renderContent(renderContext: RenderContext<*>, arguments: Any?): String {
        val template = engine.getLiteralTemplate(content)
        val output = StringWriter()
        val node = requireNotNull(renderContext.renderer.loaderContext.contentByPath[contentPath.parent()])
        template.evaluate(output, PebbleContext(node, renderContext, arguments))
        return output.toString()
    }

}

package com.dc2f.richtext.markdown

import com.dc2f.*
import com.dc2f.render.RenderContext
import com.dc2f.richtext.RichText
import com.dc2f.richtext.RichTextContext
import com.dc2f.util.readString
import com.dc2f.util.substringBefore
import com.dc2f.util.toStringReflective
import com.vladsch.flexmark.ext.admonition.AdmonitionExtension
import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension
import com.vladsch.flexmark.ext.enumerated.reference.EnumeratedReferenceExtension
import com.vladsch.flexmark.ext.toc.TocExtension
import com.vladsch.flexmark.ext.typographic.TypographicExtension
import com.vladsch.flexmark.ext.xwiki.macros.Macro
import com.vladsch.flexmark.ext.xwiki.macros.MacroBlock
import com.vladsch.flexmark.ext.xwiki.macros.MacroExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.html.IndependentLinkResolverFactory
import com.vladsch.flexmark.html.LinkResolver
import com.vladsch.flexmark.html.renderer.*
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Document
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.DataHolder
import com.vladsch.flexmark.util.data.MutableDataHolder
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.util.data.NullableDataKey
import com.vladsch.flexmark.util.html.Attributes
import mu.KotlinLogging
import org.apache.commons.beanutils.PropertyUtils
import org.springframework.expression.spel.standard.SpelExpressionParser
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

object MarkdownDc2fExtension : HtmlRenderer.HtmlRendererExtension {
    override fun extend(rendererBuilder: HtmlRenderer.Builder, rendererType: String) {
        rendererBuilder.nodeRendererFactory { options -> MarkdownMacroRenderer(options) }
        rendererBuilder.linkResolverFactory(object : IndependentLinkResolverFactory() {
            override fun apply(context: LinkResolverBasicContext): LinkResolver =
                    Dc2fLinkResolver(context)
        })

    }

    override fun rendererOptions(options: MutableDataHolder) {
    }
}

interface ValidationRequired {
    fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String?
}

//typealias ValidationRequiredLambda = (context: LoaderContext) -> String

//val VALIDATORS = DataKey<MutableList<ValidationRequiredLambda>>("VALIDATORS") { mutableListOf() }
val LOADER_CONTEXT = NullableDataKey<LoaderContext>("LOADER_CONTEXT", null)
val PARENT = NullableDataKey<ObjectDef?>("PARENT", null as ObjectDef?)
val RENDER_CONTEXT = NullableDataKey<RenderContext<*>>("RENDER_CONTEXT", null as RenderContext<*>?)

class ValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class Dc2fLinkResolver(val context: LinkResolverBasicContext): LinkResolver {
    override fun resolveLink(
        node: Node,
        context: LinkResolverBasicContext,
        link: ResolvedLink
    ): ResolvedLink {
//        context.document?.get(VALIDATORS)?.add { context ->
////            logger.info { "Validating stuff." }
////            ""
////        }
        try {
            if (link.url == "TOC" || link.url.startsWith("TOC ")) {
                logger.debug { "Found TOC. ignoring." }
                return link
            }
            if (link.url.startsWith('@') || (!link.url.contains("://") && !link.url.contains("@"))) {
                // validate internal link.
                val loaderContext = requireNotNull(LOADER_CONTEXT[context.options])
                val renderContext = RENDER_CONTEXT[context.options]
                val parent = PARENT[context.options]

                if (link.url.startsWith('@')) {
                    if (renderContext == null) {
                        require(loaderContext.phase == LoaderContext.LoaderPhase.Validating)
                        return link
                    }
                    val obj = PropertyUtils.getNestedProperty(RichTextContext(renderContext.node, loaderContext, renderContext, null), link.url.substring(1))
//                    val obj = BeanUtil.pojo.getProperty<Any>(RichTextContext(renderContext.node, loaderContext, renderContext, null), link.url.substring(1))
                    return link.withUrl(RichText.render(obj, renderContext)).withLinkType(LinkType.LINK).withStatus(
                        LinkStatus.VALID)
                }

                val parentContentPath = parent?.let { loaderContext.findContentPath(it) }

                val linkedContent = loaderContext.contentByPath[parentContentPath?.resolve(link.url) ?: ContentPath.parse(link.url)]
                    ?: throw ValidationException("Invalid link to {${link.url}}: ${link.toStringReflective()}")

                return renderContext?.let { //renderContext ->
                    val l = link.withStatus(LinkStatus.VALID)
                        .withUrl(renderContext.href(linkedContent))
                        .withTitle(renderContext.theme.renderLinkTitle(linkedContent))
                    // crazy workaround to get the "title" attribute into the rendered output.
                    // (CoreNodeRenderer#858 / render(Link node, NodeRendererContext context, HtmlWriter html))
                    object : ResolvedLink(l.linkType, l.url, l.attributes, l.status) {
                        override fun getNonNullAttributes(): Attributes {
                            return Attributes(l.attributes)
                        }

                        override fun getAttributes(): Attributes {
                            return nonNullAttributes
                        }
                    }
                } ?: link
            }
        } catch (e: ValidationException) {
            logger.error(e) { "temporarily disabled link errors." }
//            return link
            throw ValidationException("Invalid link (malformed URL): ${link.toStringReflective()}, ${e.message}", e)
        }
        logger.debug { "We need to resolve link ${link.toStringReflective()} for $node" }
        // absolute links are kept as they are...
        return link
    }

}


/*
TODO the markdown renderer has quite a few problems
 - {{ render /}} won't render at all (space after {{)
 - {{ render content=test-test }} .. no '-' allowed in attribute values? (maybe makes sense?)
 - generally it does not fail for broken macros.
 - it should be easier to reference content.
 */
class MarkdownMacroRenderer(@Suppress("UNUSED_PARAMETER") options: DataHolder) : NodeRenderer {

    companion object {
        val expressionParser by lazy { SpelExpressionParser() }
    }

    fun render(param: Macro, nodeRendererContext: NodeRendererContext, html: HtmlWriter) {
        logger.debug { "Rendering ${param.name} with: ${param.attributeText}" }

        @Suppress("ReplaceCallWithBinaryOperator")
        if (!param.name.equals("render")) {
            logger.debug { "Unsupported macro ${param.name}" }
            throw IllegalArgumentException("Unsupported macro in markdown context. ${param.name}")
        }

        val loaderContext = requireNotNull(LOADER_CONTEXT[nodeRendererContext.options])

        if (!loaderContext.phase.isAfter(LoaderContext.LoaderPhase.Validating)) {
            // we are in validation step. do not do anything yet.
            // TODO validate content.
            return
        }

        val renderContext = requireNotNull(RENDER_CONTEXT[nodeRendererContext.options])
        val context = RichTextContext(renderContext.node, renderContext.renderer.loaderContext, renderContext, null)
        val contentPath = param.attributes["content"]
        val arguments = param.attributes["arguments"]?.let { str ->
            val expr = expressionParser.parseExpression(str)
            expr.getValue(context)
        }

//        PropertyUtilsBean
        val result = PropertyUtils.getNestedProperty(context, contentPath)
//        val result: Any = BeanUtil.pojo.getProperty(context, contentPath)
        html.rawPre(RichText.render(result, renderContext, arguments))
    }

    override fun getNodeRenderingHandlers(): MutableSet<NodeRenderingHandler<*>> =
        mutableSetOf(
            NodeRenderingHandler(Macro::class.java, ::render),
            NodeRenderingHandler(MacroBlock::class.java) { param: MacroBlock, nodeRendererContext: NodeRendererContext, htmlWriter: HtmlWriter ->
                if (!param.isClosedTag) {
                    throw IllegalArgumentException("Tag must be closed. Got: ${param.chars}")
                }
                if (!param.macroContentChars.isNullOrBlank()) {
                    throw IllegalArgumentException("Tag content must be empty, because it is ignored. contains: ${param.chars}")
                }
                render(param.macroNode, nodeRendererContext, htmlWriter)
            }
        )

}

@PropertyType("md")
class Markdown(private val content: String) : ParsableObjectDef, RichText, ValidationRequired {

    companion object : Parsable<Markdown> {
        override fun parseContent(
            context: LoaderContext,
            file: Path,
            contentPath: ContentPath
        ): Markdown =
            parseContentString(file.readString())

        fun parseContentString(str: String): Markdown {
            return Markdown(str)
        }

        val options by lazy {
            MutableDataSet()
//                .set(MacroExtension.ENABLE_RENDERING, true)
                .set(MacroExtension.ENABLE_INLINE_MACROS, true)
                .set(MacroExtension.ENABLE_BLOCK_MACROS, true)
                .set(HtmlRenderer.GENERATE_HEADER_ID, true)
                .set(AnchorLinkExtension.ANCHORLINKS_ANCHOR_CLASS, "md-anchor")
                .set(AnchorLinkExtension.ANCHORLINKS_SET_ID, false)
                .set(TocExtension.LEVELS, 1 or 2 or 4)
                .set(TocExtension.IS_NUMBERED, true)
                .set(TocExtension.LIST_CLASS, "md-toc")
                .set(TocExtension.DIV_CLASS, "md-toc-div")
                .set(Parser.EXTENSIONS, listOf(
                    MacroExtension.create(),
                    MarkdownDc2fExtension,
                    AnchorLinkExtension.create(),
                    TocExtension.create(),
                    TypographicExtension.create(),
                    EnumeratedReferenceExtension.create(),
                    AdmonitionExtension.create()
                    ))
        }

        val parser: Parser by lazy {
            Parser.builder(
                options
            ).build()
        }
    }

    val rawContent get() = content
    override fun rawContent(): String = content

    private fun parsedContent(context: LoaderContext, content: String = this.content): Document {
        context.phase.requireAfter(LoaderContext.LoaderPhase.Loading)
        return parser.parse(content)
    }

    private fun renderer(renderContext: RenderContext<*>?, loaderContext: LoaderContext, asInlineContent: Boolean = false) =
        HtmlRenderer.builder(
            MutableDataSet(options)
                .set(LOADER_CONTEXT, loaderContext)
                .set(PARENT, renderContext?.node)
                .set(RENDER_CONTEXT, renderContext)
                .set(HtmlRenderer.NO_P_TAGS_USE_BR, asInlineContent)
        ).build()

    fun renderedContent(context: RenderContext<*>, content: String = this.content, asInlineContent: Boolean = false): String {
        val doc = parsedContent(context.renderer.loaderContext, content = content)

        return renderer(
            context,
            context.renderer.loaderContext,
            asInlineContent = asInlineContent
        ).render(doc)
            .let { html ->
                if (asInlineContent) {
                    html.trim().let {
                        require(it.endsWith("<br /><br />"))
                        it.substring(0, it.length - "<br /><br />".length)
                    }
                } else {
                    html.takeIf { html.contains("<p>") }
                        ?: "<p>$html</p>"
                }
            }
    }

    override fun renderContent(renderContext: RenderContext<*>, arguments: Any?): String =
        renderedContent(renderContext, this.content)

    fun summary(context: RenderContext<*>): String {
        val summarySource = content.substringBefore("<!--more-->") {
            content.split(Regex("""\r\n\r\n|\n\n|\r\r"""), 2).first()
        }
        return renderedContent(context, content = summarySource)
    }


    override fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String? {
        return try {
            HtmlRenderer.builder(
                MutableDataSet(options)
                    .set(LOADER_CONTEXT, loaderContext)
                    .set(PARENT, parent.content)
            ).build()
                .render(parsedContent(loaderContext))
            null
        } catch (e: ValidationException) {
            e.message
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Error while parsing content." }
            e.message
        }
    }
}


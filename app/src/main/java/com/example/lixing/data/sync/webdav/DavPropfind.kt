package com.example.lixing.data.sync.webdav

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV 目录条目。 */
data class DavEntry(
    /** 服务器返回的原始 href（可能是绝对 URL，也可能是路径）。 */
    val href: String,
    /** 末段名字；目录已去掉结尾斜杠。 */
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
)

/**
 * PROPFIND 的 207 Multi-Status 响应解析。
 *
 * 用 `javax.xml` 而不依赖 Android 的 XmlPullParser，这样同一份代码
 * 在真机和 JVM 单测里走的是同一条路径。
 */
internal object DavPropfind {

    const val NAMESPACE = "DAV:"

    /** Depth: 1 的目录列举请求体，只取大小 / 修改时间 / 资源类型。 */
    const val REQUEST_BODY =
        """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop><D:getcontentlength/><D:getlastmodified/><D:resourcetype/></D:prop></D:propfind>"""

    fun parse(xml: String): List<DavEntry> {
        if (xml.isBlank()) return emptyList()
        val document = runCatching {
            factory().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrElse {
            throw WebDavException(WebDavException.Kind.PROTOCOL, "服务器返回的目录列表无法解析（不是合法 XML）")
        }
        document.documentElement.normalize()

        val responses = document.getElementsByTagNameNS(NAMESPACE, "response")
        val entries = mutableListOf<DavEntry>()
        for (i in 0 until responses.length) {
            val node = responses.item(i) as? Element ?: continue
            val href = node.child(NAMESPACE, "href")?.textContent?.trim().orEmpty()
            if (href.isEmpty()) continue

            // 属性可能分散在多个 propstat 里，只认 HTTP 200 的那一个
            val prop = node.children(NAMESPACE, "propstat")
                .firstOrNull { it.child(NAMESPACE, "status")?.textContent.orEmpty().contains("200") }
                ?.child(NAMESPACE, "prop")
                ?: node.children(NAMESPACE, "propstat")
                    .mapNotNull { it.child(NAMESPACE, "prop") }
                    .firstOrNull()

            val size = prop?.child(NAMESPACE, "getcontentlength")
                ?.textContent?.trim()?.toLongOrNull() ?: 0L
            val isDirectory = prop?.child(NAMESPACE, "resourcetype")
                ?.child(NAMESPACE, "collection") != null

            val name = entryName(href)
            if (name.isEmpty()) continue
            entries += DavEntry(href = href, name = name, sizeBytes = size, isDirectory = isDirectory)
        }
        return entries
    }

    private fun entryName(href: String): String {
        val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        return decoded.substringBefore('?').trimEnd('/').substringAfterLast('/')
    }

    private fun Element.child(namespace: String, localName: String): Element? =
        children(namespace, localName).firstOrNull()

    private fun Element.children(namespace: String, localName: String): List<Element> {
        val nodes: NodeList = getElementsByTagNameNS(namespace, localName)
        val result = mutableListOf<Element>()
        for (i in 0 until nodes.length) {
            val node: Node = nodes.item(i)
            // getElementsByTagNameNS 会递归到 propstat 内部，这里只保留直接子元素
            if (node is Element && node.parentNode === this) result += node
        }
        return result
    }

    private fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // 关掉外部实体，避免 XXE
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isXIncludeAware = false }
        runCatching { isExpandEntityReferences = false }
    }
}

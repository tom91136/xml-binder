//> using target.platform js
//> using jsDom
package example

import net.kurobako.xmlbinder.*
import org.scalajs.dom

object jsdom {
  given XmlBackend[dom.Element] = new XmlBackend[dom.Element] {

    def loadString(xml: String): dom.Element = {
      val parser = new dom.DOMParser()
      val doc    = parser.parseFromString(xml, dom.MIMEType.`text/xml`)
      doc.documentElement
    }

    def source(e: dom.Element): String = {
      val ser = new dom.XMLSerializer()
      ser.serializeToString(e)
    }

    def label(e: dom.Element): String = Option(e.localName).getOrElse(e.tagName)

    def text(e: dom.Element): String = e.textContent

    def ownText(e: dom.Element): String = {
      val sb    = StringBuilder()
      val nodes = e.childNodes
      var i     = 0
      while (i < nodes.length) {
        val n = nodes(i)
        if (n.nodeType == dom.Node.TEXT_NODE || n.nodeType == dom.Node.CDATA_SECTION_NODE)
          sb.append(n.textContent)
        i += 1
      }
      sb.result()
    }

    def children(e: dom.Element): Seq[dom.Element] = {
      val out   = scala.collection.mutable.ArrayBuffer.empty[dom.Element]
      val nodes = e.childNodes
      var i     = 0
      while (i < nodes.length) {
        val n = nodes(i)
        if (n.nodeType == dom.Node.ELEMENT_NODE) out += n.asInstanceOf[dom.Element]
        i += 1
      }
      out.toSeq
    }

    def attributes(e: dom.Element): Map[String, String] = {
      val m   = e.attributes
      val out = scala.collection.mutable.Map.empty[String, String]
      var i   = 0
      while (i < m.length) {
        val a = m(i)
        if (a.namespaceURI != "http://www.w3.org/2000/xmlns/" && a.name != "xmlns" && !Option(a.prefix).contains("xmlns"))
          out += (a.name -> a.value)
        i += 1
      }
      out.toMap
    }

    def attribute(e: dom.Element, local: String): Option[String] =
      Option(e.attributes.getNamedItem(local))
        .filter(a => a.namespaceURI != "http://www.w3.org/2000/xmlns/")
        .map(_.value)

    def attributeQName(e: dom.Element, qname: String): Option[String] =
      Option(e.attributes.getNamedItem(qname))
        .filter(a => a.namespaceURI != "http://www.w3.org/2000/xmlns/")
        .map(_.value)

    def attributeNS(e: dom.Element, uri: String, local: String): Option[String] =
      if (uri == "http://www.w3.org/2000/xmlns/") None
      else Option(e.getAttributeNS(uri, local)).filter(_.nonEmpty)

    def resolvePrefix(e: dom.Element, prefix: String): Option[String] =
      Option(e.lookupNamespaceURI(prefix))

    def originalQName(e: dom.Element, uri: String, local: String): Option[String] = {
      val m = e.attributes
      var i = 0
      while (i < m.length) {
        val a = m(i)
        val ok =
          if (uri == null) a.namespaceURI == null && a.localName == local
          else a.namespaceURI == uri && a.localName == local
        if (ok) return Option(a.name)
        i += 1
      }
      None
    }
  }
}

import jsdom.given

class JsDomSuite extends Tests[dom.Element] {}

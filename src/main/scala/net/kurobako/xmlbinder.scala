package net.kurobako

import scala.annotation.StaticAnnotation
import scala.annotation.implicitNotFound
import scala.annotation.tailrec
import scala.deriving.Mirror

object xmlbinder {

  /** Thrown when binding is unsuccessful.
    *
    * @param reason
    *   the reason of the failure
    * @param elem
    *   the specific element the failure occurred on
    */
  class XmlBindingException(reason: String, elem: String) extends Exception(s"$reason (while binding `${elem}`)")

  /** Typeclass for converting a `String` into an instance of `A`.
    *
    * @example
    *   {{{
    *  case class Bounds(left: Int, top: Int, right: Int, bottom: Int)
    *  case class Foo(b : Bounds) derives XmlBinding
    *
    *  given XmlMappable[Bounds] = {
    *    case s"[$l,$t][$b,$r]" => (for {
    *       l <- l.toIntOption
    *       t <- t.toIntOption
    *       b <- b.toIntOption
    *       r <- r.toIntOption
    *     } yield Bounds(l, t, b, r)).toRight("Can't parse bound components")
    *    case bad => Left(s"Can't parse $bad")
    *  }
    *
    *  XmlBinding.string[Node]("<foo b= \"[1,2][3,4]\"/>") ==
    *  Foo(Bounds(1, 2, 3, 4)) // true
    *   }}}
    * @tparam A
    *   the target type that can be parsed from any XML text (attributes, or other bindings such as [[tagName]] and
    *   [[text]] )
    */
  @implicitNotFound(
    "No given instance of XmlMappable[${A}] was found, to continue, either:\n" +
      "  a) Implement the mapping: `given XmlMappable[${A}] = { Right(???: ${A}) }`\n" +
      "  b) Construct from an existing one `given XmlMappable[${A}] = XmlMappable[String].map[${A}](s => ???)`\n\n"
  )
  trait XmlMappable[A] { self =>

    /** Attempt to parse the given string into an `A`.
      *
      * @param s
      *   the raw XML content as `String`
      * @return
      *   `Right(a)` if parsing succeeds, or `Left(errorMsg)` on failure
      */
    def parse(s: String): Either[String, A]

    /** Transform parsed values of this instance into another type.
      *
      * @param f
      *   function mapping `A` to `B`
      * @tparam B
      *   the resulting type after mapping
      * @return
      *   a new `XmlMappable[B]` that applies `f` to successful parses
      */
    def map[B](f: A => B): XmlMappable[B] = new XmlMappable[B] {
      def parse(s: String): Either[String, B] = self.parse(s).map(f)
    }
  }

  object XmlMappable {

    /** Summon the available `XmlMappable[A]`.
      *
      * @tparam A
      *   type for which an instance must be in implicit scope
      * @return
      *   the implicit `XmlMappable[A]`
      */
    def apply[A: XmlMappable]: XmlMappable[A] = summon[XmlMappable[A]]
  }

  /** Bind a specific XML attribute to this field. This is intended as a one‑off for awkward or exceptional attribute
    * names that you do **not** want to have global attribute‑name mapping applied.
    *
    * Namespaced XML attributes are complicated, this annotation supports the following patterns:
    *
    *   - **Unprefixed:** `"attr"` - matches an unqualified attribute `attr`.
    *   - **Prefixed (prefix + local):** `"ns:attr"` - matches the attribute with that qualified name, using whatever
    *     namespace binding the element has for the prefix `ns` at that point in the document.
    *   - **URI literal (prefix‑agnostic):** `"{uri}attr"` or `"@{uri}attr"` - matches by **namespace URI** + **local
    *     name**, regardless of the prefix actually used in the XML (useful if you care about the namespace but cannot
    *     rely on a specific prefix).
    *
    * @param name
    *   the attribute name to bind, accepts `"attr"`, `"ns:attr"`, `"{uri}attr"`, or `"@{uri}attr"` as described above
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *
    *  // Unprefixed
    *  case class A(@attrName("a") v: String) derives XmlBinding
    *  XmlBinding.string[A]("<x a=\"v\"/>") == A("v")
    *
    *  // Prefixed (prefix must be bound in the element's scope)
    *  case class B(@attrName("ns:a") v: String) derives XmlBinding
    *  XmlBinding.string[B]("<x xmlns:ns=\"urn:x\" ns:a=\"v\"/>") == B("v")
    *
    *  // URI literal (prefix‑agnostic)
    *  case class C(@attrName("{urn:x}a") v: String) derives XmlBinding
    *  XmlBinding.string[C]("<x xmlns:p=\"urn:x\" p:a=\"v\"/>") == C("v")
    *
    *  // With extras: bound attributes are not included in extras
    *  case class D(@attrName("{urn:x}a") v: String, @extras rest: Map[String,String]) derives XmlBinding
    *  XmlBinding.string[D]("<x xmlns:p=\"urn:x\" p:a=\"v\" b=\"1\"/>") == D("v", Map("b" -> "1"))
    *   }}}
    */
  case class attrName(name: String) extends StaticAnnotation

  /** Bind the XML tag name to this field.
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  case class Node(
    *    @tagName val tag: String,
    *    val id: Int
    *  ) derives XmlBinding
    *
    *  XmlBinding.string[Node]("<person id=\"123\"/>") == Node("person", 123) // true
    *   }}}
    */
  class tagName extends StaticAnnotation

  /** Bind any extra XML attributes not mapped to fields into a Map. The original attribute names will be used as keys,
    * without any global name transformations.
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  case class Node(
    *    id: Int,
    *    @extras val rest: Map[String, String]
    *  ) derives XmlBinding
    *
    *  XmlBinding.string[Elem, Node]("<person id=\"1\" foo=\"bar\"/>") == Node(1, Map("foo" -> "bar")) // true
    *   }}}
    */
  class extras extends StaticAnnotation

  /** Bind the concrete XML node type from the backend. This annotation will inject the raw element value provided by
    * the active [[XmlBackend]] into the annotated field **without** conversion.
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  import scala.xml.Elem
    *  import scala.xml.XML
    *
    *  case class Node(
    *    @repr val elem: Elem,
    *    val id: Int
    *  ) derives XmlBinding
    *
    *  val elem = XML.loadString("<person id=\"123\"/>")
    *  XmlBinding.elem[Elem, Node](elem) == Node(elem, 123) // true
    *   }}}
    */
  class repr extends StaticAnnotation

  /** Bind the entire _text_ (i.e. text representation, **including** text from children) content of an XML element to
    * this field.
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  case class Message(
    *    @text val content: String
    *  ) derives XmlBinding
    *
    *  XmlBinding.string[Message]("<message>Hello<bold>World</bold></message>") == Message("HelloWorld") // true
    *   }}}
    */
  class text extends StaticAnnotation

  /** Bind the node's own _text_ (i.e. text representation, **excluding** children nodes) content of an XML element to
    * this field.
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  case class Message(
    *    @ownText val content: String
    *  ) derives XmlBinding
    *
    *  XmlBinding.string[Message]("<message>Hello<bold>World</bold></message>") == Message("Hello") // true
    *   }}}
    */
  class ownText extends StaticAnnotation

  /** Bind all child _elements_ (excluding text) of an XML node to this field.
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  case class Item(@tagName val name: String) derives XmlBinding
    *  case class Parent(@children val items: Seq[Item]) derives XmlBinding
    *
    *  XmlBinding.string[Parent](
    *    "<parent><item>one</item><item>two</item></parent>"
    *  ) == Parent(Seq(Item("one"), Item("two"))) // true
    *   }}}
    */
  class children extends StaticAnnotation

  /** For sum types: match one or more XML tag names to this case.
    *
    * @param name
    *   allowed XML tag names
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  enum Shape derives XmlBinding {
    *    @matchTag("circle") case Circle(r: Int)
    *    @matchTag("rect") case Rectangle(w: Int, h: Int)
    *  }
    *
    *  XmlBinding.string[Shape]("<circle r=\"5\"/>") == Shape.Circle(5) // true
    *  XmlBinding.string[Shape]("<rect w=\"1\" h=\"2\"/>") == Shape.Rectangle(1, 2) // true
    *   }}}
    */
  case class matchTag(name: String*) extends StaticAnnotation

  /** For sum types: match one or more tags containing attribute names to this case.
    *
    * @param name
    *   allowed XML attribute names
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  enum Shape derives XmlBinding {
    *    @matchAttr("r") case Circle(r: Int)
    *    @matchAttr("w", "h") case Rectangle(w: Int, h: Int)
    *  }
    *
    *  XmlBinding.string[Shape]("<circle r=\"5\"/>") == Shape.Circle(5) // true
    *  XmlBinding.string[Shape]("<rect w=\"1\" h=\"2\"/>") == Shape.Rectangle(1, 2) // true
    *   }}}
    */
  case class matchAttr(name: String*) extends StaticAnnotation

  /** For sum types: match any tag to this case
    *
    * @example
    *   {{{
    *  import net.kurobako.xmlbinder.*
    *  enum Shape derives XmlBinding {
    *    @matchAttr("r") case Circle(r: Int)
    *    @matchAny case Rectangle(w: Int, h: Int)
    *  }
    *
    *  XmlBinding.string[Shape]("<circle r=\"5\"/>") == Shape.Circle(5) // true
    *  XmlBinding.string[Shape]("<rect w=\"1\" h=\"1\"/>") == Shape.Rectangle(1, 2) // true
    *   }}}
    */
  class matchAny extends StaticAnnotation

  /** Materialised values of the various kinds of field mappings. An instance of this type is generated at compile time
    * by reading the field annotations.
    */
  enum FieldMapping {
    case TagName, Extras, Text, OwnText, Children, Repr
    case AttrName(name: String)
  }

  /** Materialised values of the various kinds of type mappings. An instance of this type is generated at compile time
    * by reading the type annotations.
    */
  enum TypeMapping {
    case MatchTag(name: Set[String])
    case MatchAttr(name: Set[String])
    case MatchAny
  }

  /** Materialised values of all type and field mappings. An instance of this type is generated at compile time for sum
    * and product types.
    *
    * Alternatively, you may also construct an instance yourself.
    */
  case class Mapping(fields: Map[String, FieldMapping], types: List[TypeMapping])

  /** Typeclass for binding an XML element to an instance of `A`.
    */
  trait XmlBinding[A] {

    /** Construct an `A` from an XML element.
      * @param xml
      *   the source XML node
      * @param attrNameToFieldName
      *   function mapping XML attribute names to Scala field names, for example, from kebab-case to camelCase
      * @param mapping
      *   metadata for field‑to‑XML mapping; a mapping is generated from a macro at compile time, however may also
      *   supply your own Mapping instance to implement custom bindings
      */
    def apply[E](xml: E, attrNameToFieldName: String => String, mapping: Mapping)(using XmlBackend[E]): A
  }

  trait XmlBackend[E] {

    /** Parse a raw XML string into an element of E (used by XmlBinding.string). */
    def loadString(xml: String): E

    def source(e: E): String

    /** Tag local name (label). */
    def label(e: E): String

    /** Full text (including children). */
    def text(e: E): String

    /** Own/direct text, excluding children. */
    def ownText(e: E): String

    /** Child elements (elements only, no text nodes). */
    def children(e: E): Seq[E]

    /** All attributes as a Map using the original (as-appears) qualified names as keys. */
    def attributes(e: E): Map[String, String]

    /** Unprefixed attribute by local name. */
    def attribute(e: E, local: String): Option[String]

    /** Attribute by prefixed qualified name, e.g. "ns:attr". */
    def attributeQName(e: E, qname: String): Option[String]

    /** Attribute by namespace URI + local name. */
    def attributeNS(e: E, uri: String, local: String): Option[String]

    /** Resolve a prefix to its namespace URI in the context of this element. */
    def resolvePrefix(e: E, prefix: String): Option[String]

    /** Best-effort original qualified name for (uri, local) as written in the source. */
    def originalQName(e: E, uri: String, local: String): Option[String]
  }

  object XmlBinding {

    import scala.annotation.nowarn
    import scala.collection.mutable
    import scala.compiletime.*

    private inline def debugAttrsNames[E](inline elem: E)(using b: XmlBackend[E]) =
      b.attributes(elem).keySet.toList.sorted.map(x => s"\"$x\"")

    protected class State[E](
        val elem: E,
        val attrNameToFieldName: String => String,
        val mapping: Mapping,
        val fields: Set[String]
    )(using val b: XmlBackend[E]) {

      import FieldMapping.*

      private inline def raise[C, A](inline field: String, inline value: String): Nothing = {
        val tpe = Macro.typeName[A]
        throw XmlBindingException(
          s"Cannot parse `$value` into $tpe for `${constValue[C]}.$field: $tpe`",
          b.source(elem)
        )
      }

      private inline def missing[C, A](inline field: String): Nothing = {
        val attrs      = debugAttrsNames(elem)
        val attrsInfo  = if (attrs.isEmpty) "tag contains no attributes" else s"tag contains ${attrs.mkString(", ")}"
        val mappedInfo =
          if (attrNameToFieldName(field) == field) ""
          else s" (original: `${constValue[C]}.$field`)"
        throw XmlBindingException(
          s"Missing mandatory field `${constValue[C]}.${attrNameToFieldName(field)}: ${Macro.typeName[A]}`$mappedInfo; ${attrsInfo}",
          b.source(elem)
        )
      }

      private def attrValueNS(raw: String): Option[String] = raw match {
        case s"$p{$uri}$local" if p == "" || p == "@" => // prefix-agnostic via (uri, local)
          b.attributeNS(elem, uri, local)
        case qualified @ s"$prefix:$local" => // try prefix->URI first, then exact qname
          b.resolvePrefix(elem, prefix).flatMap(u => b.attributeNS(elem, u, local))
            .orElse(b.attributeQName(elem, qualified))
        case unprefixed => b.attribute(elem, unprefixed)
      }

      private lazy val attrsOriginal: Map[String, String]         = b.attributes(elem)
      private lazy val attrsMapped: Map[String, (String, String)] =
        attrsOriginal.map((k, v) => attrNameToFieldName(k) -> (k, v))

      private inline def mappedAttr(inline field: String): Option[String] =
        mapping.fields.get(field) match {
          case Some(FieldMapping.AttrName(name)) => attrValueNS(name)
          case _                                 => attrsMapped.get(field).map(_._2)
        }

      inline def extras: Map[String, String] = {
        val boundKeys: Set[String] = fields.map { field =>
          mapping.fields.get(field) match {
            case Some(FieldMapping.AttrName(name)) =>
              attrNameToFieldName(name match {
                case s"$p{$uri}$local" if p == "" || p == "@" =>
                  b.originalQName(elem, uri, local).getOrElse(name)
                case _ => name
              })
            case _ => field
          }
        }
        (attrsMapped -- boundKeys).map { case (_, (kOrig, v)) => kOrig -> v }
      }

      inline def fieldKind(inline field: String, inline fm: FieldMapping*) =
        fm.exists(mapping.fields.get(field).contains(_))

      inline def headOrRaise[C, A, Col](inline field: String) = b.children(elem) match {
        case Nil      => None.asInstanceOf[Col]
        case x :: Nil =>
          Some(summonInline[XmlBinding[A]](x, attrNameToFieldName, Macro.mapping[A])).asInstanceOf[Col]
        case xs =>
          throw XmlBindingException(
            s"Expected at most one child element for ${constValue[C]}.$field: ${Macro.typeName[A]}, found ${xs.size}",
            b.source(elem)
          )
      }

      inline def maybeChild[C, A, Col](inline field: String): Col = summonFrom {
        case given XmlBinding[A] =>
          if (fieldKind(field, FieldMapping.Children)) headOrRaise[C, A, Col](field)
          else if (fieldKind(field, FieldMapping.Repr)) Some(elem).asInstanceOf[Col]
          else attr[C, Col](field)
        case _ =>
          if (fieldKind(field, FieldMapping.Children)) throw AssertionError()
          else if (fieldKind(field, FieldMapping.Repr)) Some(elem).asInstanceOf[Col]
          else attr[C, Col](field)
      }

      inline def collectChildren[C, A, Col](using cbf: collection.Factory[A, Col]) = b.children(elem)
        .map(c => summonInline[XmlBinding[A]](c, attrNameToFieldName, Macro.mapping[A])).to(cbf)

      inline def children[C, A, Col](inline field: String)(using cbf: collection.Factory[A, Col]): Col = summonFrom {
        case given XmlMappable[A] =>
          if (fieldKind(field, FieldMapping.Children)) collectChildren[C, A, Col]
          else attr[C, A](field).asInstanceOf[Col]
        case _ =>
          if (fieldKind(field, FieldMapping.Children)) collectChildren[C, A, Col]
          else throw AssertionError()
      }

      inline def str[C, A](inline attr: Option[String])(inline field: String): A =
        attr match {
          case None    => missing[C, A](field)
          case Some(x) =>
            summonFrom {
              case m: XmlMappable[A] => m.parse(x) match {
                  case Right(v) => v
                  case Left(_)  => raise[C, A](field, x)
                }
              case _ => throw AssertionError()
            }
        }

      inline def attr[C, A](inline field: String): A = {
        def raw: Option[String] =
          if (fieldKind(field, FieldMapping.Text)) Some(b.text(elem)).filter(_.nonEmpty)
          else if (fieldKind(field, FieldMapping.OwnText)) Some(b.ownText(elem))
          else if (fieldKind(field, FieldMapping.TagName)) Some(b.label(elem)).filter(_.nonEmpty)
          else mappedAttr(field)

        inline erasedValue[A] match {

          case _: String =>
            if (fieldKind(field, FieldMapping.TagName)) b.label(elem).asInstanceOf[A]
            else if (fieldKind(field, FieldMapping.Text)) b.text(elem).asInstanceOf[A]
            else if (fieldKind(field, FieldMapping.OwnText)) b.ownText(elem).asInstanceOf[A]
            else mappedAttr(field) match {
              case Some(x) => x.asInstanceOf[A]
              case None    => missing[C, A](field)
            }
          case _: Option[t] =>
            val result: Option[t] = raw.map { x =>
              type T = t
              summonFrom {
                case m: XmlMappable[T] => m.parse(x) match {
                    case Right(v) => v: t
                    case Left(_)  => raise[C, A](field, x)
                  }
                case _ => throw AssertionError()
              }
            }
            result.asInstanceOf[A]
          case _ =>
            summonFrom {
              case _: =:=[A, E] =>
                if (fieldKind(field, FieldMapping.Repr)) elem.asInstanceOf[A]
                else str[C, A](raw)(field)
              case _ => str[C, A](raw)(field)
            }
        }
      }
    }

    private given arrayFactory[A](using ct: reflect.ClassTag[A]): collection.Factory[A, Array[A]] =
      new collection.Factory[A, Array[A]] {
        def newBuilder: mutable.Builder[A, Array[A]]    = mutable.ArrayBuilder.make[A]
        def fromSpecific(it: IterableOnce[A]): Array[A] = mutable.ArrayBuilder.make[A].addAll(it).result()
      }

    private inline def bindAttr[E, C, L, A](inline state: State[E]): Any = {
      import FieldMapping.*
      inline (erasedValue[L], erasedValue[A]) match {
        case (field: String, _: Map[String, String]) =>
          summonFrom {
            case given XmlMappable[A] =>
              if (state.fieldKind(field, Extras)) state.extras
              else state.attr[C, A](field)
            case _ =>
              if (state.fieldKind(field, Extras)) state.extras
              else throw AssertionError()
          }
        case (field: String, _: Option[t])     => state.maybeChild[C, t, Option[t]](field)
        case (field: String, _: Vector[t])     => state.children[C, t, Vector[t]](field)
        case (field: String, _: List[t])       => state.children[C, t, List[t]](field)
        case (field: String, _: IndexedSeq[t]) => state.children[C, t, IndexedSeq[t]](field)
        case (field: String, _: Seq[t])        => state.children[C, t, Seq[t]](field)
        case (field: String, _: Set[t])        => state.children[C, t, Set[t]](field)
        case (field: String, _: Array[t])      =>
          state.children[C, t, Array[t]](field)(
            using arrayFactory(using summonInline[reflect.ClassTag[t]])
          )
        case (field: String, _) => state.attr[C, A](field)
      }
    }

    private inline def bind[E, C, Elems <: Tuple, Labels <: Tuple](inline state: State[E]): List[Any] =
      inline (erasedValue[Elems], erasedValue[Labels]) match {
        case _: (head *: tail, lab *: labs) => bindAttr[E, C, lab, head](state) :: bind[E, C, tail, labs](state)
        case _                              => Nil
      }

    private inline def dispatchSum[E, A, C, Elems <: Tuple](
        inline elem: E,
        inline attrNameToFieldName: String => String
    )(using b: XmlBackend[E]): A =
      inline erasedValue[Elems] match {
        case _: (head *: tail) =>
          val mapping = Macro.mapping[head]
          val matches = mapping.types.exists {
            case TypeMapping.MatchTag(names)  => names.contains(b.label(elem))
            case TypeMapping.MatchAttr(attrs) => attrs.exists(b.attributes(elem).contains)
            case TypeMapping.MatchAny         => true
          }
          if (matches)
            summonInline[XmlBinding[head]].apply(elem, attrNameToFieldName, mapping).asInstanceOf[A]
          else
            dispatchSum[E, A, C, tail](elem, attrNameToFieldName)
        case _: EmptyTuple =>
          val lbl = b.label(elem)
          val msg = s"Unknown tag `$lbl` when binding to sum type `${constValue[C]}`" +
            s", annotate the matching case with @matchTag(\"$lbl\")"
          if (b.attributes(elem).isEmpty) throw XmlBindingException(msg, lbl)
          else {
            val attrs = debugAttrsNames(elem)
            throw XmlBindingException(
              s"$msg, annotate the matching case with @matchTag(\"$lbl\"), or @matchAttr(${attrs.mkString("|")})",
              lbl
            )
          }
      }

    inline given derived[A](using m: Mirror.Of[A]): XmlBinding[A] =
      inline m match {
        case s: Mirror.SumOf[A] => new XmlBinding[A] {
            def apply[E](xml: E, attrNameToFieldName: String => String, mapping: Mapping)(using XmlBackend[E]): A =
              dispatchSum[E, A, m.MirroredLabel, m.MirroredElemTypes](xml, attrNameToFieldName)
          }: @nowarn
        case p: Mirror.ProductOf[A] => new XmlBinding[A] {
            def apply[E](xml: E, attrNameToFieldName: String => String, mapping: Mapping)(using XmlBackend[E]): A = {
              val fields = Set.from(constValueTuple[m.MirroredElemLabels].productIterator).asInstanceOf[Set[String]]
              val state  = State[E](xml, attrNameToFieldName, mapping, fields)
              p.fromProduct(
                Tuple.fromArray(
                  bind[E, m.MirroredLabel, m.MirroredElemTypes, m.MirroredElemLabels](state).toArray
                )
              )
            }
          }: @nowarn
      }

    /** Common XML attributes to Scala abbreviations; used by default in [[kebabCaseToCamelCase]] attribute mapping. */
    final val ScalaAbbreviationReplacements: Map[String, String] = Map(
      "object"  -> "obj",
      "class"   -> "cls",
      "type"    -> "tpe",
      "package" -> "pkg"
    )

    /** Convert a kebab‑case to camelCase. Applies standard abbreviation replacements first. This is useful for mapping
      * XMLs with kebab-cases to more idiomatic Scala field names.
      *
      * @example
      *   {{{
      *  import net.kurobako.xmlbinder.*
      *  case class Data(cls: String, customData: String) derives XmlBinding
      *
      *  XmlBinding.string[Data](
      *    "<data class=\"foo\" custom-data=\"bar\"/>"
      *  ) == Data("foo", "bar") // true
      *   }}}
      */
    def kebabCaseToCamelCase(name: String, replacements: Map[String, String] = ScalaAbbreviationReplacements): String =
      replacements.getOrElse(
        name,
        name.split('-').filter(_.nonEmpty).toList match {
          case x :: xs => s"$x${xs.map(_.capitalize).mkString}"
          case Nil     => name
        }
      )

    /** Parse a raw XML string and bind it to `A`.
      * @tparam A
      *   has a given `XmlBinding[A]`
      * @param rawXML
      *   XML content as a string
      * @param attrNameToFieldName
      *   XML attribute name to Scala field name mapping (default: kebab‑case to camelCase)
      */
    inline def string[E, A: XmlBinding](
        inline rawXML: String,
        inline attrNameToFieldName: String => String = kebabCaseToCamelCase(_)
    )(using b: XmlBackend[E]): A =
      summon[XmlBinding[A]].apply(b.loadString(rawXML), attrNameToFieldName, Macro.mapping[A])

    /** Bind an existing `scala.xml.Elem` to `A`.
      * @tparam A
      *   has a given `XmlBinding[A]`
      * @param elem
      *   the XML element
      * @param attrNameToFieldName
      *   XML attribute name to Scala field name mapping (default: kebab‑case to camelCase)
      */
    inline def elem[E, A: XmlBinding](
        inline elem: E,
        inline attrNameToFieldName: String => String = kebabCaseToCamelCase(_)
    )(using b: XmlBackend[E]): A =
      summon[XmlBinding[A]].apply(elem, attrNameToFieldName, Macro.mapping[A])

  }

  given XmlMappable[Char] = { s =>
    if (s.length == 1) Right(s.head)
    else Left(s"Illegal character format `$s`, expecting string length of 1")
  }
  given XmlMappable[Byte]   = n => n.toByteOption.toRight(s"Illegal numeric format `$n` for Byte")
  given XmlMappable[Short]  = n => n.toShortOption.toRight(s"Illegal numeric format `$n` for Short")
  given XmlMappable[Int]    = n => n.toIntOption.toRight(s"Illegal numeric format `$n` for Int")
  given XmlMappable[Long]   = n => n.toLongOption.toRight(s"Illegal numeric format `$n` for Long")
  given XmlMappable[Float]  = n => n.toFloatOption.toRight(s"Illegal numeric format `$n` for Float")
  given XmlMappable[Double] = n => n.toDoubleOption.toRight(s"Illegal numeric format `$n` for Double")
  given XmlMappable[BigInt] = n => scala.util.Try(BigInt(n)).toOption.toRight(s"Illegal numeric format `$n` for BigInt")
  given XmlMappable[BigDecimal] = n =>
    scala.util.Try(BigDecimal(n)).toOption.toRight(s"Illegal numeric format `$n` for BigDecimal")
  given XmlMappable[String]  = Right(_)
  given XmlMappable[Boolean] = {
    case "true"  => Right(true)
    case "false" => Right(false)
    case bad     => Left(s"Illegal boolean format: $bad, expecting \"true\"|\"false\"")
  }

  object scalaxml {
    import scala.xml.*

    given XmlBackend[scala.xml.Elem] = new XmlBackend[scala.xml.Elem] {
      def loadString(xml: String): Elem = XML.loadString(xml)
      def source(e: Elem): String       = e.toString
      def label(e: Elem): String        = e.label
      def text(e: Elem): String         = e.text
      def ownText(e: Elem): String      = e.child.collect { case t: Text => t.data; case c: PCData => c.data }.mkString
      def children(e: Elem): Seq[Elem]  = e.child.collect { case c: Elem => c }.toList
      def attributes(e: Elem): Map[String, String]                         = e.attributes.asAttrMap
      def attribute(e: Elem, local: String): Option[String]                = e.attribute(local).map(_.text)
      def attributeQName(e: Elem, qname: String): Option[String]           = (e \ s"@$qname").headOption.map(_.text)
      def attributeNS(e: Elem, uri: String, local: String): Option[String] =
        e.attributes.get(uri, e.scope, local).map(_.text)
      def resolvePrefix(e: Elem, prefix: String): Option[String]             = Option(e.getNamespace(prefix))
      def originalQName(e: Elem, uri: String, local: String): Option[String] = {
        @tailrec def loop(md: MetaData): Option[String] = md match {
          case x: PrefixedAttribute
              if x.key == local && Option(e.getNamespace(x.pre)).contains(uri) =>
            Some(s"${x.pre}:$local")
          case x: UnprefixedAttribute if x.key == local && uri == null => Some(local)
          case x                                                       => loop(x.next)
          case null                                                    => None
        }
        loop(e.attributes)
      }
    }
  }

  object dom {

    import java.io.StringReader
    import javax.xml.parsers.DocumentBuilderFactory
    import scala.jdk.CollectionConverters.*

    import org.w3c.dom.Attr
    import org.w3c.dom.CDATASection
    import org.w3c.dom.Element
    import org.w3c.dom.Node
    import org.w3c.dom.Text
    import org.w3c.dom.ls.DOMImplementationLS
    import org.xml.sax.InputSource

    private final val XMLNS_URI = "http://www.w3.org/2000/xmlns/"

    given XmlBackend[Element] = new XmlBackend[Element] {

      def loadString(xml: String): Element = {
        val f = DocumentBuilderFactory.newInstance()
        f.setNamespaceAware(true)
        // be conservative with features; ignore failures silently if not supported
        try f.setXIncludeAware(false)
        catch { case _: Throwable => () }
        try f.setExpandEntityReferences(false)
        catch { case _: Throwable => () }
        val b   = f.newDocumentBuilder()
        val doc = b.parse(InputSource(StringReader(xml)))
        doc.getDocumentElement
      }

      def source(e: Element): String = {
        val impl = e.getOwnerDocument.getImplementation.asInstanceOf[DOMImplementationLS]
        val ser  = impl.createLSSerializer()
        ser.getDomConfig.setParameter("xml-declaration", false)
        ser.writeToString(e)
      }

      def label(e: Element): String = Option(e.getLocalName).getOrElse(e.getTagName)

      def text(e: Element): String = e.getTextContent

      def ownText(e: Element): String = {
        val sb    = StringBuilder()
        val nodes = e.getChildNodes
        var i     = 0
        while (i < nodes.getLength) {
          nodes.item(i) match {
            case c: CDATASection => sb.append(c.getData)
            case t: Text         => sb.append(t.getData)
            case _               => ()
          }
          i += 1
        }
        sb.result()
      }

      def children(e: Element): Seq[Element] = {
        val out   = scala.collection.mutable.ArrayBuffer.empty[Element]
        val nodes = e.getChildNodes
        var i     = 0
        while (i < nodes.getLength) {
          val n = nodes.item(i)
          if (n.getNodeType == Node.ELEMENT_NODE) out += n.asInstanceOf[Element]
          i += 1
        }
        out.toSeq
      }

      def attributes(e: Element): Map[String, String] = {
        val m   = e.getAttributes
        val out = scala.collection.mutable.Map.empty[String, String]
        var i   = 0
        while (i < m.getLength) {
          val a       = m.item(i).asInstanceOf[Attr]
          val isXmlns = {
            val uri = a.getNamespaceURI
            (uri != null && uri == XMLNS_URI) || a.getName == "xmlns" || a.getPrefix == "xmlns"
          }
          if (!isXmlns) out += (a.getName -> a.getValue)
          i += 1
        }
        out.toMap
      }

      def attribute(e: Element, local: String): Option[String] =
        Option(e.getAttributeNode(local)).filter(_.getNamespaceURI != XMLNS_URI).map(_.getValue)

      def attributeQName(e: Element, qname: String): Option[String] =
        Option(e.getAttributeNode(qname)).filter(_.getNamespaceURI != XMLNS_URI).map(_.getValue)

      def attributeNS(e: Element, uri: String, local: String): Option[String] =
        if (uri == XMLNS_URI) None else Option(e.getAttributeNodeNS(uri, local)).map(_.getValue)

      def resolvePrefix(e: Element, prefix: String): Option[String] =
        Option(e.lookupNamespaceURI(if (prefix == null) null else prefix))

      def originalQName(e: Element, uri: String, local: String): Option[String] = {
        val m = e.getAttributes
        var i = 0
        while (i < m.getLength) {
          val a      = m.item(i).asInstanceOf[Attr]
          val aUri   = a.getNamespaceURI
          val aLocal = a.getLocalName
          val ok     =
            if (uri == null) aUri == null && (aLocal == null && a.getName == local || aLocal == local)
            else aUri == uri && (aLocal == local)
          if (ok) return Option(a.getName)
          i += 1
        }
        None
      }
    }

  }

}

package example

import net.kurobako.xmlbinder.*

trait Tests[E](using b: XmlBackend[E]) extends munit.FunSuite {

  inline def isSame[A: XmlBinding](inline actual: String, inline expected: A) =
    assertEquals(XmlBinding.string[E, A](actual), expected)

  test("bind simple") {
    case class Person(name: String, age: Int, active: Boolean) derives XmlBinding
    isSame("""<person name="Alice" age="30" active="false"/>""", Person("Alice", 30, false))
  }

  test("bind simple with extra") {
    case class Person(name: String, @extras rest: Map[String, String]) derives XmlBinding
    isSame(
      """<person name="Bob" age="25" active="true" extra-field="zzz"/>""",
      Person("Bob", Map("age" -> "25", "active" -> "true", "extra-field" -> "zzz"))
    )
  }

  test("text includes descendants") {
    case class T(@text t: String) derives XmlBinding
    isSame("<p>hi<b>bold</b>!</p>", T("hibold!"))
  }

  test("ownText excludes descendants") {
    case class T(@ownText t: String) derives XmlBinding
    isSame("<p>hi<b>bold</b>!</p>", T("hi!"))
  }

  test("bind attrName simple") {
    case class Custom(@attrName("a") fooBar: String) derives XmlBinding
    isSame("<c a=\"baz\"/>", Custom("baz"))
  }

  test("bind attrName with multiple fields") {
    case class Multi(
        @attrName("x-y") xY: Int,
        @attrName("a_b") ab: String
    ) derives XmlBinding
    isSame("<m x-y=\"5\" a_b=\"hello\"/>", Multi(5, "hello"))
  }

  test("bind attrName alongside extras") {
    case class WithExtras(
        @attrName("custom") value: String,
        @extras extras: Map[String, String]
    ) derives XmlBinding
    isSame("<w custom=\"v\" foo=\"bar\"/>", WithExtras("v", Map("foo" -> "bar")))
  }

  test("bind tagName") {
    case class Tagged(@tagName name: String) derives XmlBinding
    isSame("<widget some-attr='x'/>", Tagged("widget"))
  }

  test("bind sequence of children") {
    case class Item(id: Int, text: String) derives XmlBinding
    case class Container(@children xs: Vector[Item]) derives XmlBinding
    val xml =
      """<container>
        |  <item id="1" text="first"/>
        |  <item id="2" text="second"/>
        |  <item id="3" text="third"/>
        |</container>""".stripMargin

    isSame(
      xml,
      Container(Vector(
        Item(1, "first"),
        Item(2, "second"),
        Item(3, "third")
      ))
    )
  }

  test("bind nested nodes") {
    case class Node(a: String, @children xs: Vector[Leaf]) derives XmlBinding
    case class Leaf(a: String) derives XmlBinding

    isSame(
      """<node a="2"> <leaf a="3"/> </node>""",
      Node(
        "2",
        Vector(Leaf("3"))
      )
    )
  }

  test("bind sum type") {
    enum Tree derives XmlBinding {
      @matchTag("node") case Node(a: String, @children xs: Vector[Tree])
      @matchTag("leaf", "a") case Leaf(a: Option[String])
    }
    isSame(
      """<node a="1">
        |  <node a="2">
        |    <leaf a="3"/>
        |    <leaf a="4"/>
        |  </node>
        |  <a></a>
        |  <leaf a="5"/>
        |</node>""",
      Tree.Node(
        "1",
        Vector(
          Tree.Node(
            "2",
            Vector(Tree.Leaf(Some("3")), Tree.Leaf(Some("4")))
          ),
          Tree.Leaf(None),
          Tree.Leaf(Some("5"))
        )
      )
    )
  }

  test("bind optional attribute present") {
    case class Opt(a: Option[Int]) derives XmlBinding
    isSame("""<x a="42"/>""", Opt(Some(42)))
  }

  test("bind optional attribute absent") {
    case class Opt(a: Option[Int]) derives XmlBinding
    isSame("<x/>", Opt(None))
  }

  test("missing mandatory attribute should error") {
    case class Mand(a: Int) derives XmlBinding
    assert(intercept[XmlBindingException](XmlBinding.string[E, Mand]("<m/>")).getMessage.startsWith(
      "Missing mandatory field `Mand.a: scala.Int`; tag contains no attributes (while binding `"
    ))
  }

  test("illegal format for Int attr should error") {
    case class Bad(a: Int) derives XmlBinding
    assert(intercept[XmlBindingException](XmlBinding.string[E, Bad]("<b a=\"foo\"/>")).getMessage.startsWith(
      "Cannot parse `foo` into scala.Int for `Bad.a: scala.Int` (while binding `"
    ))
  }

  test("bind camelCase field name") {
    case class Camel(myFieldName: String) derives XmlBinding
    isSame("<c my-field-name=\"ok\"/>", Camel("ok"))
  }

  test("bind complex camelCase field name") {
    case class Camel(myName: String) derives XmlBinding
    isSame("<c my---name=\"ok\"/>", Camel("ok"))
    isSame("<c my-name-=\"ok\"/>", Camel("ok"))
  }

  test("extras map empty when none") {
    case class HasExtras(@extras rest: Map[String, String]) derives XmlBinding
    isSame("<e/>", HasExtras(Map.empty))
  }

  {
    enum Shape derives XmlBinding {
      @matchAttr("radius") case Circle(radius: Int)
      @matchAttr("width", "height") case Rectangle(width: Int, height: Int)
      @matchAny case Unknown(area: Int)
    }
    test("bind sum type Circle via matchAttr") {
      isSame("<shape radius=\"5\"/>", Shape.Circle(5))
    }
    test("bind sum type Rectangle via matchAttr") {
      isSame("<shape width=\"3\" height=\"4\"/>", Shape.Rectangle(3, 4))
    }
    test("bind sum type Unknown via matchAny") {
      isSame("<shape area=\"3\"/>", Shape.Unknown(3))
    }
  }

  test("bind tagName with extras") {
    case class Combined(@tagName name: String, @extras rest: Map[String, String]) derives XmlBinding
    isSame(
      "<foo bar=\"1\" baz=\"2\"/>",
      Combined("foo", Map("bar" -> "1", "baz" -> "2"))
    )
  }

  test("bind nested text") {
    case class Item(@text val name: String) derives XmlBinding
    case class Parent(@children val items: Seq[Item]) derives XmlBinding

    isSame(
      "<parent><item>one</item><item>two</item></parent>",
      Parent(Seq(Item("one"), Item("two")))
    )

  }

  test("bind empty children") {
    case class Item(a: String) derives XmlBinding
    case class Parent(@children xs: Vector[Item]) derives XmlBinding
    isSame("<parent></parent>", Parent(Vector()))
  }

  test("bind list of children") {
    case class Item(id: Int) derives XmlBinding
    case class ListContainer(@children xs: List[Item]) derives XmlBinding
    isSame(
      "<list><item id=\"1\"/><item id=\"2\"/></list>",
      ListContainer(List(Item(1), Item(2)))
    )
  }

  test("bind children ignoring text nodes") {
    case class Item(v: String) derives XmlBinding
    case class Parent(@children xs: Vector[Item]) derives XmlBinding
    val xml =
      "<p>hello<item v=\"x\"/>world<item v=\"y\"/></p>"
    isSame(xml, Parent(Vector(Item("x"), Item("y"))))
  }

  test("bind text content") {
    case class Txt(@text value: String) derives XmlBinding
    isSame("<n>hello world</n>", Txt("hello world"))
  }

  test("bind empty text content") {
    case class Empty(@text t: String) derives XmlBinding
    isSame("<e></e>", Empty(""))
  }

  test("bind attribute plus text content") {
    case class AtText(id: Int, @text txt: String) derives XmlBinding
    isSame("<x id=\"7\">some text</x>", AtText(7, "some text"))
  }

  test("bind optional text content present") {
    case class Maybe(@text t: Option[String]) derives XmlBinding
    isSame("<m>foo</m>", Maybe(Some("foo")))
  }

  test("bind optional text content absent") {
    case class Maybe(@text t: Option[String]) derives XmlBinding
    isSame("<m></m>", Maybe(None))
  }

  test("bind text with whitespace preserved") {
    case class Space(@text t: String) derives XmlBinding
    isSame("<w>  spaced  </w>", Space("  spaced  "))
  }

  test("bind numeric text content") {
    case class Num(@text n: Int) derives XmlBinding
    isSame("<num>123</num>", Num(123))
  }

  test("bind optional numeric text content") {
    case class Num(@text n: Option[Int]) derives XmlBinding
    isSame("<num>123</num>", Num(Some(123)))
    isSame("<num></num>", Num(None))
  }

  test("bind custom type") {
    case class Bounds(left: Int, top: Int, bottom: Int, right: Int)
    case class Foo(b: Bounds) derives XmlBinding
    given XmlMappable[Bounds] = {
      case s"[$l,$t][$b,$r]" => (for {
          l <- l.toIntOption
          t <- t.toIntOption
          b <- b.toIntOption
          r <- r.toIntOption
        } yield Bounds(l, t, b, r)).toRight("Can't parse bound components")
      case bad => Left(s"Can't parse $bad")
    }
    isSame("<foo b= \"[1,2][3,4]\"/>", Foo(Bounds(1, 2, 3, 4)))

  }

  // namespaces

  test("bind element with prefixed namespace") {
    case class Foo(name: String) derives XmlBinding
    isSame(
      """<ns:foo xmlns:ns="http://example.com/ns" name="bar"/>""",
      Foo("bar")
    )
  }

  test("bind element in default namespace") {
    case class Default(name: String) derives XmlBinding
    isSame(
      """<foo xmlns="http://example.com/default" name="baz"/>""",
      Default("baz")
    )
  }

  test("bind child element ") {
    case class Child(v: String) derives XmlBinding
    case class Parent(@children child: List[Child]) derives XmlBinding
    isSame("""<p><child v="x"/></p>""", Parent(List(Child("x"))))
  }

  test("bind child element with namespace") {
    case class Child(v: String) derives XmlBinding
    case class Parent(@children child: List[Child]) derives XmlBinding
    isSame(
      """<p xmlns="urn:parent">
         |  <ns:child xmlns:ns="urn:parent" v="x"/>
         |</p>""".stripMargin,
      Parent(List(Child("x")))
    )
  }

  test("extras map collects namespaced attributes") {
    case class Extras(@extras rest: Map[String, String]) derives XmlBinding
    isSame(
      """<e xmlns:foo="urn:ex" foo:bar="val" plain="ok"/>""",
      Extras(Map("foo:bar" -> "val", "plain" -> "ok"))
    )
  }

  test("attrName mapping does not leak into extras") {
    case class X(@attrName("x-y") xY: Int, @extras rest: Map[String, String]) derives XmlBinding
    isSame("""<x x-y="1" z="2"/>""", X(1, Map("z" -> "2")))
    case class C(@attrName("{u}a") a: String, @extras rest: Map[String, String]) derives XmlBinding
    isSame("""<c xmlns:p="u" p:a="v" x="1"/>""", C("v", Map("x" -> "1")))
  }

  test("attrName with namespace prefix") {
    case class N(@attrName("ns:a") a: String) derives XmlBinding
    isSame("""<n xmlns:ns="urn" ns:a="v"/>""", N("v"))
  }

  test("attrName with URI literal is prefix-agnostic") {
    case class N(@attrName("{urn}a") a: String) derives XmlBinding
    isSame("""<n xmlns:p="urn" p:a="v"/>""", N("v"))
  }

  test("optional single child") {

    case class Item(v: String) derives XmlBinding
    case class C(@children ccc: Option[Item]) derives XmlBinding
    isSame("""<c><item v="x"/></c>""", C(Some(Item("x"))))
    isSame("""<c></c>""", C(None))
  }

  test("optional single child errors on multiple children") {
    case class Item(v: String) derives XmlBinding
    case class C(@children c: Option[Item]) derives XmlBinding
    assert(
      intercept[XmlBindingException] {
        XmlBinding.string[E, C]("""<c><item v="x"/><item v="y"/></c>""")
      }.getMessage.startsWith("Expected at most one child element for C.c: Item, found 2 (while binding `")
    )
  }

  test("array of children") {

    case class Item(id: Int) derives XmlBinding
    case class C(@children xs: Array[Item]) derives XmlBinding
    val c = XmlBinding.string[E, C]("""<c><item id="1"/><item id="2"/></c>""")
    assertEquals(c.xs.map(_.id).toList, List(1, 2))
  }

  test("missing XmlMappable should not compile") {
    compileErrors("""
      case class Foo(bar: String)
      case class Unknown(a: Foo) derives XmlBinding
      XmlBinding.string[Unknown]("<u a=\"bar\"/>")
    """)
  }

  test("Optional @repr") {
    case class ReprTop(@repr raw: Option[E], id: Int) derives XmlBinding
    val v = XmlBinding.string[E, ReprTop]("""<x id="7" extra="z"/>""")

    val be = summon[XmlBackend[E]]
    assertEquals(be.label(v.raw.get), "x")
    assertEquals(be.attribute(v.raw.get, "id"), Some("7"))
    // ensure extras didn’t get swallowed into repr handling
    assertEquals(be.attribute(v.raw.get, "extra"), Some("z"))
  }

  test("@repr binds the backend element (top-level)") {
    case class ReprTop(@repr raw: E, id: Int) derives XmlBinding
    val v = XmlBinding.string[E, ReprTop]("""<x id="7" extra="z"/>""")

    val be = summon[XmlBackend[E]]
    assertEquals(be.label(v.raw), "x")
    assertEquals(be.attribute(v.raw, "id"), Some("7"))
    // ensure extras didn’t get swallowed into repr handling
    assertEquals(be.attribute(v.raw, "extra"), Some("z"))
  }

  test("@repr works inside children") {
    case class Item(@repr raw: E, id: Int) derives XmlBinding
    case class Box(@children items: List[Item]) derives XmlBinding

    val b  = XmlBinding.string[E, Box]("""<box><item id="1"/><item id="2"/></box>""")
    val be = summon[XmlBackend[E]]

    assertEquals(b.items.map(i => be.attribute(i.raw, "id")), List(Some("1"), Some("2")))
    // sanity: each child repr has no element children
    assertEquals(be.children(b.items.head.raw).size, 0)
  }

  test("@repr inside sum types binds the concrete case element") {
    enum T derives XmlBinding {
      @matchTag("a") case A(@repr raw: E, v: Int)
      @matchTag("b") case B(@repr raw: E, w: String)
    }
    val be = summon[XmlBackend[E]]

    XmlBinding.string[E, T]("""<a v="5"/>""") match {
      case T.A(raw, v) =>
        assertEquals(v, 5)
        assertEquals(be.label(raw), "a")
      case _ => fail("expected A")
    }

    XmlBinding.string[E, T]("""<b w="x"/>""") match {
      case T.B(raw, w) =>
        assertEquals(w, "x")
        assertEquals(be.label(raw), "b")
      case _ => fail("expected B")
    }
  }

  test("@repr cooperates with @extras") {
    case class WithReprAndExtras(@repr raw: E, name: String, @extras rest: Map[String, String]) derives XmlBinding
    val v = XmlBinding.string[E, WithReprAndExtras]("""<p name="ok" a="1" b="2"/>""")

    val be = summon[XmlBackend[E]]
    assertEquals(v.rest, Map("a" -> "1", "b" -> "2"))
    assertEquals(be.label(v.raw), "p")
    assertEquals(be.attribute(v.raw, "name"), Some("ok"))
  }

  test("self-recursive product binds nested children") {
    case class R(name: String, @children kids: List[R]) derives XmlBinding

    val xml =
      """<r name="root">
      |  <r name="a">
      |    <r name="a1"/>
      |  </r>
      |  <r name="b"/>
      |</r>""".stripMargin

    isSame(
      xml,
      R(
        "root",
        List(
          R("a", List(R("a1", Nil))),
          R("b", Nil)
        )
      )
    )
  }

  test("generic Foo[A] binds children of A") {
    case class Item(v: Int) derives XmlBinding
    case class Foo[A](a: Int, @children xs: Seq[A]) derives XmlBinding

    isSame(
      "<foo a='1'><item v='2'/><item v='3'/></foo>",
      Foo[Item](1, Seq(Item(2), Item(3)))
    )
  }

  test("generic Foo[A] works with sum type A") {
    enum Node derives XmlBinding {
      @matchTag("i") case I(v: Int)
      @matchTag("s") case S(t: String)
    }
    case class Foo[A](a: Int, @children xs: Seq[A]) derives XmlBinding

    isSame(
      "<foo a='7'><i v='1'/><s t='x'/></foo>",
      Foo[Node](7, Seq(Node.I(1), Node.S("x")))
    )
  }

  test("generic Foo[A] with empty children") {
    case class Item(v: Int) derives XmlBinding
    case class Foo[A](a: Int, @children xs: Seq[A]) derives XmlBinding

    isSame("<foo a='0'></foo>", Foo[Item](0, Seq.empty))
  }

  test("bind BigInt attribute") {
    case class Big(v: BigInt) derives XmlBinding
    isSame("<b v=\"123456789012345678901234567890\"/>", Big(BigInt("123456789012345678901234567890")))
  }

  test("bind BigDecimal attribute") {
    case class Big(v: BigDecimal) derives XmlBinding
    isSame("<b v=\"12345.6789\"/>", Big(BigDecimal("12345.6789")))
  }

  test("bind optional BigInt absent") {
    case class Big(v: Option[BigInt]) derives XmlBinding
    isSame("<b/>", Big(None))
  }

  test("bind optional BigDecimal present") {
    case class Big(v: Option[BigDecimal]) derives XmlBinding
    isSame("<b v=\"3.14\"/>", Big(Some(BigDecimal("3.14"))))
  }

  test("invalid BigInt should error") {
    case class Big(v: BigInt) derives XmlBinding
    assert(intercept[XmlBindingException](XmlBinding.string[E, Big]("<b v=\"notanumber\"/>")).getMessage.startsWith(
      "Cannot parse `notanumber` into scala.math.BigInt for `Big.v: scala.math.BigInt`"
    ))
  }

  test("invalid BigDecimal should error") {
    case class Big(v: BigDecimal) derives XmlBinding
    assert(intercept[XmlBindingException](XmlBinding.string[E, Big]("<b v=\"notanumber\"/>")).getMessage.startsWith(
      "Cannot parse `notanumber` into scala.math.BigDecimal for `Big.v: scala.math.BigDecimal`"
    ))
  }

}

import dom.given
import scalaxml.given

class ScalaXMLSuite extends Tests[scala.xml.Elem] {}

class ExampleSuite2 extends Tests[org.w3c.dom.Element] {}

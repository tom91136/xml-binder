package net.kurobako

private[kurobako] object Macro {
  import scala.quoted.*

  import xmlbinder.*

  inline def typeName[T]: String                                = ${ typeNameImpl[T] }
  private def typeNameImpl[T: Type](using Quotes): Expr[String] = Expr(Type.show[T])

  inline def mapping[A]: Mapping                                   = ${ mappingImpl[A] }
  private def mappingImpl[A: Type](using q: Quotes): Expr[Mapping] = {
    import q.reflect.*
    val sym = TypeRepr.of[A].typeSymbol

    def stringConsts(xs: List[Term]) = '{
      Set(${
        Varargs(xs.flatMap {
          case Typed(Repeated(xs, _), _) => xs
          case x                         => x :: Nil
        }.collect { case Literal(StringConstant(name)) => Expr(name) })
      }*)
    }

    val types = sym.annotations.collect {
      case Apply(Select(New(tpt), _), xs) if tpt.tpe =:= TypeRepr.of[matchTag] =>
        '{ TypeMapping.MatchTag(${ stringConsts(xs) }) }
      case Apply(Select(New(tpt), _), xs) if tpt.tpe =:= TypeRepr.of[matchAttr] =>
        '{ TypeMapping.MatchAttr(${ stringConsts(xs) }) }
      case Apply(Select(New(tpt), _), _) if tpt.tpe =:= TypeRepr.of[matchAny] =>
        '{ TypeMapping.MatchAny }
    }

    val fields = sym
      .primaryConstructor
      .paramSymss
      .flatten
      .map { f =>
        f.name -> (f.annotations.collect {
          case Apply(Select(New(tpt), _), Literal(StringConstant(name)) :: Nil)
              if tpt.tpe =:= TypeRepr.of[attrName] => '{ FieldMapping.AttrName(${ Expr(name) }) }
          case x if x.tpe =:= TypeRepr.of[tagName]  => '{ FieldMapping.TagName }
          case x if x.tpe =:= TypeRepr.of[extras]   => '{ FieldMapping.Extras }
          case x if x.tpe =:= TypeRepr.of[text]     => '{ FieldMapping.Text }
          case x if x.tpe =:= TypeRepr.of[ownText]  => '{ FieldMapping.OwnText }
          case x if x.tpe =:= TypeRepr.of[children] => '{ FieldMapping.Children }
          case x if x.tpe =:= TypeRepr.of[repr]     => '{ FieldMapping.Repr }
        } match {
          case x :: Nil => Some(x)
          case Nil      => None
          case xs       => report.errorAndAbort(s"More than one field mapping annotation on ${f}")
        })
      }
      .collect { case (name, Some(expr)) => '{ (${ Expr(name) }, $expr) } }

    '{ Mapping(${ Expr.ofList(fields) }.toMap, ${ Expr.ofList(types) }) }
  }
}

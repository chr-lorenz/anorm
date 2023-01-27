/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package anorm

/**
 * @param tokens the token groups
 * @param names the binding names of parsed placeholders
 */
private[anorm] case class TokenizedStatement(tokens: Seq[TokenGroup], names: Seq[String])

private[anorm] object TokenizedStatement {
  import scala.quoted.{ Expr, FromExpr, Quotes, Type }

  /** Returns empty tokenized statement. */
  lazy val empty = TokenizedStatement(Nil, Nil)

  /** String interpolation to tokenize statement. */
  inline def stringInterpolation[T](
      inline parts: Seq[String],
      inline params: Seq[T & Show]
  ): (TokenizedStatement, Map[String, T]) = ${ tokenizeImpl[T]('parts, 'params) }

  /** Tokenization macro */
  private def tokenizeImpl[T](
      parts: Expr[Seq[String]],
      params: Expr[Seq[T & Show]]
  )(using Quotes, Type[T]): Expr[(TokenizedStatement, Map[String, T])] = '{
    val _parts  = ${ parts }
    val _params = ${ params }

    tokenize(Iterator[String](), Nil, _parts, _params, Nil, Nil, Map.empty[String, T])
  }

  @annotation.tailrec
  private[anorm] def tokenize[T](
      ti: Iterator[String],
      tks: List[StatementToken],
      parts: Seq[String],
      ps: Seq[T with Show],
      gs: Seq[TokenGroup],
      ns: Seq[String],
      m: Map[String, T]
  ): (TokenizedStatement, Map[String, T]) = if (ti.hasNext) {
    tokenize(ti, StringToken(ti.next()) :: tks, parts, ps, gs, ns, m)
  } else {
    if (tks.nonEmpty) {
      gs match {
        case prev :: groups =>
          ps.headOption match {
            case Some(v) =>
              prev match {
                case TokenGroup(StringToken(str) :: gts, pl) if str.endsWith("#") /* escaped part */ =>
                  val before =
                    if (str == "#") gts
                    else {
                      StringToken(str.dropRight(1)) :: gts
                    }
                  val ng = TokenGroup(
                    tks ::: StringToken(v.show) ::
                      before,
                    pl
                  )

                  tokenize(ti, tks.tail, parts, ps.tail, ng :: groups, ns, m)

                case _ =>
                  val ng = TokenGroup(tks, None)
                  val n  = '_'.toString + ns.size
                  tokenize(
                    ti,
                    tks.tail,
                    parts,
                    ps.tail,
                    ng :: prev.copy(placeholder = Some(n)) :: groups,
                    n +: ns,
                    m + (n -> v)
                  )
              }
            case _ =>
              sys.error(s"No parameter value for placeholder: ${gs.size}")
          }
        case _ => tokenize(ti, tks.tail, parts, ps, List(TokenGroup(tks, None)), ns, m)
      }
    } else
      parts.headOption match {
        case Some(part) =>
          val it = List(part).iterator

          if (!it.hasNext /* empty */ ) {
            tokenize(it, List(StringToken("")), parts.tail, ps, gs, ns, m)
          } else tokenize(it, tks, parts.tail, ps, gs, ns, m)

        case _ =>
          val groups = (gs match {
            case TokenGroup(List(StringToken("")), None) :: tgs => tgs // trim end
            case _                                              => gs
          }).collect {
            case TokenGroup(pr, pl) =>
              TokenGroup(pr.reverse, pl)
          }.reverse

          TokenizedStatement(groups, ns.reverse) -> m
      }
  }

  final class TokenizedStatementShow(subject: TokenizedStatement) extends Show {
    def show = subject.tokens.map(Show.mkString(_)).mkString
  }

  implicit object ShowMaker extends Show.Maker[TokenizedStatement] {
    def apply(subject: TokenizedStatement): Show =
      new TokenizedStatementShow(subject)
  }
}

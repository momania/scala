// $Id$

package scala.tools.selectivecps

import scala.tools.nsc._
import scala.tools.nsc.transform._
import scala.tools.nsc.symtab._
import scala.tools.nsc.plugins._

import scala.tools.nsc.ast._

/** 
 * In methods marked @cps, explicitly name results of calls to other @cps methods
 */
abstract class SelectiveANFTransform extends PluginComponent with Transform with
  TypingTransformers with CPSUtils {
  // inherits abstract value `global` and class `Phase` from Transform

  import global._                  // the global environment
  import definitions._             // standard classes and methods
  import typer.atOwner             // methods to type trees

  /** the following two members override abstract members in Transform */
  val phaseName: String = "selectiveanf"

  protected def newTransformer(unit: CompilationUnit): Transformer =
    new ANFTransformer(unit)


  class ANFTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {

    implicit val _unit = unit // allow code in CPSUtils.scala to report errors
    var cpsAllowed: Boolean = false // detect cps code in places we do not handle (yet)

    override def transform(tree: Tree): Tree = {
      if (!cpsEnabled) return tree

      tree match {

        // Maybe we should further generalize the transform and move it over 
        // to the regular Transformer facility. But then, actual and required cps
        // state would need more complicated (stateful!) tracking. 
        
        // Making the default case use transExpr(tree, None, None) instead of
        // calling super.transform() would be a start, but at the moment,
        // this would cause infinite recursion. But we could remove the
        // ValDef case here.
        
        case dd @ DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
          log("transforming " + dd.symbol)

          atOwner(dd.symbol) {
            val rhs1 = transExpr(rhs, None, getExternalAnswerTypeAnn(tpt.tpe))
      
            log("result "+rhs1)
            log("result is of type "+rhs1.tpe)

            treeCopy.DefDef(dd, mods, name, transformTypeDefs(tparams), transformValDefss(vparamss),
                        transform(tpt), rhs1)
          }

        case ff @ Function(vparams, body) =>
          log("transforming anon function " + ff.symbol)

          atOwner(ff.symbol) {

            //val body1 = transExpr(body, None, getExternalAnswerTypeAnn(body.tpe))

            // need to special case partial functions: if expected type is @cps
            // but all cases are pure, then we would transform
            // { x => x match { case A => ... }} to
            // { x => shiftUnit(x match { case A => ... })}
            // which Uncurry cannot handle (see function6.scala)
            
            val ext = getExternalAnswerTypeAnn(body.tpe)
            
            val body1 = body match {
              case Match(selector, cases) if (ext.isDefined && getAnswerTypeAnn(body.tpe).isEmpty) =>
                val cases1 = for {
                  cd @ CaseDef(pat, guard, caseBody) <- cases
                  caseBody1 = transExpr(body, None, ext)
                } yield {
                  treeCopy.CaseDef(cd, transform(pat), transform(guard), caseBody1)
                }
                treeCopy.Match(tree, transform(selector), cases1)

              case _ =>
                transExpr(body, None, ext)
            }
            
            log("result "+body1)
            log("result is of type "+body1.tpe)

            treeCopy.Function(ff, transformValDefs(vparams), body1)
          }

        case vd @ ValDef(mods, name, tpt, rhs) => // object-level valdefs
          log("transforming valdef " + vd.symbol)

          atOwner(vd.symbol) {

            assert(getExternalAnswerTypeAnn(tpt.tpe) == None)

            val rhs1 = transExpr(rhs, None, None)

            treeCopy.ValDef(vd, mods, name, transform(tpt), rhs1)
          }

        case TypeTree() =>
          // circumvent cpsAllowed here
          super.transform(tree)
        
        case Apply(_,_) =>
          // this allows reset { ... } in object constructors
          // it's kind of a hack to put it here (see note above)
          transExpr(tree, None, None)
        
        case _ => 
          
          if (hasAnswerTypeAnn(tree.tpe)) {
            if (!cpsAllowed)
              unit.error(tree.pos, "cps code not allowed here / " + tree.getClass + " / " + tree)

            log(tree)
          }

          cpsAllowed = false
          super.transform(tree)            
      }
    }


    def transExpr(tree: Tree, cpsA: CPSInfo, cpsR: CPSInfo): Tree = {
      transTailValue(tree, cpsA, cpsR) match {
        case (Nil, b) => b
        case (a, b) =>
          treeCopy.Block(tree, a,b)
      }
    }


    def transArgList(fun: Tree, args: List[Tree], cpsA: CPSInfo): (List[List[Tree]], List[Tree], CPSInfo) = {
      val formals = fun.tpe.paramTypes
      val overshoot = args.length - formals.length
      
      var spc: CPSInfo = cpsA
      
      val (stm,expr) = (for ((a,tp) <- args.zip(formals ::: List.fill(overshoot)(NoType))) yield {
        tp match {
          case TypeRef(_, ByNameParamClass, List(elemtp)) =>
            (Nil, transExpr(a, None, getAnswerTypeAnn(elemtp)))
          case _ =>
            val (valStm, valExpr, valSpc) = transInlineValue(a, spc)
            spc = valSpc
            (valStm, valExpr)
        }
      }).unzip
      
      (stm,expr,spc)
    }


    def transValue(tree: Tree, cpsA: CPSInfo, cpsR: CPSInfo): (List[Tree], Tree, CPSInfo) = {
      // return value: (stms, expr, spc), where spc is CPSInfo after stms but *before* expr
      implicit val pos = tree.pos
      tree match {
        case Block(stms, expr) => 
          val (cpsA2, cpsR2) = (cpsA, linearize(cpsA, getAnswerTypeAnn(tree.tpe))) // tbd
//          val (cpsA2, cpsR2) = (None, getAnswerTypeAnn(tree.tpe))
          val (a, b) = transBlock(stms, expr, cpsA2, cpsR2)
          
          val tree1 = (treeCopy.Block(tree, a, b)) // no updateSynthFlag here!!!

          (Nil, tree1, cpsA)

        case If(cond, thenp, elsep) =>
          
          val (condStats, condVal, spc) = transInlineValue(cond, cpsA)

          val (cpsA2, cpsR2) = (spc, linearize(spc, getAnswerTypeAnn(tree.tpe)))
//          val (cpsA2, cpsR2) = (None, getAnswerTypeAnn(tree.tpe))
          val thenVal = transExpr(thenp, cpsA2, cpsR2)
          val elseVal = transExpr(elsep, cpsA2, cpsR2)
          
          // check that then and else parts agree (not necessary any more, but left as sanity check)
          if (cpsR.isDefined) {
            if (elsep == EmptyTree)
              unit.error(tree.pos, "always need else part in cps code")
          }
          if (hasAnswerTypeAnn(thenVal.tpe) != hasAnswerTypeAnn(elseVal.tpe)) {
            unit.error(tree.pos, "then and else parts must both be cps code or neither of them")
          }

          (condStats, updateSynthFlag(treeCopy.If(tree, condVal, thenVal, elseVal)), spc)

        case Match(selector, cases) =>
        
          val (selStats, selVal, spc) = transInlineValue(selector, cpsA)
          val (cpsA2, cpsR2) = (spc, linearize(spc, getAnswerTypeAnn(tree.tpe)))
//          val (cpsA2, cpsR2) = (None, getAnswerTypeAnn(tree.tpe))

          val caseVals = for {
            cd @ CaseDef(pat, guard, body) <- cases
            bodyVal = transExpr(body, cpsA2, cpsR2)
          } yield {
            treeCopy.CaseDef(cd, transform(pat), transform(guard), bodyVal)
          }
          
          (selStats, updateSynthFlag(treeCopy.Match(tree, selVal, caseVals)), spc)


        case ldef @ LabelDef(name, params, rhs) =>
          if (hasAnswerTypeAnn(tree.tpe)) {
            val sym = currentOwner.newMethod(tree.pos, name)
                        .setInfo(ldef.symbol.info)
                        .setFlag(Flags.SYNTHETIC)
          
            val rhs1 = new TreeSymSubstituter(List(ldef.symbol), List(sym)).transform(rhs)
            val rhsVal = transExpr(rhs1, None, getAnswerTypeAnn(tree.tpe))
            new ChangeOwnerTraverser(currentOwner, sym) traverse rhsVal

            val stm1 = localTyper.typed(DefDef(sym, rhsVal))
            val expr = localTyper.typed(Apply(Ident(sym), List()))

            (List(stm1), expr, cpsA)
          } else {
            val rhsVal = transExpr(rhs, None, None)
            (Nil, updateSynthFlag(treeCopy.LabelDef(tree, name, params, rhsVal)), cpsA)
          }
          

        case Try(block, catches, finalizer) =>
          val blockVal = transExpr(block, cpsA, cpsR)
        
          val catchVals = for {
            cd @ CaseDef(pat, guard, body) <- catches
            bodyVal = transExpr(body, cpsA, cpsR)
          } yield {
            treeCopy.CaseDef(cd, transform(pat), transform(guard), bodyVal)
          }

          val finallyVal = transExpr(finalizer, None, None) // for now, no cps in finally

          (Nil, updateSynthFlag(treeCopy.Try(tree, blockVal, catchVals, finallyVal)), cpsA)

        case Assign(lhs, rhs) =>
          // allow cps code in rhs only
          val (stms, expr, spc) = transInlineValue(rhs, cpsA)
          (stms, updateSynthFlag(treeCopy.Assign(tree, transform(lhs), expr)), spc)
          
        case Return(expr0) =>
          val (stms, expr, spc) = transInlineValue(expr0, cpsA)
          (stms, updateSynthFlag(treeCopy.Return(tree, expr)), spc)

        case Throw(expr0) =>
          val (stms, expr, spc) = transInlineValue(expr0, cpsA)
          (stms, updateSynthFlag(treeCopy.Throw(tree, expr)), spc)

        case Typed(expr0, tpt) =>
          // TODO: should x: A @cps[B,C] have a special meaning?
          // type casts used in different ways (see match2.scala, #3199)
          val (stms, expr, spc) = transInlineValue(expr0, cpsA)
          val tpt1 = if (treeInfo.isWildcardStarArg(tree)) tpt else
            treeCopy.TypeTree(tpt).setType(removeAllCPSAnnotations(tpt.tpe))
//        (stms, updateSynthFlag(treeCopy.Typed(tree, expr, tpt1)), spc)
          (stms, treeCopy.Typed(tree, expr, tpt1).setType(removeAllCPSAnnotations(tree.tpe)), spc)
          
        case TypeApply(fun, args) =>
          val (stms, expr, spc) = transInlineValue(fun, cpsA)
          (stms, updateSynthFlag(treeCopy.TypeApply(tree, expr, args)), spc)

        case Select(qual, name) =>
          val (stms, expr, spc) = transInlineValue(qual, cpsA)
          (stms, updateSynthFlag(treeCopy.Select(tree, expr, name)), spc)

        case Apply(fun, args) =>
          val (funStm, funExpr, funSpc) = transInlineValue(fun, cpsA)
          val (argStm, argExpr, argSpc) = transArgList(fun, args, funSpc)

          (funStm ::: (argStm.flatten), updateSynthFlag(treeCopy.Apply(tree, funExpr, argExpr)),
            argSpc)

        case _ =>
          cpsAllowed = true
          (Nil, transform(tree), cpsA)
      }
    }
    
    def transTailValue(tree: Tree, cpsA: CPSInfo, cpsR: CPSInfo): (List[Tree], Tree) = {
      
      val (stms, expr, spc) = transValue(tree, cpsA, cpsR)

      val bot = linearize(spc, getAnswerTypeAnn(expr.tpe))(unit, tree.pos)

      val plainTpe = removeAllCPSAnnotations(expr.tpe)

      if (cpsR.isDefined && !bot.isDefined) {
        
        if (!expr.isEmpty && (expr.tpe.typeSymbol ne NothingClass)) {
          // must convert!
          log("cps type conversion (has: " + cpsA + "/" + spc + "/" + expr.tpe  + ")")
          log("cps type conversion (expected: " + cpsR.get + "): " + expr)
          
          if (!expr.tpe.hasAnnotation(MarkerCPSAdaptPlus))
            unit.warning(tree.pos, "expression " + tree + " is cps-transformed unexpectedly")
          
          try {
            val Some((a, b)) = cpsR

            val res = localTyper.typed(atPos(tree.pos) {
                    Apply(TypeApply(gen.mkAttributedRef(MethShiftUnit), 
                      List(TypeTree(plainTpe), TypeTree(a), TypeTree(b))), 
                       List(expr))
            })
            return (stms, res)

          } catch {
            case ex:TypeError =>
              unit.error(ex.pos, "cannot cps-transform expression " + tree + ": " + ex.msg)
          }
        }

      } else if (!cpsR.isDefined && bot.isDefined) {
        // error!
        log("cps type error: " + expr)
        //println("cps type error: " + expr + "/" + expr.tpe + "/" + getAnswerTypeAnn(expr.tpe))

        println(cpsR + "/" + spc + "/" + bot)

        unit.error(tree.pos, "found cps expression in non-cps position")
      } else {
        // all is well

        if (expr.tpe.hasAnnotation(MarkerCPSAdaptPlus)) {
          unit.warning(tree.pos, "expression " + expr + " of type " + expr.tpe + " is not expected to have a cps type")
          expr.setType(removeAllCPSAnnotations(expr.tpe))
        }

        // TODO: sanity check that types agree
      }

      (stms, expr)
    }
    
    def transInlineValue(tree: Tree, cpsA: CPSInfo): (List[Tree], Tree, CPSInfo) = {

      val (stms, expr, spc) = transValue(tree, cpsA, None) // never required to be cps

      getAnswerTypeAnn(expr.tpe) match {
        case spcVal @ Some(_) =>

          val valueTpe = removeAllCPSAnnotations(expr.tpe)

          val sym = currentOwner.newValue(tree.pos, unit.fresh.newName("tmp"))
                      .setInfo(valueTpe)
                      .setFlag(Flags.SYNTHETIC)
                      .setAnnotations(List(AnnotationInfo(MarkerCPSSym.tpe, Nil, Nil)))

          new ChangeOwnerTraverser(currentOwner, sym) traverse expr

          (stms ::: List(ValDef(sym, expr) setType(NoType)),
             Ident(sym) setType(valueTpe) setPos(tree.pos), linearize(spc, spcVal)(unit, tree.pos))

        case _ =>
          (stms, expr, spc)
      }

    }



    def transInlineStm(stm: Tree, cpsA: CPSInfo):  (List[Tree], CPSInfo) = {
      stm match {

        // TODO: what about DefDefs?
        // TODO: relation to top-level val def?
        // TODO: what about lazy vals?

        case tree @ ValDef(mods, name, tpt, rhs) =>
          val (stms, anfRhs, spc) = atOwner(tree.symbol) { transValue(rhs, cpsA, None) }
        
          val tv = new ChangeOwnerTraverser(tree.symbol, currentOwner)
          stms.foreach(tv.traverse(_))

          // TODO: symbol might already have annotation. Should check conformance
          // TODO: better yet: do without annotations on symbols
          
          val spcVal = getAnswerTypeAnn(anfRhs.tpe)
          if (spcVal.isDefined) {
              tree.symbol.setAnnotations(List(AnnotationInfo(MarkerCPSSym.tpe, Nil, Nil)))
          }
          
          (stms:::List(treeCopy.ValDef(tree, mods, name, tpt, anfRhs)), linearize(spc, spcVal)(unit, tree.pos))

        case _ =>
          val (headStms, headExpr, headSpc) = transInlineValue(stm, cpsA)
          val valSpc = getAnswerTypeAnn(headExpr.tpe)
          (headStms:::List(headExpr), linearize(headSpc, valSpc)(unit, stm.pos))
      }
    }

    def transBlock(stms: List[Tree], expr: Tree, cpsA: CPSInfo, cpsR: CPSInfo): (List[Tree], Tree) = {
      stms match {
        case Nil =>
          transTailValue(expr, cpsA, cpsR)

        case stm::rest =>
          var (rest2, expr2) = (rest, expr)
          val (headStms, headSpc) = transInlineStm(stm, cpsA)
          val (restStms, restExpr) = transBlock(rest2, expr2, headSpc, cpsR)
          (headStms:::restStms, restExpr)
       }
    }


  }
}

/* NSC -- new Scala compiler
 * Copyright 2007-2011 LAMP/EPFL
 * @author  David Bernard, Manohar Jonnalagedda
 */

package scala.tools.nsc
package doc

import scala.util.control.ControlThrowable
import reporters.Reporter
import util.NoPosition
import io.{ File, Directory }
import DocParser.Parsed

/** A documentation processor controls the process of generating Scala
  * documentation, which is as follows.
  *
  * * A simplified compiler instance (with only the front-end phases enabled)
  * * is created, and additional ''sourceless'' comments are registered.
  * * Documentable files are compiled, thereby filling the compiler's symbol table.
  * * A documentation model is extracted from the post-compilation symbol table.
  * * A generator is used to transform the model into the correct final format (HTML).
  *
  * A processor contains a single compiler instantiated from the processor's
  * `settings`. Each call to `document` uses the same compiler instance with
  * the same symbol table. In particular, this implies that the scaladoc site
  * obtained from a call to `run` will contain documentation about files compiled
  * during previous calls to the same processor's `run` method.
  *
  * @param reporter The reporter to which both documentation and compilation errors will be reported.
  * @param settings The settings to be used by the documenter and compiler for generating documentation.
  * 
  * @author Gilles Dubochet */
class DocFactory(val reporter: Reporter, val settings: doc.Settings) { processor =>
  /** The unique compiler instance used by this processor and constructed from its `settings`. */
  object compiler extends Global(settings, reporter) with interactive.RangePositions {
    override protected def computeInternalPhases() {
      phasesSet += syntaxAnalyzer
      phasesSet += analyzer.namerFactory
      phasesSet += analyzer.packageObjects
      phasesSet += analyzer.typerFactory
      phasesSet += superAccessors
      phasesSet += pickler
      phasesSet += refChecks
    }
    override def forScaladoc = true
  }

  /** Creates a scaladoc site for all symbols defined in this call's `files`,
    * as well as those defined in `files` of previous calls to the same processor.
    * @param files The list of paths (relative to the compiler's source path,
    *        or absolute) of files to document. */
  def makeUniverse(files: List[String]): Option[Universe] = {
    assert(settings.docformat.value == "html")
    new compiler.Run() compile files
    if (reporter.hasErrors)
      return None

    val extraTemplatesToDocument: Set[compiler.Symbol] = {
      if (settings.docUncompilable.isDefault) Set()
      else {
        val uncompilable = new {
          val global: compiler.type = compiler
          val settings = processor.settings
        } with Uncompilable { }

        compiler.docComments ++= uncompilable.comments
        docdbg("" + uncompilable)

        uncompilable.templates
      }
    }

    val modelFactory = (
      new { override val global: compiler.type = compiler }
        with model.ModelFactory(compiler, settings)
        with model.comment.CommentFactory
        with model.TreeFactory {
          override def templateShouldDocument(sym: compiler.Symbol) =
            extraTemplatesToDocument(sym) || super.templateShouldDocument(sym)
        }
    )

    modelFactory.makeModel match {
      case Some(madeModel) =>
        println("model contains " + modelFactory.templatesCount + " documentable templates")
        Some(madeModel)
      case None =>
        println("no documentable class found in compilation units")
        None
    }

  }

  object NoCompilerRunException extends ControlThrowable { }

  val documentError: PartialFunction[Throwable, Unit] = {
    case NoCompilerRunException =>
      reporter.info(NoPosition, "No documentation generated with unsucessful compiler run", false)
    case _: ClassNotFoundException =>
      ()
  }

  /** Generate document(s) for all `files` containing scaladoc documenataion.
    * @param files The list of paths (relative to the compiler's source path, or absolute) of files to document. */
  def document(files: List[String]) {
    def generate() = {
      import doclet._
      val docletClass    = Class.forName(settings.docgenerator.value) // default is html.Doclet
      val docletInstance = docletClass.newInstance().asInstanceOf[Generator]

      docletInstance match {
        case universer: Universer =>
          val universe = makeUniverse(files) getOrElse { throw NoCompilerRunException }
          universer setUniverse universe

          docletInstance match {
            case indexer: Indexer => indexer setIndex model.IndexModelFactory.makeIndex(universe)
            case _                => ()
          }
        case _ => ()
      }
      docletInstance.generate
    }

    try generate()
    catch documentError
  }

  private[doc] def docdbg(msg: String) {
    if (settings.Ydocdebug.value)
      println(msg)
  }
}

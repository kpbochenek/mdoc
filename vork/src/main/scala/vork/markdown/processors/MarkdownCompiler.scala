package vork.markdown.processors

import scalafix._
import scala.meta._
import java.io.File
import java.net.URLClassLoader
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter
import metaconfig.ConfError
import metaconfig.Configured
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import vork.runtime.Document
import vork.runtime.DocumentBuilder
import vork.runtime.Macros
import vork.runtime.Section

object MarkdownCompiler {

  case class EvaluatedDocument(sections: List[EvaluatedSection])
  object EvaluatedDocument {
    def apply(document: Document, trees: List[SectionInput]): EvaluatedDocument =
      EvaluatedDocument(
        document.sections.zip(trees).map {
          case (a, b) => EvaluatedSection(a, b.source, b.mod)
        }
      )
  }
  case class EvaluatedSection(section: Section, source: Source, mod: FencedCodeMod) {
    def out: String = section.statements.map(_.out).mkString
  }

  def fromClasspath(cp: String): MarkdownCompiler =
    if (cp.isEmpty) {
      default()
    } else {
      val runtimeOnly = defaultClasspath(
        path =>
          Set(
            "scala-reflect",
            "pprint",
            "vork-runtime"
          ).contains(path)
      )
      val finalRuntime =
        if (runtimeOnly.isEmpty) defaultClasspath
        else runtimeOnly
      new MarkdownCompiler(cp + File.pathSeparator + finalRuntime)
    }
  def default(): MarkdownCompiler = new MarkdownCompiler(defaultClasspath)
  def render(sections: List[String], compiler: MarkdownCompiler): EvaluatedDocument = {
    renderInputs(
      sections.map(s => SectionInput(dialects.Sbt1(s).parse[Source].get, FencedCodeMod.Default)),
      compiler
    )
  }

  case class SectionInput(source: Source, mod: FencedCodeMod)

  def renderInputs(sections: List[SectionInput], compiler: MarkdownCompiler): EvaluatedDocument = {
    val instrumented = instrumentSections(sections)
    val doc = document(compiler, instrumented)
    val evaluated = EvaluatedDocument(doc, sections)
    evaluated
  }

  def renderEvaluatedSection(section: EvaluatedSection): String = {
    val sb = new StringBuilder
    var first = true
    section.section.statements.zip(section.source.stats).foreach {
      case (statement, tree) =>
        if (first) {
          first = false
        } else {
          sb.append("\n")
        }
        sb.append("@ ")
          .append(tree.syntax)
        if (statement.out.nonEmpty) {
          sb.append("\n").append(statement.out)
        }
        if (sb.charAt(sb.size - 1) != '\n') {
          sb.append("\n")
        }

        statement.binders.foreach { binder =>
          section.mod match {
            case FencedCodeMod.Fail =>
              binder.value match {
                case Macros.TypecheckedOK =>
                  sb.append("???\n")
                case Macros.ParseError(msg) =>
                  sb.append(msg)
                case Macros.TypeError(msg) =>
                  sb.append(msg)
                case _ =>
                  val obtained = pprint.PPrinter.BlackWhite.apply(binder).toString()
                  throw new IllegalArgumentException(
                    s"Expected Macros.CompileResult." +
                      s"Obtained $obtained"
                  )
              }
            case _ =>
              statement.binders.foreach { binder =>
                sb.append(binder.name)
                  .append(": ")
                  .append(binder.tpe.render)
                  .append(" = ")
                  .append(pprint.PPrinter.BlackWhite.apply(binder.value))
                  .append("\n")
              }
          }
        }
    }
    if (sb.nonEmpty && sb.last == '\n') sb.setLength(sb.length - 1)
    sb.toString()
  }

  def document(compiler: MarkdownCompiler, instrumented: String): Document = {
    val wrapped =
      s"""
         |package vork
         |class Generated extends _root_.vork.runtime.DocumentBuilder {
         |  def app(): Unit = {
         |$instrumented
         |  }
         |}
      """.stripMargin
    val loader = compiler.compile(Input.String(wrapped)).get
    val cls = loader.loadClass(s"vork.Generated")
    cls.newInstance().asInstanceOf[DocumentBuilder].$doc.build()
  }

  // Copy paste from scalafix
  def defaultClasspath: String = defaultClasspath(_ => true)
  def defaultClasspath(filter: String => Boolean): String = {
    getClass.getClassLoader match {
      case u: URLClassLoader =>
        val paths = u.getURLs.iterator
          .map(u => {
            if (u.getProtocol.startsWith("bootstrap")) {
              import java.io._
              import java.nio.file._
              val stream = u.openStream
              val tmp = File.createTempFile("bootstrap-" + u.getPath, ".jar")
              Files
                .copy(stream, Paths.get(tmp.getAbsolutePath), StandardCopyOption.REPLACE_EXISTING)
              tmp.getAbsolutePath
            } else {
              URLDecoder.decode(u.getPath, "UTF-8")
            }
          })
          .filter(path => filter(path))
          .toList
        paths.mkString(File.pathSeparator)
      case _ => ""
    }
  }
  def instrumentSections(sections: List[SectionInput]): String = {
    var counter = 0
    val totalStats = sections.map(_.source.stats.length).sum
    val mapped = sections.map { section =>
      val (instrumentedSection, nextCounter) = instrument(section, counter)
      counter = nextCounter
      WIDTH_INDENT +
        "; $doc.section {\n" +
        instrumentedSection
    }
    val join = """
                 |// =======
                 |// Section
                 |// =======
                 |""".stripMargin
    val end = "\n" + ("}" * (totalStats + sections.length))
    mapped.mkString("", join, end)
  }

  object Binders {
    def binders(pat: Pat): List[Name] =
      pat.collect { case m: Member => m.name }
    def unapply(tree: Tree): Option[List[Name]] = tree match {
      case Defn.Val(_, pats, _, _) => Some(pats.flatMap(binders))
      case Defn.Var(_, pats, _, _) => Some(pats.flatMap(binders))
      case _: Defn => Some(Nil)
      case _: Import => Some(Nil)
      case _ => None
    }
  }

  def literal(string: String): String = {
    import scala.meta.internal.prettyprinters._
    enquote(string, DoubleQuotes)
  }

  private val WIDTH = 100
  private val WIDTH_INDENT = " " * WIDTH

  def instrument(section: SectionInput, n: Int): (String, Int) = {
    var counter = n
    val source = section.source
    val stats = source.stats
    val ctx = RuleCtx(source)
    def freshBinder(): String = {
      val name = "res" + counter
      counter += 1
      name
    }
    val rule = Rule.syntactic("Vork") { ctx =>
      val patches = stats.map { stat =>
        val (names, freshBinderPatch) = Binders.unapply(stat) match {
          case Some(b) if section.mod != FencedCodeMod.Fail =>
            b -> Patch.empty
          case _ =>
            val name = freshBinder()
            List(Term.Name(name)) -> ctx.addLeft(stat, s"${WIDTH_INDENT}val $name = \n")
        }
        val failPatch = section.mod match {
          case FencedCodeMod.Fail =>
            val newCode =
              s"_root_.vork.runtime.Macros.fail(${literal(stat.syntax)}); "
            ctx.replaceTree(stat, newCode)
          case _ =>
            Patch.empty
        }
        val rightIndent = " " * (WIDTH - stat.pos.endColumn)
        val binders = names
          .map(name => s"$$doc.binder($name)")
          .mkString(rightIndent + "; ", "; ", "; $doc.statement {")
        failPatch +
          freshBinderPatch +
          ctx.addRight(stat, binders)
      }
      val patch = patches.asPatch
      patch
    }
    val out = rule.apply(ctx)
    out -> counter
  }
}

class MarkdownCompiler(
    classpath: String,
    target: AbstractFile = new VirtualDirectory("(memory)", None)
) {
  private val settings = new Settings()
  settings.deprecation.value = true // enable detailed deprecation warnings
  settings.unchecked.value = true // enable detailed unchecked warnings
  settings.outputDirs.setSingleOutput(target)
  settings.classpath.value = classpath
  lazy val reporter = new StoreReporter
  private val global = new Global(settings, reporter)
  private def classLoader =
    new AbstractFileClassLoader(target, this.getClass.getClassLoader)

  private def clearTarget(): Unit = target match {
    case vdir: VirtualDirectory => vdir.clear()
    case _ =>
  }

  def compile(input: Input): Configured[ClassLoader] = {
    clearTarget()
    reporter.reset()
    val run = new global.Run
    val label = input match {
      case Input.File(path, _) => path.toString()
      case Input.VirtualFile(path, _) => path
      case _ => "(input)"
    }
    run.compileSources(List(new BatchSourceFile(label, new String(input.chars))))
    val errors = reporter.infos.collect {
      case reporter.Info(pos, msg, reporter.ERROR) =>
        ConfError
          .message(msg)
          .atPos(
            if (pos.isDefined) Position.Range(input, pos.start, pos.end)
            else Position.None
          )
          .notOk
    }
    ConfError
      .fromResults(errors.toSeq)
      .map(_.notOk)
      .getOrElse(Configured.Ok(classLoader))
  }
}

package codacy.plugins.test

import java.io.File
import java.nio.file.Path

import codacy.plugins.docker.DockerPlugin
import codacy.utils.{CollectionHelper, FileHelper, Printer}
import play.api.libs.json.{JsError, JsSuccess, Json, Reads}
import plugins._

import scala.util.Properties

object JsonTests extends ITest {

  val opt = "json"

  def run(plugin: DockerPlugin, testSources: Seq[Path], dockerImageName: String, optArgs: Seq[String]): Boolean = {
    Printer.green("Running JsonTests:")

    DockerHelpers.withDocsDirectory(dockerImageName) { baseDocDir =>

      val toolOpt = checkDoc[ToolSpec](baseDocDir, "patterns.json")(_ => true)
      val descriptionsOpt = checkDoc[Seq[PatternDescription]](baseDocDir, "description/description.json") { descriptions =>
        descriptions.map { pattern =>
          readFile(baseDocDir, s"description/${pattern.patternId}.md") match {
            case Some(_) =>
              Printer.green(s"Read /docs/description/${pattern.patternId}.md successfully")
              true

            case _ =>
              Printer.red(s"Could not read /docs/description/${pattern.patternId}.md")
              false
          }
        }.forall(identity)
      }

      (toolOpt, descriptionsOpt) match {
        case (Some(tool), Some(descriptions)) =>
          val diffResult = new CollectionHelper[PatternSpec, PatternDescription, String](tool.patterns.toSeq, descriptions)({
            pattern =>
              val parameters = pattern.parameters.getOrElse(Seq.empty).map(_.name.value).toSeq.sorted
              generateUniquePatternSignature(pattern.patternId.value, parameters)
          }, {
            description =>
              val parameters = description.parameters.getOrElse(Seq.empty).map(_.name.value).toSeq.sorted
              generateUniquePatternSignature(description.patternId.value, parameters)
          }).fastDiff

          if (diffResult.newObjects.nonEmpty) {
            Printer.red(
             s"""
                |Some patterns were only found in /docs/patterns.json
                |Confirm that all the patterns and parameters present in /docs/patterns.json are also present in /docs/description/description.json
                |
                |  * ${diffResult.newObjects.map(_.patternId).mkString(",")}
              """.stripMargin)
          }

          if (diffResult.deletedObjects.nonEmpty) {
            Printer.red(
             s"""
                |Some patterns were only found in /docs/description/description.json
                |Confirm that all the patterns and parameters present in /docs/description/description.json are also present in /docs/patterns.json
                |
                |  * ${diffResult.deletedObjects.map(_.patternId).mkString(",")}
              """.stripMargin)
          }

          diffResult.newObjects.isEmpty && diffResult.deletedObjects.isEmpty

        case (Some(_), _) =>
          true

        case _ => false
      }
    }
  }

  private def checkDoc[T](baseDir: Path, filename: String)(block: T => Boolean)(implicit format: Reads[T]): Option[T] = {
    readFile(baseDir.resolve(filename).toFile)
      .map { docString =>
        Printer.green(s"Read /docs/$filename successfully")

        Json.parse(docString).validate[T] match {
          case JsSuccess(descriptions, _) =>
            Printer.green(s"Parsed /docs/$filename successfully")
            block(descriptions)
            Option(descriptions)

          case JsError(e) =>
            Printer.red(s"Could not parse /docs/$filename")
            println(e)
            Option.empty[T]
        }
      }.getOrElse {
      Printer.red(s"Could not read /docs/$filename")
      Option.empty[T]
    }
  }

  private def generateUniquePatternSignature(patternName: String, parameters: Seq[String]) = {
    s"""$patternName:${parameters.mkString(",")}"""
  }

  private def readFile(baseDir: Path, filename: String): Option[String] = {
    FileHelper.read(baseDir.resolve(filename).toFile).map(_.mkString(Properties.lineSeparator))
  }

  private def readFile(file: File): Option[String] = {
    FileHelper.read(file).map(_.mkString(Properties.lineSeparator))
  }

}

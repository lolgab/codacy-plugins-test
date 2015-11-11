package codacy.plugins.test

import java.io.File
import java.nio.file.Path

import codacy.plugins.docker.{DockerPlugin, PluginConfiguration, PluginRequest}
import codacy.plugins.traits.IResultsPlugin
import codacy.utils.{FileHelper, Printer}
import org.apache.commons.io.FileUtils

case class TestPattern(toolName: String, plugin: IResultsPlugin, patternId: String)

object PatternTests extends ITest with CustomMatchers {

  val opt = "pattern"

  def run(plugin: DockerPlugin, testSources: Seq[Path], dockerImageName: String, patternName: Option[String]): Boolean = {
    Printer.green(s"Running PatternsTests:")
    testSources.map { sourcePath =>
      val testFiles = new TestFilesParser(sourcePath.toFile).getTestFiles

      val filteredTestFiles = patternName.fold(testFiles) {
        case patternNameToRun => testFiles.filter(testFiles => testFiles.file.getName.contains(patternNameToRun))
      }

      filteredTestFiles.map { testFile =>
        analyseFile(sourcePath.toFile, testFile, plugin)
      }.forall(identity)
    }.forall(identity)
  }

  private def analyseFile(rootDirectory: File, testFile: PatternTestFile, plugin: DockerPlugin): Boolean = {

    val testFilePath = testFile.file.getAbsolutePath
    val filename = toRelativePath(rootDirectory.getAbsolutePath, testFilePath)

    Printer.green(s"- $filename should have ${testFile.matches.length} matches with patterns: " +
      testFile.enabledPatterns.map(_.name).mkString(", "))

    val configuration = DockerHelpers.toPatterns(testFile.enabledPatterns)

    val testFiles = Seq(testFile.file)
    val testFilesAbsolutePaths = testFiles.map(_.getAbsolutePath)

    val filteredResults = {
      val pluginResult = plugin.run(PluginRequest(rootDirectory.getAbsolutePath, testFilesAbsolutePaths, PluginConfiguration(configuration)))
      filterResults(rootDirectory.toPath, testFiles, configuration, pluginResult.results)
    }

    val matches = filteredResults.map(r => TestFileResult(r.patternIdentifier, r.line, r.level))

    val comparison = beEqualTo(testFile.matches).apply(matches)

    Printer.green(s"  + ${matches.length} matches found in lines: ${matches.map(_.line).sorted.mkString(", ")}")

    if (!comparison.matches) Printer.red(comparison.rawFailureMessage)
    else Printer.green(comparison.rawNegatedFailureMessage)

    comparison.matches
  }

  private def toRelativePath(rootPath: String, absolutePath: String) = {
    absolutePath.stripPrefix(rootPath).stripPrefix(File.separator)
  }

}

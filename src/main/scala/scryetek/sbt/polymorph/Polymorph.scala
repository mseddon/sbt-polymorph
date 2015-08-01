package scryetek.sbt.polymorph

import java.util.Base64
import java.util.zip.ZipFile
import scala.collection.JavaConverters._
import android.Keys._
import android.Tasks._
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.cross.CrossProject
import sbt.Keys._
import sbt._
import sbtassembly.{PathList, MergeStrategy}
import sbtrobovm.RobovmPlugin._
import sbtrobovm.RobovmProjects

import scala.language.implicitConversions
import scala.language.experimental.macros
import scala.reflect.macros.Context
import sbtassembly.AssemblyKeys._

/**
 * ScryeTek sbt-polymorph
 */
object Polymorph extends AutoPlugin {
  lazy val kernelMainClass = settingKey[Option[String]]("The GLApp that this application is defined in")
  lazy val kernelBootClass = settingKey[Option[String]]("The name of the generated boot (main) class")
  lazy val polymorphLinkerObject = settingKey[Option[String]]("The name of the linker class, if one exists")

  lazy val generateEntrypoint = taskKey[Seq[File]]("Generates the boot class class for a given platform")
  lazy val copyToAssets = taskKey[Unit]("Copies the assets")

  lazy val commonSettings =  Seq(
    scalacOptions in ThisBuild ++= Seq( "-target:jvm-1.7", "-deprecation", "-feature"),
    scalaVersion := "2.11.6",
    kernelMainClass := None,
    kernelBootClass := None,
    polymorphLinkerObject := None,
    copyToAssets := {},
    resourceGenerators in Compile <+=
        (resourceManaged in Compile, polymorphLinkerObject) map { (dir: File, linker: Option[String]) =>
            linker.map { linker =>
              val file = dir / "linker.info"
              IO.write(file, linker)
              Seq(file)
            }.getOrElse(Seq())
    }
  )

  import ScalaJSPlugin.autoImport._

  def getPackageAndClass(name: String): (String, String) = {
    val idx = name.lastIndexOf('.')
    if(idx == -1) ("", name)
    else ("package "+name.substring(0, idx), name.substring(idx+1))
  }

  case class PolymorphProjectSet(private val _ios: Project, private val _android: Project, private val crossProject: CrossProject) {
    lazy val js = filterSettings(crossProject.js)
    lazy val jvm = filterSettings(crossProject.jvm)
    lazy val ios = filterSettings(_ios, "-ios").settings(name := name.value + "-ios")
    lazy val android = filterSettingsAndroid(_android).settings(name := name.value + "-android")

    def filterSettingsAndroid(project: Project): Project =
      project.settings(libraryDependencies := libraryDependencies.value.flatMap {
        case e: ModuleID if e.name.startsWith("polymorph@") =>
          Seq(aar(e.copy(name = e.name.substring(10) + "-android")))
        case e: ModuleID =>
          Seq(e)
      })

    def filterSettings(project: Project, postfix: String = ""): Project =
      project.settings(libraryDependencies := libraryDependencies.value.flatMap {
        case e: ModuleID if e.name.startsWith("polymorph@") =>
          Seq(e.copy(name = e.name.substring(10) + postfix))
        case e: ModuleID =>
          Seq(e)
      })

    def settings(ss: Def.Setting[_]*): PolymorphProjectSet =
      copy(_ios.settings(ss: _*), _android.settings(ss: _*), crossProject.settings(ss: _*))

    def jsSettings(ss: Def.Setting[_]*) =
      copy(crossProject = crossProject.jsSettings(ss: _*))

    def jvmSettings(ss: Def.Setting[_]*) =
      copy(crossProject = crossProject.jvmSettings(ss: _*))

    def iosSettings(ss: Def.Setting[_]*) =
      copy(_ios = _ios.settings(ss: _*))

    def androidSettings(ss: Def.Setting[_]*) =
      copy(_android = _android.settings(ss: _*))
  }


  class PolymorphProjectBuilder(libraryName: String, library: Boolean) {
    def in(dir: java.io.File): PolymorphProjectSet = {
      def getLinkerInfo(f: File): Option[String] = {
        val z = new ZipFile(f)
        val res = Option(z.getEntry("linker.info")).map(e => IO.readStream(z.getInputStream(e)))
        z.close()
        res
      }

      def collectLinkedFiles(files: Seq[Attributed[File]]): String =
        (for {
          f <- files
          linker <- getLinkerInfo(f.data)
        } yield linker).map(_ + ".link()").mkString("\n")

      def collectLinkedFilesAndroid(files: Seq[File]): String =
        (for {
          f <- files
          linker = f / "assets" / "linker.info"
          linkerString = IO.read(linker) if linker.exists()
        } yield linkerString).map(_ + ".link()").mkString("\n")

      val ios = RobovmProjects.iOSProject(libraryName+"IOS", dir / "ios").settings(commonSettings: _*).settings(
        (generateEntrypoint in Compile) := {
          if(kernelBootClass.value.isDefined) {
            val (packageDecl, entryClass) = getPackageAndClass(kernelBootClass.value.get)
            val mainApp = kernelMainClass.value.get
            val link = collectLinkedFiles((managedClasspath in Compile).value)
            val file = (sourceManaged in Compile).value / (entryClass + ".scala")
            IO.write(file,
              s"""$packageDecl
                 |object $entryClass extends App {
                 |  $link
                 |  new polymorph.impl.AppImpl(new $mainApp).main(args)
                 |}
                 |""".stripMargin('|'))
            Seq(file)
          } else Seq()
        },
        robovmSimulatorDevice := Some("iPhone-4s, 8.4"),
        sourceGenerators in Compile += (generateEntrypoint in Compile).taskValue,
        unmanagedSourceDirectories in Compile += dir.getAbsoluteFile / "shared" / "src" / "main" / "scala"
      )

      def recurseDirectory(base: java.io.File): Seq[(java.io.File, String)] = {
        def loop(dir: java.io.File): Seq[(java.io.File, String)] =
          dir.listFiles() flatMap { file =>
            if (file.isDirectory)
              recurseDirectory(file)
            else {
              val resource = file.getCanonicalPath.substring(base.getCanonicalPath.length)
              Seq((file, resource.replaceAll("\\\\", "/")))
            }
          }
        loop(base)
      }

      val ap = Project(libraryName+"Android", dir / "android").settings(
        commonSettings,
        if(library) android.Plugin.androidBuildAar else android.Plugin.androidBuild,
        platformTarget in Android := "android-15",
        minSdkVersion in Android := "15",
        targetSdkVersion in Android := "15",
        crossPaths := true,
        (generateEntrypoint in Compile) := {
          if(kernelBootClass.value.isDefined) {
            val (packageDecl, entryClass) = getPackageAndClass(kernelBootClass.value.get)
            val mainApp = kernelMainClass.value.get
            val file = (sourceManaged in Compile).value / (entryClass + ".scala")
            val link = collectLinkedFilesAndroid((baseDirectory.value / "target" / "aars").listFiles())
            IO.write(file,
              s"""$packageDecl
                 |class $entryClass extends android.app.Activity {
                 |  val app = new polymorph.impl.AppImpl(new $mainApp)
                 |  override def onCreate(savedInstanceState: android.os.Bundle): Unit = {
                 |    requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                 |    polymorph.impl.AppImpl.activity = this
                 |    $link
                 |    super.onCreate(savedInstanceState)
                 |    app.main()
                 |  }
                 |}
                 |""".stripMargin('|'))
            Seq(file)
          } else
            Seq()
        },
        (generateEntrypoint in Compile) <<= (generateEntrypoint in Compile) dependsOn (dependencyClasspath in Android),
        (collectResources in Android) <<= (collectResources in Android) dependsOn copyToAssets,
        copyToAssets := {
          polymorphLinkerObject.value.foreach { linker =>
            IO.write(baseDirectory.value / "src" / "main" / "assets" / "linker.info", linker)
          }
          IO.copyDirectory(dir.getCanonicalFile/ "shared" / "src" / "main" / "resources", baseDirectory.value / "target" / "android-bin" / "assets", overwrite = true)
          IO.copyDirectory(dir.getCanonicalFile/ "shared" / "src" / "main" / "resources", baseDirectory.value / "bin" / "assets", overwrite = true)
        },
        sourceGenerators in Compile += (generateEntrypoint in Compile).taskValue,
        unmanagedSourceDirectories in Compile += dir.getAbsoluteFile / "shared" / "src" / "main" / "scala",
        unmanagedSourceDirectories in Android += dir.getAbsoluteFile / "shared" / "src" / "main" / "scala"
      )

      val cp = CrossProject(libraryName, dir, CrossType.Full)
          .settings(commonSettings: _*)
          .jsSettings(
            persistLauncher := true,
            (generateEntrypoint in Compile) := {

              if(kernelBootClass.value.isDefined) {
                val (packageDecl, entryClass) = getPackageAndClass(kernelBootClass.value.get)
                val mainApp = kernelMainClass.value.get
                val link = collectLinkedFiles((managedClasspath in Compile).value)
                val file = (sourceManaged in Compile).value / (entryClass + ".scala")
                IO.write(file,
                  s"""$packageDecl
                     |object $entryClass extends scala.scalajs.js.JSApp {
                     |  def main(): Unit = {
                     |    org.scalajs.dom.document.addEventListener("DOMContentLoaded", { e: org.scalajs.dom.Event =>
                     |      $link
                     |      new polymorph.impl.AppImpl(new $mainApp).main()
                     |    })
                     |  }
                     |}""".stripMargin('|'))
                Seq(file)
              } else
                Seq()
            },
            (fastOptJS in Compile) <<= (fastOptJS in Compile).dependsOn(copyToAssets),
            (fullOptJS in Compile) <<= (fullOptJS in Compile).dependsOn(copyToAssets),
            (resourceGenerators in Compile) <+= (resourceManaged in Compile, organization, name, version) map {
              (outDir, organization, name, version) =>
                val resourceFile =  organization+ "-" + name+ "-" +version+ ".js"
                var out = "//\n"
                try {
                  val files = recurseDirectory(dir.getCanonicalFile / "shared" / "src" / "main" / "resources")
                  if (files.nonEmpty) {
                    out += "var nxResources = nxResources ? nxResources : {};\n"
                    for ((file, name) <- files) {
                      out += "nxResources['" + name.replaceAll("'", "\\'") + "'] = '" + Base64.getEncoder.encodeToString(IO.readBytes(file)) + "';\n"
                    }
                  }
                } catch {
                  case e: Throwable =>
                }
                val outFile = outDir / resourceFile
                IO.write(outFile, out)
                Seq(outFile)
            },
            jsDependencies += ProvidedJS / (organization.value + "-" + name.value + "-" + version.value + ".js"),
            sourceGenerators in Compile += (generateEntrypoint in Compile).taskValue
          ).jvmSettings(
            fork in (Compile, run) := true,
            (generateEntrypoint in Compile) := {
              if (kernelBootClass.value.isDefined) {
                val (packageDecl, entryClass) = getPackageAndClass(kernelBootClass.value.get)
                val mainApp = kernelMainClass.value.get
                val link = collectLinkedFiles((managedClasspath in Compile).value)

                val file = (sourceManaged in Compile).value / (entryClass + ".scala")
                IO.write(file,
                  s"""$packageDecl
                     |object $entryClass extends scala.App {
                     |  $link
                     |  new polymorph.impl.AppImpl(new $mainApp).main(args)
                     |}
                     |""".stripMargin('|'))
                Seq(file)
              } else
                Seq()
            },
            assemblyMergeStrategy in assembly := {
              case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
              case _ => MergeStrategy.first
            },
            assemblyExcludedJars in assembly := {
              val cp = (fullClasspath in assembly).value
              cp.filter { cl =>
                val name = cl.data.getName
                name.contains("i586") || name.contains("armv6") || name.contains("linux") || name.contains("macos") || name.contains("solaris")
              }
            },
            unmanagedResourceDirectories in Compile += dir / "shared" / "src" / "main" / "resources",
            sourceGenerators in Compile += (generateEntrypoint in Compile).taskValue,
            mainClass in assembly := kernelBootClass.value
          )
      new PolymorphProjectSet(ios, ap, cp)
    }
  }

  // based on scala-js's crossProject class
  def polymorphApplication_impl(c: Context): c.Expr[PolymorphProjectBuilder] = {
    import c.universe._
    val enclosingValName = MacroUtils.definingValName(c, methodName =>
      s"""$methodName must be directly assigned to a val, such as `val x = $methodName`.""")
    val name = c.Expr[String](Literal(Constant(enclosingValName)))
    reify { new PolymorphProjectBuilder(name.splice, false) }
  }

  def polymorphLibrary_impl(c: Context): c.Expr[PolymorphProjectBuilder] = {
    import c.universe._
    val enclosingValName = MacroUtils.definingValName(c, methodName =>
      s"""$methodName must be directly assigned to a val, such as `val x = $methodName`.""")
    val name = c.Expr[String](Literal(Constant(enclosingValName)))
    reify { new PolymorphProjectBuilder(name.splice, true) }
  }

  object autoImport {
    val kernelMainClass = Polymorph.kernelMainClass
    val kernelBootClass = Polymorph.kernelBootClass
    val polymorphLinkerObject = Polymorph.polymorphLinkerObject

    def polylib(id: ModuleID): ModuleID =
      id.copy(name = "polymorph@" + id.name)
    def polymorphApplication: PolymorphProjectBuilder = macro polymorphApplication_impl
    def polymorphLibrary: PolymorphProjectBuilder = macro polymorphLibrary_impl
  }
}

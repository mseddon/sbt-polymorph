package scryetek.sbt.polymorph

import java.util.Base64

import android.Keys._
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

  lazy val generateEntrypoint = taskKey[Seq[File]]("Generates the boot class class for a given platform")
  lazy val copyToAssets = taskKey[Unit]("Copies the assets")

  lazy val commonSettings =  Seq(
    scalacOptions in ThisBuild ++= Seq( "-target:jvm-1.7", "-deprecation", "-feature"),
    scalaVersion := "2.11.6",
    kernelMainClass := None,
    kernelBootClass := None
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
    lazy val ios = filterSettings(_ios, "-ios")
    lazy val android = filterSettings(_android, "-android")

    def filterSettings(project: Project, postfix: String = ""): Project = {
      project.settings(libraryDependencies := libraryDependencies.value.flatMap {
          case e: ModuleID if e.name.contains("polymorph@") =>
            Seq(e.copy(name = e.name.substring(10) + postfix))
          case e: ModuleID if e.name.contains("polymorph@") =>
            Seq(e.copy(name= e.name.substring(10)))
          case e: ModuleID =>
            Seq(e)
        },
        name := name.value + postfix
      )
    }

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
      val ios = RobovmProjects.iOSProject(libraryName+"IOS", dir / "ios").settings(commonSettings: _*).settings(
        (generateEntrypoint in Compile) := {
          if(kernelBootClass.value.isDefined) {
            val (packageDecl, entryClass) = getPackageAndClass(kernelBootClass.value.get)
            val mainApp = kernelMainClass.value.get

            val file = (sourceManaged in Compile).value / (entryClass + ".scala")
            IO.write(file, s"$packageDecl\n\nobject $entryClass extends App {\n  new scryetek.gl.impl.GLAppImpl(new $mainApp())\n}\n")
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
        (generateEntrypoint in Compile) := {
          if(kernelBootClass.value.isDefined) {
            val (packageDecl, entryClass) = getPackageAndClass(kernelBootClass.value.get)
            val mainApp = kernelMainClass.value.get
            val file = (sourceManaged in Compile).value / (entryClass + ".scala")
            IO.write(file, s"$packageDecl\n\nimport android.app.Activity\nimport android.os.Bundle\nimport scryetek.gl.impl.GLAppImpl\n\nclass $entryClass extends Activity {\n  val app = new GLAppImpl(new $mainApp())\n\n  override def onCreate(savedInstanceState: Bundle): Unit = {\n    super.onCreate(savedInstanceState)\n    app.setActivity(this)\n  }\n}")
            Seq(file)
          } else
            Seq()
        },
        (collectResources in Android) <<= (collectResources in Android) dependsOn copyToAssets,
        copyToAssets := {
          IO.copyDirectory(dir.getCanonicalFile/ "shared" / "src" / "main" / "resources", baseDirectory.value / "target" / "android-bin" / "assets", overwrite = true)
        },
        sourceGenerators in Compile += (generateEntrypoint in Compile).taskValue,
        unmanagedSourceDirectories in Compile += dir.getAbsoluteFile / "shared" / "src" / "main" / "scala"
      )

      val cp = CrossProject(libraryName, dir, CrossType.Full)
          .settings(commonSettings: _*)
          .jsSettings(
            (generateEntrypoint in Compile) := {
              if(kernelBootClass.value.isDefined) {
                val (packageDecl, entryClass) = getPackageAndClass(kernelBootClass.value.get)
                val mainApp = kernelMainClass.value.get

                val file = (sourceManaged in Compile).value / (entryClass + ".scala")
                IO.write(file, s"$packageDecl\n\nimport scryetek.gl.impl.GLAppImpl\nimport scala.scalajs.js.JSApp\n\nobject $entryClass extends JSApp {\n  def main(): Unit = {\n    new GLAppImpl(new $mainApp()).main()\n  }\n}")
                Seq(file)
              } else
                Seq()
            },
            publish <<= publish.dependsOn(copyToAssets),
            (fastOptJS in Compile) <<= (fastOptJS in Compile).dependsOn(copyToAssets),
            (fullOptJS in Compile) <<= (fullOptJS in Compile).dependsOn(copyToAssets),
            copyToAssets := {
              val resourceFile =  organization.value + "-" + name.value + "-" +version.value + ".js"
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
              IO.write((resourceDirectory in Compile).value / resourceFile, out)
            },
            jsDependencies += ProvidedJS / (organization.value + "-" + name.value + "-" + version.value + ".js"),
            sourceGenerators in Compile += (generateEntrypoint in Compile).taskValue
          ).jvmSettings(
            (generateEntrypoint in Compile) := {
              if (kernelBootClass.value.isDefined) {
                val (packageDecl, entryClass) = getPackageAndClass(kernelBootClass.value.get)
                val mainApp = kernelMainClass.value.get

                val file = (sourceManaged in Compile).value / (entryClass + ".scala")
                val out = s"$packageDecl\n\nobject $entryClass extends App {\n  new scryetek.gl.impl.GLAppImpl(new $mainApp())\n}\n"
                IO.write(file, out)
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
      new PolymorphProjectSet(ios.enablePlugins(Polymorph), ap.enablePlugins(Polymorph), cp.enablePlugins(Polymorph))
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
    def polylib(id: ModuleID): ModuleID =
      id.copy(name = "polymorph@" + id.name)
    def polymorphApplication: PolymorphProjectBuilder = macro polymorphApplication_impl
    def polymorphLibrary: PolymorphProjectBuilder = macro polymorphLibrary_impl
  }
}

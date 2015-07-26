# sbt-polymorph
Scala everywhere with a single codebase

## IMPORTANT
This project is not yet officially released, and won't be ready for a while.
While this message is here *polymorph is not going to work for you*, particularly since I'm in the process
of setting up maven central access etc.

Watch this space!

## Quickstart

First, you want to include the following in your `project/plugins.sbt`

```scala
addSbtPlugin("com.scryetek" % "sbt-polymorph" % "0.1")
```

Next, create a simple `build.sbt` that creates a polymorph application project:

```scala
name := "my-first-polymorph-app"

version := "0.0.1-SNAPSHOT"

// Use polymorphApplication to generate a cross platform application project.
// If you wish to make a library, instead, use a polymorphLibrary
lazy val myProject = polymorphApplication.in(file(".")).settings(
  // It is always good practice to specify a scala version!
  scalaVersion := "2.11.6",

  // Specify your GLApp class here
  kernelMainClass := "com.example.MainApp",

  // Specify the name of the generated App/JSApp/Activity that will run your app.
  kernelBootClass := "com.example.Main",

  // add any other settings/dependencies here that will be shared across all projects.
  // note: library dependencies should use %%% syntax so that scala-js can resolve correctly.

  // cross platform libraries must be additionally wrapped in polylib, to help us locate
  // platform specific libs.  You *must* have polymorph-core in your library dependencies or
  // there will be tears before bedtime
  libraryDependencies += polylib("com.scryetek" %%% "polymorph-core" % "0.1-SNAPSHOT")
)

// You may also additionally override specific subproject settings using:
//   .jsSettings(..)
//   .jvmSettings(..)
//   .iosSettings(..)
//   .androidSettings(..)

// You must assign each platform you wish to support to a global val here
// or sbt will not see them.  Bonus- if you don't specify a platform, it won't be built.
lazy val myProjectJS = myProject.js
lazy val myProjectJVM = myProject.jvm
lazy val myProjectAndroid = myProject.android
lazy val myProjectIOS= myProject.ios

/*
 * Finally -
 *
 * To run each project, use:
 *    myProjectJS/fastOptJS
 *    myProjectJVM/run
 *    myProjectAndroid/android:run
 *    myProjectIOS/simulator
 */
```

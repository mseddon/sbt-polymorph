name := "sbt-polymorph"

organization := "com.scryetek"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.5"

sbtPlugin := true

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.5")

addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.4.7")

addSbtPlugin("org.roboscala" % "sbt-robovm" % "1.6.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.13.0")

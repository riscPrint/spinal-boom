ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "boom"

val spinalVersion = "1.15.0"

lazy val root = (project in file("."))
  .settings(
    name := "spinal-boom",
    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion,
      compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion),
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature", "-language:reflectiveCalls"),
    Compile / run / fork := true,
    Test / fork := true,
    Test / parallelExecution := false
  )

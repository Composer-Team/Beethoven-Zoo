ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "local"

val chiselVersion = "7.1.0"
val beethovenHardwareVersion = "latest.integration"

lazy val root = (project in file("."))
  .settings(
    name := "beethoven-gameboy",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.duke.cs.apex" %% "beethoven-hardware" % beethovenHardwareVersion,
      "com.github.tototoshi" %% "scala-csv" % "1.3.10"
    ),
    Compile / unmanagedSourceDirectories +=
      baseDirectory.value / "hw" / "src" / "main" / "scala",
    Compile / unmanagedResourceDirectories +=
      baseDirectory.value / "hw" / "src" / "main" / "resources",
    Compile / mainClass := Some("beethoven.cli.Run"),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations"
    ),
    resolvers += ("reposilite-repository-releases" at "http://54.165.244.214:8080/releases").withAllowInsecureProtocol(true),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
  )

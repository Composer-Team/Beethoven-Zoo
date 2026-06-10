ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "local"

val chiselVersion = "3.5.6"
val chiselPlugin = file("/home/mason/.cache/coursier/v1/https/repo1.maven.org/maven2/edu/berkeley/cs/chisel3-plugin_2.13.10/3.5.6/chisel3-plugin_2.13.10-3.5.6.jar")

lazy val root = (project in file("."))
  .settings(
    name := "beethoven-a3",
    target := baseDirectory.value / "target" / ".sbt",
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "hw" / "src" / "main" / "scala"),
    Test / unmanagedSourceDirectories := Seq.empty,
    Compile / mainClass := Some("A3ZooBuild"),
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3" % chiselVersion,
      "edu.duke.cs.apex" %% "beethoven-hardware" % "0.0.34"
    ),
    resolvers += ("reposilite-repository-releases" at "http://54.165.244.214:8080/releases").withAllowInsecureProtocol(true),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      s"-Xplugin:${chiselPlugin.getAbsolutePath}"
    ),
    run / fork := true,
    run / envVars += (
      "BEETHOVEN_PATH" -> (baseDirectory.value / "target" / "legacy-root").getAbsolutePath
    )
  )

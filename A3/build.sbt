ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "local"

val chiselVersion = "7.5.0"
val beethovenHardwareVersion = "latest.integration"
lazy val fixedpointProject = RootProject(uri("https://github.com/ucb-bar/fixedpoint.git#4e53e281b21bdd1a8940601b5c23139fe1ec8848"))

lazy val root = (project in file("."))
  .dependsOn(fixedpointProject)
  .settings(
    name := "beethoven-a3",
    target := baseDirectory.value / "target" / ".sbt",
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "hw" / "src" / "main" / "scala"),
    Test / unmanagedSourceDirectories := Seq.empty,
    Compile / mainClass := Some("beethoven.cli.Run"),
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.duke.cs.apex" %% "beethoven-hardware" % beethovenHardwareVersion
    ),
    resolvers += ("reposilite-repository-releases" at "http://54.165.244.214:8080/releases").withAllowInsecureProtocol(true),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations"
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
  )

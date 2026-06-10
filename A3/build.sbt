ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "local"

val chiselVersion = "7.5.0"
val beethovenHardwarePath = file("../../Beethoven-Hardware")
lazy val beethovenHardware = RootProject(beethovenHardwarePath)
val fixedpointPath = file("../../fixedpoint")
lazy val fixedpointProject = RootProject(fixedpointPath)


lazy val root = (project in file("."))
  .dependsOn(beethovenHardware, fixedpointProject)
  .settings(
    name := "beethoven-a3",
    target := baseDirectory.value / "target" / ".sbt",
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "hw" / "src" / "main" / "scala"),
    Test / unmanagedSourceDirectories := Seq.empty,
    Compile / mainClass := Some("beethoven.cli.Run"),
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion
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

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "local"

val chiselVersion = "7.1.0"

val beethovenHardwarePath = sys.env
  .get("BEETHOVEN_HARDWARE")
  .map(file)
  .getOrElse {
    Seq(
      file("../../Beethoven-Hardware"), // Beethoven-Zoo cloned next to Beethoven-Hardware
      file("../Beethoven-Hardware")     // legacy in-tree checkout layout
    ).find(_.exists()).getOrElse(file("../../Beethoven-Hardware"))
  }

lazy val beethovenHardware = RootProject(beethovenHardwarePath)

lazy val root = (project in file("."))
  .dependsOn(beethovenHardware)
  .settings(
    name := "beethoven-dot-product",
    target := baseDirectory.value / "target" / ".sbt",
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "hw" / "src" / "main" / "scala"),
    Test / unmanagedSourceDirectories := Seq.empty,
    Compile / mainClass := Some("beethoven.cli.Run"),
    libraryDependencies += "org.chipsalliance" %% "chisel" % chiselVersion,
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

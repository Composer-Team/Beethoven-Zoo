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
    name := "beethoven-gameboy",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
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
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
  )

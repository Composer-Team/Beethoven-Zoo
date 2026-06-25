ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "local"

val chiselVersion = "7.1.0"
val beethovenHardwareVersion = "latest.integration"

lazy val root = (project in file("."))
  .settings(
    name := "beethoven-dot-product",
    target := baseDirectory.value / "target" / ".sbt",
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "hw" / "src" / "main" / "scala"),
    Test / unmanagedSourceDirectories := Seq.empty,
    Compile / mainClass := Some("beethoven.cli.Run"),
    libraryDependencies += "org.chipsalliance" %% "chisel" % chiselVersion,
    // Use the locally published beethoven-hardware artifact (resolved from
    // ~/.ivy2/local) instead of a RootProject source dependency on the stale
    // ../../Beethoven-Hardware checkout, which was on the old chisel-3.5 line.
    libraryDependencies += "edu.duke.cs.apex" %% "beethoven-hardware" % beethovenHardwareVersion,
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

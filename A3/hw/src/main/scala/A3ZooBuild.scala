import beethoven.{BeethovenBuild, BuildMode}
import beethoven.Platforms.FPGA.Xilinx.AWS.AWSF1Platform

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters._

object A3ZooBuild {
  private def projectRoot: Path = Paths.get("").toAbsolutePath.normalize()

  private def modeFrom(args: Array[String]): String = {
    args.sliding(2).collectFirst {
      case Array("--mode", mode) => mode
    }.getOrElse("simulation")
  }

  private def copyTree(from: Path, to: Path): Unit = {
    if (!Files.exists(from)) {
      throw new IllegalStateException(s"missing generated tree: $from")
    }
    if (Files.exists(to)) {
      Files.walk(to).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
    Files.createDirectories(to)
    Files.walk(from).iterator().asScala.foreach { src =>
      val rel = from.relativize(src)
      val dst = to.resolve(rel.toString)
      if (Files.isDirectory(src)) {
        Files.createDirectories(dst)
      } else {
        Files.createDirectories(dst.getParent)
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  def main(args: Array[String]): Unit = {
    new BeethovenBuild(
      config = new design.A3.A3Config,
      platform = new AWSF1Platform(1),
      buildMode = BuildMode.Simulation
    ).main(args.filter(_.startsWith("-D")))

    val mode = modeFrom(args)
    val legacyBuildRoot = projectRoot.resolve("target").resolve("legacy-root").resolve("build")
    val bindingRoot = projectRoot.resolve("target").resolve("binding")
    val hwRoot = projectRoot.resolve("target").resolve(mode).resolve("hw")

    Files.createDirectories(bindingRoot)
    Files.copy(
      legacyBuildRoot.resolve("beethoven_hardware.h"),
      bindingRoot.resolve("beethoven_hardware.h"),
      StandardCopyOption.REPLACE_EXISTING
    )
    Files.copy(
      legacyBuildRoot.resolve("beethoven_hardware.cc"),
      bindingRoot.resolve("beethoven_hardware.cc"),
      StandardCopyOption.REPLACE_EXISTING
    )
    copyTree(legacyBuildRoot.resolve("hw"), hwRoot)
  }
}

import sbt._
import Keys._

object ApplicationBuild extends Build {

  lazy val plugin = Project (
    id = "plugin",
    base = file ("plugin")
  ).settings(
    Seq(
      name := "play-rproxy",
      organization := "com.github.iwaiawi",
      version := "0.0.2",
      scalaVersion := "2.11.5",
      resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
      libraryDependencies ++= Seq(
        "com.typesafe.play" %% "play" % play.core.PlayVersion.current % "provided",
        "com.typesafe.play" %% "play-cache" % play.core.PlayVersion.current,
        "com.typesafe.play" %% "play-ws" % play.core.PlayVersion.current
      ),
      scalacOptions ++= Seq("-language:_", "-deprecation")
    ) ++ publishingSettings :_*
  )

  val sampleAppDependencies = Seq(
	  "com.typesafe.play" %% "play-cache" % play.core.PlayVersion.current,
	  "com.typesafe.play" %% "play-ws" % play.core.PlayVersion.current
  )

  val sampleAppName = "sample"
  val sampleAppVersion = "1.0-SNAPSHOT"

  lazy val sample = Project(
    sampleAppName,
    file("sample")
  ).enablePlugins(play.PlayScala).settings(
    resourceDirectories in Test += baseDirectory.value / "conf",
    scalaVersion := "2.11.5",
    version := sampleAppVersion,
    resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
    libraryDependencies ++= sampleAppDependencies
  )
  .dependsOn(plugin)
  .aggregate(plugin)

  val publishingSettings = Seq(
    publishMavenStyle := true,
    publishTo <<= version { (v: String) => _publishTo(v) },
    publishArtifact in Test := false,
    pomExtra := _pomExtra
  )

  def _publishTo(v: String) = {
//    val nexus = "https://oss.sonatype.org/"
//    if (v.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus + "content/repositories/snapshots")
//    else Some("releases" at nexus + "service/local/staging/deploy/maven2")
    Some(Resolver.file("currentDir", file("./publish")))
  }

  val _pomExtra =
    <url>http://github.com/iwaiawi/play-rproxy</url>
    <licenses>
      <license>
        <name>Apache License, Version 2.0</name>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:iwaiawi/play-rproxy.git</url>
      <connection>scm:git:git@github.com:iwaiawi/play-rproxy.git</connection>
    </scm>
    <developers>
      <developer>
        <id>iwaiawi</id>
        <name>iwaiawi</name>
        <url>http://iwaiawi.github.com</url>
      </developer>
    </developers>

}

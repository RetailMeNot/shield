enablePlugins(JavaAppPackaging)

enablePlugins(UniversalPlugin)

enablePlugins(BuildInfoPlugin)

name := "Shield"

organization := "com.retailmenot"

version := "0.3-SNAPSHOT"

scalaVersion := "2.11.7"

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

resolvers ++= Seq(
  "rediscala" at "http://dl.bintray.com/etaty/maven"
)

libraryDependencies ++= {
  val akkaV = "2.3.9"
  val sprayV = "1.3.3"
  Seq(
    "io.spray" %% "spray-can" % sprayV,
    "io.spray" %% "spray-client" % sprayV,
    "io.spray" %% "spray-json" % "1.3.1",
    "io.spray" %% "spray-testkit" % sprayV % "test",
    "io.swagger" % "swagger-parser" % "1.0.19",
    "io.swagger" % "swagger-compat-spec-parser" % "1.0.2",
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
    "com.miguno.akka" % "akka-mock-scheduler_2.11" % "0.4.0",
    "com.etaty.rediscala" %% "rediscala" % "1.5.0",
    "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test",
    "com.google.protobuf" % "protobuf-java" % "2.6.1",
    "com.googlecode.concurrentlinkedhashmap" % "concurrentlinkedhashmap-lru" % "1.3.1",
    "org.specs2" %% "specs2-core" % "2.4.17" % "test",
    "ch.qos.logback" % "logback-classic" % "1.0.9",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    "nl.grons" %% "metrics-scala" % "3.5.1_a2.3",
    "io.dropwizard.metrics" % "metrics-json" % "3.1.2",
    "io.dropwizard.metrics" % "metrics-jvm" % "3.1.2",
    "com.amazonaws" % "aws-java-sdk-s3" % "1.11.22",
    "com.amazonaws" % "aws-java-sdk-sts" % "1.11.22",
    "com.amazonaws" % "aws-java-sdk-lambda" % "1.11.22"
  )
}

fork in run := true

Revolver.settings

buildInfoPackage := "shield.build"

buildInfoUsePackageAsPath := true

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoOptions ++= Seq(BuildInfoOption.ToMap, BuildInfoOption.ToJson, BuildInfoOption.BuildTime)

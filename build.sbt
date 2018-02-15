// Copyright 2018 Vy-Shane Xie Sin Fat

name := "grpc-oidc"
version := sys.env.get("VERSION").getOrElse("0.1-SNAPSHOT")
description := "OpenID Connect integration for gRPC"
organization := "mu.node"
licenses += ("Apache-2.0", url("https://choosealicense.com/licenses/apache-2.0/"))

scalaVersion := "2.12.4"
scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

resolvers += Resolver.jcenterRepo

libraryDependencies ++= Seq(
  // Configuration
  "com.typesafe" % "config" % "1.3.2",

  // gRPC
  "io.grpc" % "grpc-netty-shaded" % "1.9.1",
  "io.grpc" % "grpc-stub" % "1.9.1",

  // OpenID Connect
  "com.nimbusds" % "oauth2-oidc-sdk" % "5.52",

  // Test
  "org.scalatest" %% "scalatest" % "3.0.4" % Test,
  "com.whisk" %% "docker-testkit-scalatest" % "0.10.0-beta4" % Test,
  "com.whisk" %% "docker-testkit-impl-spotify" % "0.9.6" % Test,
  "com.trueaccord.scalapb" %% "scalapb-runtime-grpc" % "0.6.7" % Test,
  "com.trueaccord.scalapb" %% "scalapb-runtime" % "0.6.7" % "protobuf",
  "org.nanohttpd" % "nanohttpd" % "2.3.1" % Test,
  "org.jsoup" % "jsoup" % "1.11.2" % Test,
  "com.github.javafaker" % "javafaker" % "0.14" % Test,
  "com.github.t3hnar" %% "scala-bcrypt" % "3.0" % Test
)

// Shade dependencies
assemblyShadeRules in assembly ++= Seq(
  ShadeRule.rename("com.typesafe.config.**" -> "mu.node.shaded.@0").inAll,
)

// Protobuf/gRPC code generation
inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings)
PB.targets in Test := Seq(
  scalapb.gen(grpc=true, flatPackage=true) -> (sourceManaged in Test).value
)

// Code formatting
scalafmtConfig := file(".scalafmt.conf")

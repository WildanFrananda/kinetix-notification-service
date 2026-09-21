ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "com.kinetix"
ThisBuild / version := IO.read(file("VERSION")).trim

val catsEffectV = "3.5.7"
val fs2V        = "3.11.0"
val http4sV     = "0.23.30"
val circeV      = "0.14.10"
val doobieV     = "1.0.0-RC6"
val log4catsV   = "2.7.0"
val munitCatsV  = "2.0.0"

lazy val root = (project in file("."))
  .enablePlugins(Fs2Grpc)
  .settings(
    name := "kinetix-notification-service",

    // Only the protos this service speaks — common, identity and notification — narrowed by
    // bin/sync-contracts when it fetches them. See that script for why the narrowing lives there
    // and not here.
    Compile / PB.protoSources := Seq(file(".contracts/spoken")),

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-explain",
      "-source:3.3",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Wnonunit-statement",
      "-Xfatal-warnings",
      "-Wconf:src=.*src_managed.*:silent"
    ),

    libraryDependencies ++= Seq(
      "org.typelevel"  %% "cats-effect"          % catsEffectV,
      "co.fs2"         %% "fs2-core"             % fs2V,
      "org.http4s"     %% "http4s-ember-client"  % http4sV,
      "org.http4s"     %% "http4s-ember-server"  % http4sV,
      "org.http4s"     %% "http4s-dsl"           % http4sV,
      "org.http4s"     %% "http4s-circe"         % http4sV,
      "io.circe"       %% "circe-core"           % circeV,
      "io.circe"       %% "circe-generic"        % circeV,
      "io.circe"       %% "circe-parser"         % circeV,
      "org.tpolecat"   %% "doobie-core"          % doobieV,
      "org.tpolecat"   %% "doobie-hikari"        % doobieV,
      "org.tpolecat"   %% "doobie-postgres"      % doobieV,
      "org.typelevel"  %% "log4cats-slf4j"       % log4catsV,
      "ch.qos.logback"  % "logback-classic"      % "1.5.15",
      "io.grpc"         % "grpc-netty-shaded"    % "1.69.0",

      "org.typelevel"  %% "munit-cats-effect"    % munitCatsV % Test
    ),

    Test / fork := true,
    run / fork := true,

    assembly / assemblyJarName := "kinetix-notification-service.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*)     => MergeStrategy.concat
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
      case PathList("META-INF", _*)                 => MergeStrategy.discard
      case "module-info.class"                      => MergeStrategy.discard
      case "reference.conf"                         => MergeStrategy.concat
      case _                                        => MergeStrategy.first
    }
  )

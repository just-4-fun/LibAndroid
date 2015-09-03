lazy val root = project.in(file("."))
  .settings(
      name := "LibAndroid"
      , organization := "just4fun"
      , version := "1.0-SNAPSHOT"
      , scalaVersion := "2.11.6"
      , licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php"))
      , homepage := Some(url("https://github.com/just-4-fun"))
      , scalacOptions in Compile += "-feature"
      , javacOptions ++= Seq("-source", "1.7", "-target", "1.7")
      , install <<= install in Android in test
      , run <<= run in Android in test
  )
  .settings(androidCommands)
  .aggregate(test, lib, utils)

lazy val lib = project.in(file("Lib"))
  .settings(androidBuild: _*)
  .settings(commonSettings: _*)
  .settings(paradiseSettings: _*)
  .settings(
      name := "Lib"
      , minSdkVersion := "14"
      , libraryProject in Android := true
      //	, exportJars := true
      //	, mainClass in(Test, run) := Some("j4f.test.Test") // command > test:run
  )
  .settings(proguardSettings: _*)
  .settings(testSettings: _*)
  .settings(dependencies)
  .dependsOn(utils)

lazy val test = project.in(file("Test"))
  .settings(commonSettings: _*)
  .settings(paradiseSettings: _*)
  .settings(
      name := "Test"
      , minSdkVersion := "14"
      //      , androidBuild(libandroid)
      //      , localProjects in Android += LibraryProject(libandroid.base)
  )
  //  .dependsOn(libandroid)
  .dependsOn(utils)
  .androidBuildWith(lib)

lazy val utils = RootProject(file("../../Utils"))

lazy val commonSettings = Seq(
	scalaVersion := "2.11.6"
	, platformTarget in Android := "android-22"
	, typedResources in Android := false
	, scalacOptions in Compile += "-feature"
	, javacOptions ++= Seq("-source", "1.7", "-target", "1.7")
)

lazy val proguardSettings = Seq(
	proguardOptions in Android ++= Seq("-keepattributes Signature", "-dontwarn scala.collection.**")
)

lazy val testSettings = Seq(
	publishArtifact in Test := false
)

lazy val dependencies = Seq(
	//	, libraryDependencies += "just4fun" %% "utils" % "1.0-SNAPSHOT"
)

lazy val paradiseSettings = Seq(
	resolvers += Resolver.sonatypeRepo("snapshots")
	, resolvers += Resolver.sonatypeRepo("releases")
	, addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0-M5" cross CrossVersion.full)
	, libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-reflect" % _)
)

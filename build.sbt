lazy val root = project.in(file("."))
  .settings(
	  name := "LibAndroid"
	  , organization := "just4fun"
	  , version := "1.0-SNAPSHOT"
	  , scalaVersion := "2.11.7"
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
   .settings(
	  name := "Lib"
	  , androidBuild
	  , libraryProject in Android := true
//	  ,androidBuildAar
	  , transitiveAndroidLibs in Android := false
//	  	, exportJars := true
	  //	, mainClass in(Test, run) := Some("j4f.test.Test") // command > test:run
  )
  .settings(commonSettings: _*)
  .settings(paradiseSettings: _*)
  .settings(proguardSettings: _*)
  .settings(testSettings: _*)
//  .settings(libDependencies: _*)
  .settings(dependencies: _*)
  .dependsOn(utils)

lazy val test = project.in(file("Test"))
  .settings(
	  name := "Test"
	  //	  	  , androidBuild
	  //	  , androidBuild(lib)
	  //	  , localProjects in Android += LibraryProject(lib.base)
	  //	 , exportJars := true
  )
  .settings(commonSettings: _*)
  .settings(paradiseSettings: _*)
  .settings(dependencies: _*)
  .dependsOn(utils)
  .androidBuildWith(lib)
//  .dependsOn(lib)

lazy val utils = RootProject(file("../../Utils"))

lazy val commonSettings = Seq(
	scalaVersion := "2.11.7"
	, minSdkVersion := "14"
	, targetSdkVersion := "23"
	, platformTarget in Android := "android-23"
	, typedResources in Android := false
	, scalacOptions in Compile += "-feature"
	, javacOptions ++= Seq("-source", "1.7", "-target", "1.7")
)

lazy val proguardSettings = Seq(
	proguardOptions in Android ++= Seq("-keepattributes Signature"
		, "-dontwarn scala.collection.**"
	)
)

lazy val testSettings = Seq(
	publishArtifact in Test := false
)

//lazy val libDependencies = Seq(
//	libraryDependencies += "com.android.support" % "support-v4" % "23.0.1"
//	//	, libraryDependencies += "just4fun" %% "utils" % "1.0-SNAPSHOT"
//)

lazy val dependencies = Seq(
	libraryDependencies += "just4fun" %% "logger" % "1.0-SNAPSHOT"
	//	, libraryDependencies += "just4fun" %% "utils" % "1.0-SNAPSHOT"
)

lazy val paradiseSettings = Seq(
	resolvers += Resolver.sonatypeRepo("snapshots")
	, resolvers += Resolver.sonatypeRepo("releases")
	, addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0-M5" cross CrossVersion.full)
	, libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-reflect" % _)
)

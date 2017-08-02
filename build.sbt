import de.heikoseeberger.sbtheader.license.BSD3Clause

lazy val root = (project in file(".")).enablePlugins(PlayScala, AutomateHeaderPlugin)

name := "get-you-a-license"

scalaVersion := "2.12.3"

libraryDependencies ++= Seq(
  ws,
  "org.webjars" %% "webjars-play" % "2.6.1",
  "org.webjars.npm" % "salesforce-ux__design-system" % "2.3.1",
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.1" % Test
)

headers := Map(
  "scala" -> BSD3Clause("2017", "salesforce.com, inc."),
  "conf" -> BSD3Clause("2017", "salesforce.com, inc.", "#"),
  "html" -> BSD3Clause("2017", "salesforce.com, inc.", "@*")
)

unmanagedSources.in(Compile, createHeaders) ++= sources.in(Compile, TwirlKeys.compileTemplates).value

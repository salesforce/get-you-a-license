import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

lazy val root = (project in file(".")).enablePlugins(PlayScala, AutomateHeaderPlugin)

name := "get-you-a-license"

scalaVersion := "2.12.4"

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  ws,
  "org.webjars" %% "webjars-play" % "2.6.3",
  "org.webjars" % "salesforce-lightning-design-system" % "2.5.2",
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.1" % Test
)

headerMappings += FileType("html") -> HeaderCommentStyle.TwirlStyleBlockComment

unmanagedSources.in(Compile, headerCreate) ++= sources.in(Compile, TwirlKeys.compileTemplates).value

organizationName := "salesforce.com, inc."

startYear := Some(2018)

licenses += "BSD-3-Clause" -> new URL("https://opensource.org/licenses/BSD-3-Clause")

licenseConfigurations := Set("runtime")

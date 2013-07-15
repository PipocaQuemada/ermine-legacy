package com.clarifi.reporting.project

import sbt._
import Keys._

object ErmineBuild extends Build {
  val allUnmanagedResourceDirectories = SettingKey[Seq[File]]("all-unmanaged-resource-directories", "unmanaged-resource-directories, transitively.")
  val getLibraryPath      = TaskKey[Unit]("get-library-path", "Gets the java.library.path environment variable")
  val main                = InputKey[Unit]("main", "Run the test Main class")
  val editor              = InputKey[Unit]("editor", "Run the Ermine Editor")

  lazy val ermine = Project( id = "ermine" , base = file("."), settings = projectSettings :+
    fullRunInputTask(repl, Compile, "com.clarifi.reporting.ermine.session.Console")).dependsOn(machines)

  lazy val machines = {
    // force sbt to get the latest version of scala-machines
    // 'sbt update' doesn't seem to get the latest even though this says that it should
    // http://stackoverflow.com/questions/8864317/how-do-i-refresh-updated-git-dependency-artifacts-in-sbt
    // so instead we have to go to github and get the latest version.
    val sha = scala.io.Source.fromURL("https://api.github.com/repos/runarorama/scala-machines/commits?sha=master").
      takeWhile(_ != ',').mkString.dropWhile(_!=':').drop(2).dropRight(1)
    RootProject(uri("https://github.com/runarorama/scala-machines.git#" + sha))
  }

  private lazy val projectSettings =
    Defaults.defaultSettings ++ Seq[SettingsDefinition](
        scalaVersion := "2.10.2",
        compileTestRuntime(sc => classpathConfiguration in sc := sc)
       ,mainClass in (Compile, run) := Some("com.clarifi.reporting.ermine.session.Console")
       ,compileTestRuntime(sco => allUnmanagedResourceDirectories in sco <<=
          (Defaults.inDependencies(unmanagedResourceDirectories in sco, _ => Seq.empty)
           (_.reverse.flatten)))
        // Usually, resources end up in the classpath by virtue of `compile'
        // copying them into target/scala-*/classes, and from there into jar.  But
        // we want in development (1) I can edit an Ermine module in src
        // resources, hit reload, and it's seen, and (2) I can edit CSS/JS, reload
        // the HTML, and it's seen.  So we (harmlessly) patch the src resources
        // dirs in *before* the classes dirs, so they will win in the classloader
        // lookup.
       ,compileTestRuntime(sco =>
          fullClasspath in sco <<= (allUnmanagedResourceDirectories in sco,
                                    fullClasspath in sco) map {
            (urd, fc) => Attributed.blankSeq(urd) ++ fc
        })
      ) flatMap (_.settings)

  /** Multiply a setting across Compile, Test, Runtime. */
  def compileTestRuntime[A](f: Configuration => Setting[A]): SettingsDefinition =
    seq(f(Compile), f(Test), f(Runtime))

  /** Filter messages sent through loggers produced by `coreLogMgr`.
    *
    * @param logf Invoke for each acquired logger; invoke its result
    *             for each log message that comes in.
    */
  def filterLogs(coreLogMgr: LogManager
               )(logf: AbstractLogger => (Level.Value, => String) => Unit): LogManager =
    new LogManager {
      def apply(data: Settings[Scope], state: State,
                task: ScopedKey[_], writer: java.io.PrintWriter): Logger = {
        coreLogMgr(data, state, task, writer) match {
          case coreLog: AbstractLogger => new MultiLogger(List(coreLog)) {
            private val logfn = logf(coreLog)
            override def log(level: Level.Value, message: => String) =
              logfn(level, message)
          }
          case log => log
        }
      }
    }
}

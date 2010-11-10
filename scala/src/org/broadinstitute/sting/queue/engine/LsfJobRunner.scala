package org.broadinstitute.sting.queue.engine

import java.io.File
import org.broadinstitute.sting.queue.function.CommandLineFunction
import org.broadinstitute.sting.queue.util._
import org.apache.commons.io.FileUtils

/**
 * Runs jobs on an LSF compute cluster.
 */
class LsfJobRunner(val function: CommandLineFunction) extends DispatchJobRunner with Logging {
  private var runStatus: RunnerStatus.Value = _

  var job: LsfJob = new LsfJob

  /** A file to look for to validate that the function ran to completion. */
  private var jobStatusPath: String = _

  /** A temporary job done file to let Queue know that the process ran successfully. */
  private lazy val jobDoneFile = new File(jobStatusPath + ".done")

  /** A temporary job done file to let Queue know that the process exited with an error. */
  private lazy val jobFailFile = new File(jobStatusPath + ".fail")

  /** A generated exec shell script. */
  private var exec: File = _

  /** A generated pre-exec shell script. */
  private var preExec: File = _

  /** A generated post-exec shell script. */
  private var postExec: File = _

  /**
   * Dispatches the function on the LSF cluster.
   * @param function Command to run.
   */
  def start() = {
    try {
      function.mkOutputDirectories()

      // job.name = function.jobName TODO: Make setting the job name optional.
      job.outputFile = function.jobOutputFile
      job.errorFile = function.jobErrorFile
      job.project = function.jobProject
      job.queue = function.jobQueue

      if (!IOUtils.CURRENT_DIR_ABS.equals(function.commandDirectory))
        job.workingDir = function.commandDirectory

      job.extraBsubArgs ++= function.extraArgs

      if (function.jobRestartable)
        job.extraBsubArgs :+= "-r"

      if (function.memoryLimit.isDefined)
        job.extraBsubArgs ++= List("-R", "rusage[mem=" + function.memoryLimit.get + "]")

      job.name = function.commandLine.take(1000)

      exec = writeExec()
      job.command = "sh " + exec

      preExec = writePreExec()
      job.preExecCommand = "sh " + preExec

      postExec = writePostExec()
      job.postExecCommand = "sh " + postExec

      if (logger.isDebugEnabled) {
        logger.debug("Starting: " + function.commandDirectory + " > " + job.bsubCommand.mkString(" "))
      } else {
        logger.info("Starting: " + job.bsubCommand.mkString(" "))
      }

      function.deleteLogs()
      function.deleteOutputs()

      runStatus = RunnerStatus.RUNNING
      Retry.attempt(() => job.run(), 1, 5, 10)
      jobStatusPath = IOUtils.absolute(new File(function.commandDirectory, "." + job.bsubJobId)).toString
      logger.info("Submitted LSF job id: " + job.bsubJobId)
    } catch {
      case e =>
        runStatus = RunnerStatus.FAILED
        try {
          removeTemporaryFiles()
          function.failOutputs.foreach(_.createNewFile())
          writeStackTrace(e)
        } catch {
          case _ => /* ignore errors in the exception handler */
        }
        logger.error("Error: " + job.bsubCommand.mkString(" "), e)
    }
  }

  /**
   * Updates and returns the status by looking for job status files.
   * After the job status files are detected they are cleaned up from
   * the file system and the status is cached.
   *
   * Note, these temporary job status files are currently different from the
   * .done files used to determine if a file has been created successfully.
   */
  def status = {
    try {
      if (logger.isDebugEnabled) {
        logger.debug("Done %s exists = %s".format(jobDoneFile, jobDoneFile.exists))
        logger.debug("Fail %s exists = %s".format(jobFailFile, jobFailFile.exists))
      }

      if (jobFailFile.exists) {
        removeTemporaryFiles()
        runStatus = RunnerStatus.FAILED
        logger.info("Error: " + job.bsubCommand.mkString(" "))
        tailError()
      } else if (jobDoneFile.exists) {
        removeTemporaryFiles()
        runStatus = RunnerStatus.DONE
        logger.info("Done: " + job.bsubCommand.mkString(" "))
      }
    } catch {
      case e =>
        runStatus = RunnerStatus.FAILED
        try {
          removeTemporaryFiles()
          function.failOutputs.foreach(_.createNewFile())
          writeStackTrace(e)
        } catch {
          case _ => /* ignore errors in the exception handler */
        }
        logger.error("Error: " + job.bsubCommand.mkString(" "), e)
    }

    runStatus
  }

  /**
   * Removes all temporary files used for this LSF job.
   */
  def removeTemporaryFiles() = {
    IOUtils.tryDelete(exec)
    IOUtils.tryDelete(preExec)
    IOUtils.tryDelete(postExec)
    IOUtils.tryDelete(jobDoneFile)
    IOUtils.tryDelete(jobFailFile)
  }

  /**
   * Outputs the last lines of the error logs.
   */
  private def tailError() = {
    val errorFile = if (job.errorFile != null) job.errorFile else job.outputFile
    if (FileUtils.waitFor(errorFile, 120)) {
      val tailLines = IOUtils.tail(errorFile, 100)
      val nl = "%n".format()
      logger.error("Last %d lines of %s:%n%s".format(tailLines.size, errorFile, tailLines.mkString(nl)))
    } else {
      logger.error("Unable to access log file: %s".format(errorFile))
    }
  }

  /**
   * Writes an exec file to cleanup any status files and
   * optionally mount any automount directories on the node.
   * @return the file path to the pre-exec.
   */
  private def writeExec() = {
    IOUtils.writeTempFile(function.commandLine, ".exec", "")
  }

  /**
   * Writes a pre-exec file to cleanup any status files and
   * optionally mount any automount directories on the node.
   * @return the file path to the pre-exec.
   */
  private def writePreExec() = {
    val preExec = new StringBuilder

    preExec.append("rm -f '%s/'.$LSB_JOBID.done%n".format(function.commandDirectory))
    function.doneOutputs.foreach(file => preExec.append("rm -f '%s'%n".format(file)))
    preExec.append("rm -f '%s/'.$LSB_JOBID.fail%n".format(function.commandDirectory))
    function.failOutputs.foreach(file => preExec.append("rm -f '%s'%n".format(file)))

    mountCommand(function).foreach(command =>
      preExec.append("%s%n".format(command)))

    IOUtils.writeTempFile(preExec.toString, ".preExec", "")
  }

  /**
   * Writes a post-exec file to create the status files.
   * @return the file path to the post-exec.
   */
  private def writePostExec() = {
    val postExec = new StringBuilder

    val touchDone = function.doneOutputs.map("touch '%s'%n".format(_)).mkString
    val touchFail = function.failOutputs.map("touch '%s'%n".format(_)).mkString

    postExec.append("""|
  |if [ "${LSB_JOBPEND:-unset}" != "unset" ]; then
  |  exit 0
  |fi
  |
  |JOB_STAT_ROOT='%s/'.$LSB_JOBID
  |if [ "$LSB_JOBEXIT_STAT" == "0" ]; then
  |%stouch "$JOB_STAT_ROOT".done
  |else
  |%stouch "$JOB_STAT_ROOT".fail
  |fi
  |""".stripMargin.format(function.commandDirectory, touchDone, touchFail))

    IOUtils.writeTempFile(postExec.toString, ".postExec", "")
  }
}

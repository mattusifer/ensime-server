package org.ensime.server

import java.io._
import java.net.{ ServerSocket, Socket, InetAddress }
import akka.actor.{ ActorRef, Actor, Props, ActorSystem }
import org.ensime.protocol._
import org.ensime.util.{ SExpParser, SExp, WireFormat }
import org.ensime.config.{ ProjectConfig, Environment }
import org.slf4j._
import scala.io.Source
import scala.util.Properties
import scala.util.Properties._
import org.slf4j.bridge.SLF4JBridgeHandler

/**
 * For when your upstream dependencies can't be trusted with
 * stdout and stderr.
 */
object ConsoleOutputWorkaround {
  def redirectScalaConsole(): Unit = {
    // workaround SI-8717
    ConsoleRedirect.setOut(OutLog)
    ConsoleRedirect.setErr(ErrLog)
  }

  private val blacklist = Set("sun.", "java.", "scala.Console", "scala.Predef")
  private abstract class StreamToLog extends OutputStream {
    val buffer = new StringBuilder

    override def write(b: Int): Unit = try {
      val c = b.toChar
      if (c == '\n') {
        val message = buffer.toString()
        buffer.clear()

        // reasonably expensive, but not as bad as printing to the
        // screen (which is very slow and blocking)
        val breadcrumbs = Thread.currentThread.getStackTrace.
          toList.drop(2).map(_.getClassName).filterNot {
            c => blacklist.exists(c.startsWith)
          }
        val logName = breadcrumbs.headOption.getOrElse("UNKNOWN SOURCE")
        val log = LoggerFactory.getLogger(logName)

        doLog(log, message)
      } else buffer.append(c)
    } catch {
      case t: Throwable => // bad logging shouldn't kill the app
    }

    protected def doLog(log: Logger, message: String): Unit
  }

  private object ErrLog extends StreamToLog {
    def doLog(log: Logger, m: String): Unit = log.warn(m)
  }

  private object OutLog extends StreamToLog {
    def doLog(log: Logger, m: String): Unit = log.info(m)
  }
}

object Server {
  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()
  ConsoleOutputWorkaround.redirectScalaConsole()

  val log = LoggerFactory.getLogger(classOf[Server])

  def main(args: Array[String]): Unit = {
    val ensimeFileStr = propOrNone("ensime.config").getOrElse(
      throw new RuntimeException("ensime.config (the location of the .ensime file) must be set"))

    val ensimeFile = new File(ensimeFileStr)
    if (!ensimeFile.exists() || !ensimeFile.isFile)
      throw new RuntimeException(s".ensime file ($ensimeFile) not found")

    val config = readEnsimeConfig(ensimeFile)

    val cacheDirStr = propOrNone("ensime.cachedir").getOrElse(throw new RuntimeException("ensime.cachedir must be set"))
    val cacheDir = new File(cacheDirStr)

    val server = new Server(cacheDir, "127.0.0.1", 0)
    server.startSocketListener()
  }

  def readEnsimeConfig(ensimeFile: File): ProjectConfig = {

    val configSrc = Source.fromFile(ensimeFile)
    try {
      val content = configSrc.getLines().filterNot(_.startsWith(";;")).mkString("\n")
      ProjectConfig.fromSExp(SExpParser.read(content)) match {
        case Right(config) =>
          config
        case Left(ex) =>
          throw ex
      }
    } finally {
      configSrc.close()
    }
  }
}

class Server(cacheDir: File, host: String, requestedPort: Int) {

  import Server.log

  require(!cacheDir.exists || cacheDir.isDirectory, cacheDir + " is not a valid cache directory")
  cacheDir.mkdirs()

  val actorSystem = ActorSystem.create()
  // TODO move this to only be started when we want to receive
  val listener = new ServerSocket(requestedPort, 0, InetAddress.getByName(host))
  val actualPort = listener.getLocalPort

  log.info("ENSIME Server on " + host + ":" + actualPort)
  log.info("cacheDir=" + cacheDir)
  log.info(Environment.info)

  writePort(cacheDir, actualPort)

  val protocol = new SwankProtocol
  val project = new Project(cacheDir, protocol, actorSystem)

  def startSocketListener(): Unit = {
    try {
      while (true) {
        try {
          val socket = listener.accept()
          log.info("Got connection, creating handler...")
          actorSystem.actorOf(Props(classOf[SocketHandler], socket, protocol, project))
        } catch {
          case e: IOException =>
            log.error("ENSIME Server: ", e)
        }
      }
    } finally {
      listener.close()
    }
  }

  def shutdown() {
    log.info("Shutting down server")
    listener.close()
    actorSystem.shutdown()
  }
  private def writePort(cacheDir: File, port: Int): Unit = {
    val portfile = new File(cacheDir, "port")
    println("")
    if (!portfile.exists()) {
      log.info("CREATING " + portfile)
      portfile.createNewFile()
    } else if (portfile.length > 0)
      // LEGACY: older clients create an empty file
      throw new IOException(
        "An ENSIME server might be open already for this project. " +
          "If you are sure this is not the case, please delete " +
          portfile.getAbsolutePath + " and try again"
      )

    portfile.deleteOnExit()
    val out = new PrintWriter(portfile)
    try out.println(port)
    finally out.close()
  }
}

case object SocketClosed

class SocketReader(socket: Socket, protocol: Protocol, handler: ActorRef) extends Thread {
  val in = new BufferedInputStream(socket.getInputStream)

  override def run() {
    try {
      while (true) {
        val msg: WireFormat = protocol.readMessage(in)
        handler ! IncomingMessageEvent(msg)
      }
    } catch {
      case e: IOException =>
        System.err.println("Error in socket reader: " + e)
        Properties.envOrNone("ensime.explode.on.disconnect") match {
          case Some(_) =>
            println("Tick-tock, tick-tock, tick-tock... boom!")
            System.out.flush()
            System.exit(-1)
          case None =>
            handler ! SocketClosed
        }
    }
  }
}

class SocketHandler(socket: Socket, protocol: Protocol, project: Project) extends Actor {
  protocol.setOutputActor(self)

  val reader = new SocketReader(socket, protocol, self)
  val out = new BufferedOutputStream(socket.getOutputStream)

  def write(value: WireFormat) {
    try {
      protocol.writeMessage(value, out)
    } catch {
      case e: IOException =>
        System.err.println("Write to client failed: " + e)
        context.stop(self)
    }
  }

  override def preStart() {
    reader.start()
  }

  override def receive = {
    case IncomingMessageEvent(value: WireFormat) =>
      project ! IncomingMessageEvent(value)
    case OutgoingMessageEvent(value: WireFormat) =>
      write(value)
    case SocketClosed =>
      System.err.println("Socket closed, stopping self")
      context.stop(self)
  }
}

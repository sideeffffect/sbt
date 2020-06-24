/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package internal
package server

import java.io.{ IOException, InputStream, OutputStream }
import java.net.{ Socket, SocketTimeoutException }
import java.nio.channels.ClosedChannelException
import java.util.concurrent.{ ConcurrentHashMap, LinkedBlockingQueue }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import sbt.internal.langserver.{ CancelRequestParams, ErrorCodes, LogMessageParams, MessageType }
import sbt.internal.protocol.{
  JsonRpcNotificationMessage,
  JsonRpcRequestMessage,
  JsonRpcResponseError,
  JsonRpcResponseMessage
}
import sbt.internal.util.{ ReadJsonFromInputStream, Prompt, Terminal, Util }
import sbt.internal.util.Terminal.TerminalImpl
import sbt.internal.util.complete.Parser
import sbt.protocol._
import sbt.util.Logger
import sjsonnew._
import sjsonnew.support.scalajson.unsafe.{ CompactPrinter, Converter }

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal
import Serialization.attach

final class NetworkChannel(
    val name: String,
    connection: Socket,
    protected val structure: BuildStructure,
    auth: Set[ServerAuthentication],
    instance: ServerInstance,
    handlers: Seq[ServerHandler],
    val log: Logger
) extends CommandChannel { self =>

  private val running = new AtomicBoolean(true)
  private val delimiter: Byte = '\n'.toByte
  private val out = connection.getOutputStream
  private var initialized = false
  private val pendingRequests: mutable.Map[String, JsonRpcRequestMessage] = mutable.Map()

  private[this] val inputBuffer = new LinkedBlockingQueue[Byte]()
  private[this] val pendingWrites = new LinkedBlockingQueue[(Array[Byte], Boolean)]()
  private[this] val attached = new AtomicBoolean(false)
  private[this] val alive = new AtomicBoolean(true)
  private[sbt] def isInteractive = interactive.get
  private[this] val interactive = new AtomicBoolean(false)
  private[sbt] def setInteractive(id: String, value: Boolean) = {
    terminalHolder.getAndSet(new NetworkTerminal) match {
      case null =>
      case t    => t.close()
    }
    interactive.set(value)
    if (!isInteractive) terminal.setPrompt(Prompt.Batch)
    attached.set(true)
    pendingRequests.remove(id)
    import sjsonnew.BasicJsonProtocol._
    jsonRpcRespond("", id)
    initiateMaintenance(attach)
  }
  private[sbt] def write(byte: Byte) = inputBuffer.add(byte)

  private[this] val terminalHolder = new AtomicReference(Terminal.NullTerminal)
  override private[sbt] def terminal: Terminal = terminalHolder.get

  private lazy val callback: ServerCallback = new ServerCallback {
    def jsonRpcRespond[A: JsonFormat](event: A, execId: Option[String]): Unit =
      self.respondResult(event, execId)

    def jsonRpcRespondError(execId: Option[String], code: Long, message: String): Unit =
      self.respondError(code, message, execId)

    def jsonRpcNotify[A: JsonFormat](method: String, params: A): Unit =
      self.jsonRpcNotify(method, params)

    def appendExec(commandLine: String, execId: Option[String]): Boolean =
      self.append(Exec(commandLine, execId, Some(CommandSource(name))))

    def appendExec(exec: Exec): Boolean = self.append(exec)

    def log: Logger = self.log
    def name: String = self.name
    private[sbt] def authOptions: Set[ServerAuthentication] = self.authOptions
    private[sbt] def authenticate(token: String): Boolean = self.authenticate(token)
    private[sbt] def setInitialized(value: Boolean): Unit = self.setInitialized(value)
    private[sbt] def onSettingQuery(execId: Option[String], req: SettingQuery): Unit =
      self.onSettingQuery(execId, req)
    private[sbt] def onCompletionRequest(execId: Option[String], cp: CompletionParams): Unit =
      self.onCompletionRequest(execId, cp)
    private[sbt] def onCancellationRequest(execId: Option[String], crp: CancelRequestParams): Unit =
      self.onCancellationRequest(execId, crp)
  }

  protected def authenticate(token: String): Boolean = instance.authenticate(token)

  protected def setInitialized(value: Boolean): Unit = initialized = value

  protected def authOptions: Set[ServerAuthentication] = auth

  val thread = new Thread(s"sbt-networkchannel-${connection.getPort}") {
    private val ct = "Content-Type: "
    private val x1 = "application/sbt-x1"
    override def run(): Unit = {
      try {
        connection.setSoTimeout(5000)

        val in = connection.getInputStream
        // keep going unless the socket has closed
        while (running.get) {
          try {
            val onHeader: String => Unit = line => {
              if (line.startsWith(ct) && line.contains(x1)) {
                logMessage("error", s"server protocol $x1 is no longer supported")
              }
            }
            val content = ReadJsonFromInputStream(in, running, Some(onHeader))
            if (content.nonEmpty) handleBody(content)
          } catch {
            case _: SocketTimeoutException                => // its ok
            case _: IOException | _: InterruptedException => running.set(false)
          }
        } // while
      } finally {
        shutdown()
      }
    }

    private lazy val intents = {
      handlers.toVector map { h =>
        h.handler(callback)
      }
    }

    lazy val onRequestMessage: PartialFunction[JsonRpcRequestMessage, Unit] =
      intents.foldLeft(PartialFunction.empty[JsonRpcRequestMessage, Unit]) {
        case (f, i) => f orElse i.onRequest
      }
    lazy val onResponseMessage: PartialFunction[JsonRpcResponseMessage, Unit] =
      intents.foldLeft(PartialFunction.empty[JsonRpcResponseMessage, Unit]) {
        case (f, i) => f orElse i.onResponse
      }

    lazy val onNotification: PartialFunction[JsonRpcNotificationMessage, Unit] =
      intents.foldLeft(PartialFunction.empty[JsonRpcNotificationMessage, Unit]) {
        case (f, i) => f orElse i.onNotification
      }

    def handleBody(chunk: Seq[Byte]): Unit = {
      Serialization.deserializeJsonMessage(chunk) match {
        case Right(req: JsonRpcRequestMessage) =>
          try {
            registerRequest(req)
            onRequestMessage(req)
          } catch {
            case LangServerError(code, message) =>
              log.debug(s"sending error: $code: $message")
              respondError(code, message, Some(req.id))
          }
        case Right(res: JsonRpcResponseMessage) =>
          onResponseMessage(res)
        case Right(ntf: JsonRpcNotificationMessage) =>
          try {
            onNotification(ntf)
          } catch {
            case LangServerError(code, message) =>
              logMessage("error", s"error $code while handling notification: $message")
          }
        case Right(msg) =>
          log.debug(s"unhandled message: $msg")
        case Left(errorDesc) =>
          val msg =
            s"got invalid chunk from client (${new String(chunk.toArray, "UTF-8")}): $errorDesc"
          log.error(msg)
          logMessage("error", msg)
      }
    }
  }
  thread.start()

  private[sbt] def isLanguageServerProtocol: Boolean = true

  private def registerRequest(request: JsonRpcRequestMessage): Unit = {
    this.synchronized {
      pendingRequests += (request.id -> request)
      ()
    }
  }

  private[sbt] def respondError(
      err: JsonRpcResponseError,
      execId: Option[String]
  ): Unit = this.synchronized {
    execId match {
      case Some(id) if pendingRequests.contains(id) =>
        pendingRequests -= id
        jsonRpcRespondError(id, err)
      case _ =>
        logMessage("error", s"Error ${err.code}: ${err.message}")
    }
  }

  private[sbt] def respondError(
      code: Long,
      message: String,
      execId: Option[String]
  ): Unit = {
    respondError(JsonRpcResponseError(code, message), execId)
  }

  private[sbt] def respondResult[A: JsonFormat](
      event: A,
      execId: Option[String]
  ): Unit = this.synchronized {
    execId match {
      case Some(id) if pendingRequests.contains(id) =>
        pendingRequests -= id
        jsonRpcRespond(event, id)
      case _ =>
        log.debug(
          s"unmatched json response for requestId $execId: ${CompactPrinter(Converter.toJsonUnsafe(event))}"
        )
    }
  }

  private[sbt] def notifyEvent[A: JsonFormat](method: String, params: A): Unit = {
    jsonRpcNotify(method, params)
  }

  def respond[A: JsonFormat](event: A): Unit = respond(event, None)

  def respond[A: JsonFormat](event: A, execId: Option[String]): Unit = {
    respondResult(event, execId)
  }

  def notifyEvent(event: EventMessage): Unit = {
    event match {
      case entry: LogEvent        => logMessage(entry.level, entry.message)
      case entry: ExecStatusEvent => logMessage("debug", entry.status)
      case _                      => ()
    }
  }

  def publishBytes(event: Array[Byte]): Unit = publishBytes(event, false)

  /*
   * Do writes on a background thread because otherwise the client socket can get blocked.
   */
  private[this] val writeThread = new Thread(() => {
    @tailrec def impl(): Unit = {
      val (event, delimit) =
        try pendingWrites.take
        catch {
          case _: InterruptedException =>
            alive.set(false)
            (Array.empty[Byte], false)
        }
      if (alive.get) {
        try {
          out.write(event)
          if (delimit) {
            out.write(delimiter.toInt)
          }
          out.flush()
        } catch {
          case _: IOException =>
            alive.set(false)
            shutdown()
          case _: InterruptedException =>
            alive.set(false)
        }
        impl()
      }
    }
    impl()
  }, s"sbt-$name-write-thread")
  writeThread.setDaemon(true)
  writeThread.start()

  def publishBytes(event: Array[Byte], delimit: Boolean): Unit =
    try pendingWrites.put(event -> delimit)
    catch { case _: InterruptedException => }

  def onCommand(command: CommandMessage): Unit = command match {
    case x: InitCommand  => onInitCommand(x)
    case x: ExecCommand  => onExecCommand(x)
    case x: SettingQuery => onSettingQuery(None, x)
  }

  private def onInitCommand(cmd: InitCommand): Unit = {
    if (auth(ServerAuthentication.Token)) {
      cmd.token match {
        case Some(x) =>
          authenticate(x) match {
            case true =>
              initialized = true
              notifyEvent(ChannelAcceptedEvent(name))
            case _ => sys.error("invalid token")
          }
        case None => sys.error("init command but without token.")
      }
    } else {
      initialized = true
    }
  }

  private def onExecCommand(cmd: ExecCommand) = {
    if (initialized) {
      append(
        Exec(cmd.commandLine, cmd.execId orElse Some(Exec.newExecId), Some(CommandSource(name)))
      )
      ()
    } else {
      log.warn(s"ignoring command $cmd before initialization")
    }
  }

  protected def onSettingQuery(execId: Option[String], req: SettingQuery) = {
    if (initialized) {
      import sbt.protocol.codec.JsonProtocol._
      SettingQuery.handleSettingQueryEither(req, structure) match {
        case Right(x) => respondResult(x, execId)
        case Left(s)  => respondError(ErrorCodes.InvalidParams, s, execId)
      }
    } else {
      log.warn(s"ignoring query $req before initialization")
    }
  }

  protected def onCompletionRequest(execId: Option[String], cp: CompletionParams) = {
    if (initialized) {
      try {
        Option(EvaluateTask.lastEvaluatedState.get) match {
          case Some(sstate) =>
            val completionItems =
              Parser
                .completions(sstate.combinedParser, cp.query, 9)
                .get
                .flatMap { c =>
                  if (!c.isEmpty) Some(c.append.replaceAll("\n", " "))
                  else None
                }
                .map(c => cp.query + c)
            import sbt.protocol.codec.JsonProtocol._
            respondResult(
              CompletionResponse(
                items = completionItems.toVector
              ),
              execId
            )
          case _ =>
            respondError(
              ErrorCodes.UnknownError,
              "No available sbt state",
              execId
            )
        }
      } catch {
        case NonFatal(_) =>
          respondError(
            ErrorCodes.UnknownError,
            "Completions request failed",
            execId
          )
      }
    } else {
      log.warn(s"ignoring completion request $cp before initialization")
    }
  }

  protected def onCancellationRequest(execId: Option[String], crp: CancelRequestParams) = {
    if (initialized) {

      def errorRespond(msg: String) = respondError(
        ErrorCodes.RequestCancelled,
        msg,
        execId
      )

      try {
        Option(EvaluateTask.currentlyRunningEngine.get) match {
          case Some((state, runningEngine)) =>
            val runningExecId = state.currentExecId.getOrElse("")

            def checkId(): Boolean = {
              if (runningExecId.startsWith("\u2668")) {
                (
                  Try { crp.id.toLong }.toOption,
                  Try { runningExecId.substring(1).toLong }.toOption
                ) match {
                  case (Some(id), Some(eid)) => id == eid
                  case _                     => false
                }
              } else runningExecId == crp.id
            }

            // direct comparison on strings and
            // remove hotspring unicode added character for numbers
            if (checkId) {
              runningEngine.cancelAndShutdown()

              import sbt.protocol.codec.JsonProtocol._
              respondResult(
                ExecStatusEvent(
                  "Task cancelled",
                  Some(name),
                  Some(runningExecId),
                  Vector(),
                  None,
                ),
                execId
              )
            } else {
              errorRespond("Task ID not matched")
            }

          case None =>
            errorRespond("No tasks under execution")
        }
      } catch {
        case NonFatal(e) =>
          errorRespond("Cancel request failed")
      }
    } else {
      log.warn(s"ignoring cancellation request $crp before initialization")
    }
  }

  def shutdown(): Unit = {
    log.info("Shutting down client connection")
    running.set(false)
    out.close()
  }

  /** Respond back to Language Server's client. */
  private[sbt] def jsonRpcRespond[A: JsonFormat](event: A, execId: String): Unit = {
    val m =
      JsonRpcResponseMessage("2.0", execId, Option(Converter.toJson[A](event).get), None)
    val bytes = Serialization.serializeResponseMessage(m)
    publishBytes(bytes)
  }

  /** Respond back to Language Server's client. */
  private[sbt] def jsonRpcRespondError[A: JsonFormat](
      execId: String,
      code: Long,
      message: String,
      data: A,
  ): Unit = {
    val err = JsonRpcResponseError(code, message, Converter.toJson[A](data).get)
    jsonRpcRespondError(execId, err)
  }

  private[sbt] def jsonRpcRespondError(
      execId: String,
      err: JsonRpcResponseError
  ): Unit = {
    val m = JsonRpcResponseMessage("2.0", execId, None, Option(err))
    val bytes = Serialization.serializeResponseMessage(m)
    publishBytes(bytes)
  }

  /** Notify to Language Server's client. */
  private[sbt] def jsonRpcNotify[A: JsonFormat](method: String, params: A): Unit = {
    val m =
      JsonRpcNotificationMessage("2.0", method, Option(Converter.toJson[A](params).get))
    log.debug(s"jsonRpcNotify: $m")
    val bytes = Serialization.serializeNotificationMessage(m)
    publishBytes(bytes)
  }

  /** Notify to Language Server's client. */
  private[sbt] def jsonRpcRequest[A: JsonFormat](id: String, method: String, params: A): Unit = {
    val m =
      JsonRpcRequestMessage("2.0", id, method, Option(Converter.toJson[A](params).get))
    log.debug(s"jsonRpcRequest: $m")
    val bytes = Serialization.serializeRequestMessage(m)
    publishBytes(bytes)
  }

  def logMessage(level: String, message: String): Unit = {
    import sbt.internal.langserver.codec.JsonProtocol._
    jsonRpcNotify(
      "build/logMessage",
      LogMessageParams(MessageType.fromLevelString(level), message)
    )
  }
  private[this] lazy val inputStream: InputStream = new InputStream {
    override def read(): Int = {
      try {
        inputBuffer.take & 0xFF match {
          case -1 => throw new ClosedChannelException()
          case b  => b
        }
      } catch { case _: IOException => -1 }
    }
    override def available(): Int = inputBuffer.size
  }
  import sjsonnew.BasicJsonProtocol._

  import scala.collection.JavaConverters._
  private[this] lazy val outputStream: OutputStream = new OutputStream {
    private[this] val buffer = new LinkedBlockingQueue[Byte]()
    override def write(b: Int): Unit = buffer.put(b.toByte)
    override def flush(): Unit = {
      jsonRpcNotify(Serialization.systemOut, buffer.asScala)
      buffer.clear()
    }
    override def write(b: Array[Byte]): Unit = write(b, 0, b.length)
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      var i = off
      while (i < len) {
        buffer.put(b(i))
        i += 1
      }
    }
  }
  private class NetworkTerminal extends TerminalImpl(inputStream, outputStream, name) {
    private[this] val pending = new AtomicBoolean(false)
    private[this] val closed = new AtomicBoolean(false)
    private[this] val properties = new AtomicReference[TerminalPropertiesResponse]
    private[this] val lastUpdate = new AtomicReference[Deadline]
    private def empty = TerminalPropertiesResponse(0, 0, false, false, false, false)
    def getProperties(block: Boolean): Unit = {
      if (alive.get) {
        if (!pending.get && Option(lastUpdate.get).fold(true)(d => (d + 1.second).isOverdue)) {
          pending.set(true)
          val queue = VirtualTerminal.sendTerminalPropertiesQuery(name, jsonRpcRequest)
          val update: Runnable = () => {
            queue.poll(5, java.util.concurrent.TimeUnit.SECONDS) match {
              case null =>
              case t    => properties.set(t)
            }
            pending.synchronized {
              lastUpdate.set(Deadline.now)
              pending.set(false)
              pending.notifyAll()
            }
          }
          new Thread(update, s"network-terminal-$name-update") {
            setDaemon(true)
          }.start()
        }
        while (block && properties.get == null) pending.synchronized(pending.wait())
        ()
      } else throw new InterruptedException
    }
    private def withThread[R](f: => R, default: R): R = {
      val t = Thread.currentThread
      try {
        blockedThreads.synchronized(blockedThreads.add(t))
        f
      } catch { case _: InterruptedException => default } finally {
        Util.ignoreResult(blockedThreads.synchronized(blockedThreads.remove(t)))
      }
    }
    def getProperty[T](f: TerminalPropertiesResponse => T, default: T): Option[T] = {
      if (closed.get || !isAttached) None
      else
        withThread({
          getProperties(true);
          Some(f(Option(properties.get).getOrElse(empty)))
        }, None)
    }
    private[this] def waitForPending(f: TerminalPropertiesResponse => Boolean): Boolean = {
      if (closed.get || !isAttached) false
      withThread(
        {
          if (pending.get) pending.synchronized(pending.wait())
          Option(properties.get).map(f).getOrElse(false)
        },
        false
      )
    }
    private[this] val blockedThreads = ConcurrentHashMap.newKeySet[Thread]
    override def getWidth: Int = getProperty(_.width, 0).getOrElse(0)
    override def getHeight: Int = getProperty(_.height, 0).getOrElse(0)
    override def isAnsiSupported: Boolean = getProperty(_.isAnsiSupported, false).getOrElse(false)
    override def isEchoEnabled: Boolean = waitForPending(_.isEchoEnabled)
    override def isSuccessEnabled: Boolean = prompt != Prompt.Batch
    override lazy val isColorEnabled: Boolean = waitForPending(_.isColorEnabled)
    override lazy val isSupershellEnabled: Boolean = waitForPending(_.isSupershellEnabled)
    getProperties(false)
    private def getCapability[T](
        query: TerminalCapabilitiesQuery,
        result: TerminalCapabilitiesResponse => T
    ): Option[T] = {
      if (closed.get) None
      else {
        import sbt.protocol.codec.JsonProtocol._
        val queue = VirtualTerminal.sendTerminalCapabilitiesQuery(name, jsonRpcRequest, query)
        Some(result(queue.take))
      }
    }
    override def getBooleanCapability(capability: String): Boolean =
      getCapability(
        TerminalCapabilitiesQuery(boolean = Some(capability), numeric = None, string = None),
        _.boolean.getOrElse(false)
      ).getOrElse(false)
    override def getNumericCapability(capability: String): Int =
      getCapability(
        TerminalCapabilitiesQuery(boolean = None, numeric = Some(capability), string = None),
        _.numeric.getOrElse(-1)
      ).getOrElse(-1)
    override def getStringCapability(capability: String): String =
      getCapability(
        TerminalCapabilitiesQuery(boolean = None, numeric = None, string = Some(capability)),
        _.string.flatMap {
          case "null" => None
          case s      => Some(s)
        }.orNull
      ).getOrElse("")

    override def toString: String = s"NetworkTerminal($name)"
    override def close(): Unit = if (closed.compareAndSet(false, true)) {
      val threads = blockedThreads.synchronized {
        val t = blockedThreads.asScala.toVector
        blockedThreads.clear()
        t
      }
      threads.foreach(_.interrupt())
      super.close()
    }
  }
  private[sbt] def isAttached: Boolean = attached.get
}

object NetworkChannel {
  sealed trait ChannelState
  case object SingleLine extends ChannelState
  case object InHeader extends ChannelState
  case object InBody extends ChannelState
}

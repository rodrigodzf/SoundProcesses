/*
 *  AuralSystemImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2014 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc
package impl

import de.sciss.osc.Dump
import scala.concurrent.stm.{TxnExecutor, Ref, Txn => ScalaTxn}
import collection.immutable.{IndexedSeq => Vec}
import de.sciss.synth.{Server => SServer, ServerLike => SServerLike, ServerConnection}
import TxnExecutor.{defaultAtomic => atomic}
import de.sciss.lucre.synth.{NodeGraph, Server, Txn}
import de.sciss.lucre.stm.Disposable
import de.sciss.synth.proc.{logAural => logA}

object AuralSystemImpl {
  import AuralSystem.Client

  var dumpOSC = false

  def apply(): AuralSystem = new Impl

  /* There is a bug in Scala-STM which means
   * that calling atomic from within Txn.afterCommit
   * causes an exception. It seems this has to
   * do with the external decider being set?
   */
  private def afterCommit(code: => Unit)(implicit tx: Txn): Unit = tx.afterCommit {
    SoundProcesses.pool.submit(new Runnable() {
      def run(): Unit = code
    })
  }

  private final class Impl extends AuralSystem {
    impl =>

    private sealed trait State extends Disposable[Txn] {
      def serverOption: Option[Server]
      def shutdown(): Unit
    }

    private case object StateStopped extends State {
      def dispose()(implicit tx: Txn): Unit = ()
      def serverOption: Option[Server] = None
      def shutdown(): Unit = ()
    }

    private case class StateBooting(config: Server.Config, connect: Boolean) extends State {
      private lazy val con: ServerConnection = {
        val launch: ServerConnection.Listener => ServerConnection = if (connect) {
          SServer.connect("SoundProcesses", config)
        } else {
          SServer.boot("SoundProcesses", config)
        }

        logA(s"Booting (connect = $connect)")
        launch {
          case ServerConnection.Aborted =>
            state.single.swap(StateStopped)
            //            atomic { implicit itx =>
            //              implicit val tx = Txn.wrap(itx)
            //              state.swap(StateStopped).dispose()
            //            }

          case ServerConnection.Running(s) =>
            if (dumpOSC) s.dumpOSC(Dump.Text)
            SoundProcesses.pool.submit(new Runnable() {
              def run(): Unit = serverStarted(Server(s))
            })
        }
      }

      def init()(implicit tx: Txn): Unit = afterCommit { con }

      def dispose()(implicit tx: Txn): Unit = afterCommit {
        logA("Aborting boot")
        con.abort()
      }

      def serverOption: Option[Server] = None
      def shutdown(): Unit = con.abort()
    }

    private case class StateRunning(server: Server) extends State {
      def dispose()(implicit tx: Txn): Unit = {
        logA("Stopped server")
        NodeGraph.removeServer(server)
        clients.get(tx.peer).foreach(_.stopped())

        afterCommit {
          val obs = listener.single.swap(None)
          assert(obs.isDefined)
          server.peer.removeListener(obs.get)
          if (server.peer.isRunning) server.peer.quit()
        }
      }

      def shutdown(): Unit = server.peer.quit()

      def serverOption: Option[Server] = Some(server)

      private val listener = Ref(Option.empty[SServer.Listener])

      def init()(implicit tx: Txn): Unit = {
        logA("Started server")
        NodeGraph.addServer(server)
        clients.get(tx.peer).foreach(_.started(server))

        afterCommit {
          val list = server.peer.addListener {
            case SServer.Offline =>
              atomic { implicit itx =>
                implicit val tx = Txn.wrap(itx)
                state.swap(StateStopped).dispose()
              }
          }
          val old = listener.single.swap(Some(list))
          assert(old.isEmpty)
        }
      }
    }

    override def toString = s"AuralSystem@${hashCode.toHexString}"

    private val clients = Ref(Vec   .empty[Client])
    private val state   = Ref(StateStopped: State)

    def offline(server: Server.Offline)(implicit tx: Txn): Unit = serverStartedTx(server)

    private def serverStarted(rich: Server): Unit =
      atomic { implicit itx =>
        implicit val tx = Txn.wrap(itx)
        serverStartedTx(rich)
      }

    private def serverStartedTx(rich: Server)(implicit tx: Txn): Unit = {
      val running = StateRunning(rich)
      state.swap(running)(tx.peer) // .dispose()
      running.init()
    }

    def start(config: Server.Config, connect: Boolean)(implicit tx: Txn): Unit = state.get(tx.peer) match {
      case StateStopped =>
        installShutdown
        val booting = StateBooting(config, connect = connect)
        state.swap(booting)(tx.peer) // .dispose()
        booting.init()

      case _ =>
    }

    private lazy val installShutdown: Unit = Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run(): Unit = impl.shutdown()
    }))

    private def shutdown(): Unit = state.single().shutdown()

    def stop()(implicit tx: Txn): Unit =
      state.swap(StateStopped)(tx.peer).dispose()

    def addClient(c: Client)(implicit tx: Txn): Unit =
      clients.transform(_ :+ c)(tx.peer)

    def serverOption(implicit tx: Txn): Option[Server] = state.get(tx.peer).serverOption

    def removeClient(c: Client)(implicit tx: Txn): Unit =
      clients.transform { _.filterNot(_ == c) } (tx.peer)

    def whenStarted(fun: Server => Unit)(implicit tx: Txn): Unit = {
      state.get(tx.peer) match {
        case StateRunning(server) => fun(server)
        case _ =>
          val c: Client = new Client {
            def started(s: Server)(implicit tx: Txn): Unit = {
              removeClient(this)
              fun(s)
            }

            def stopped()(implicit tx: Txn) = ()
          }
          addClient(c)
      }
    }
  }
}
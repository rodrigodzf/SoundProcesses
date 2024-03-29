/*
 *  BusManagement.scala
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

package de.sciss.lucre.synth

import collection.immutable.{SortedMap => ISortedMap}
import de.sciss.synth.{AudioBus => SAudioBus, AudioRated, Bus => SBus, ControlBus => SControlBus, ControlRated, Rate}
import concurrent.stm.{Ref => ScalaRef}

sealed trait Bus {
  def server: Server
  def numChannels: Int
  def rate: Rate
}

object AudioBus {
  /**
   *    A consumer reading or writing from an audio bus.
   *    Since a AudioBus is a meta structure, the
   *    underlying audio bus may change due to optimization.
   *    In this case the consumer is asked to update its
   *    data. Also initial bus allocation is lazy, therefore
   *    when adding the user as reader or writer, the
   *    bus implementation will push its initial allocation
   *    information to the user.
   */
  trait User /* extends Bus.User */ {
    def busChanged(peer: SAudioBus)(implicit tx: Txn): Unit
  }
}

trait AudioBus extends Bus with AudioRated {
  import AudioBus._

  def busOption(implicit tx: Txn): Option[SAudioBus]

  /**
   *    Adds a reading consumer to the bus. Note that
   *    the readers are kept in a Set and this method doesn't
   *    currently check whether the set already contains
   *    the reader. Adding the same reader more than once
   *    will cause malbehaviour.
   *
   *    As a consequence, the user's busChanged method is
   *    invoked with the current bus. The current bus may
   *    change due to the addition. In this case, busChanged
   *    is called on all other currently registered users.
   */
  def addReader(u: User)(implicit tx: Txn): Unit

  /**
   *    Adds a writing consumer to the bus. Note that
   *    the writers are kept in a Set and this method doesn't
   *    currently check whether the set already contains
   *    the writer. Adding the same writer more than once
   *    will cause malbehaviour.
   *
   *    As a consequence, the user's busChanged method is
   *    invoked with the current bus. The current bus may
   *    change due to the addition. In this case, busChanged
   *    is called on all other currently registered users.
   */
  def addWriter(u: User)(implicit tx: Txn): Unit

  /**
   *    Removes a reading consumer from the bus. It is
   *    safe to call this method, passing in a user which
   *    has already been previously removed.
   *
   *    The current bus may change due to the removal.
   *    In this case, busChanged is called on all
   *    remaining registered users.
   */
  def removeReader(u: User)(implicit tx: Txn): Unit

  /**
   *    Removes a writing consumer from the bus. It is
   *    safe to call this method, passing in a user which
   *    has already been previously removed.
   *
   *    The current bus may change due to the removal.
   *    In this case, busChanged is called on all
   *    remaining registered users.
   */
  def removeWriter(u: User)(implicit tx: Txn): Unit
}

object ControlBus {
  trait User /* extends Bus.User */ {
    def busChanged(bus: SControlBus)(implicit tx: Txn): Unit
  }
}

trait ControlBus extends Bus with ControlRated {
  import ControlBus._

  def busOption(implicit tx: Txn): Option[SControlBus]

  /**
   *    Adds a reading consumer to the bus. Note that
   *    the readers are kept in a Set and this method doesn't
   *    currently check whether the set already contains
   *    the reader. Adding the same reader more than once
   *    will cause malbehaviour.
   *
   *    As a consequence, the user's busChanged method is
   *    invoked with the current bus.
   */
  def addReader(u: User)(implicit tx: Txn): Unit

  /**
   *    Adds a writing consumer to the bus. Note that
   *    the writers are kept in a Set and this method doesn't
   *    currently check whether the set already contains
   *    the writer. Adding the same writer more than once
   *    will cause malbehaviour.
   *
   *    As a consequence, the user's busChanged method is
   *    invoked with the current bus.
   */
  def addWriter(u: User)(implicit tx: Txn): Unit

  /**
   *    Removes a reading consumer from the bus. It is
   *    safe to call this method, passing in a user which
   *    has already been previously removed.
   */
  def removeReader(u: User)(implicit tx: Txn): Unit

  /**
   *    Removes a writing consumer from the bus. It is
   *    safe to call this method, passing in a user which
   *    has already been previously removed.
   */
  def removeWriter(u: User)(implicit tx: Txn): Unit
}

object Bus {
  //   trait User {
  //      def busChanged( bus: Bus )( implicit tx: Txn ) : Unit
  //   }

  /**
   *    Constructs a new audio bus proxy for use in a shared environment, where
   *    there can be situations of semi-orphaned buses (only one reader or
   *    only one writer left).
   */
  def audio  (server: Server, numChannels: Int): AudioBus   = new AudioImpl  (server, numChannels)
  def control(server: Server, numChannels: Int): ControlBus = new ControlImpl(server, numChannels)

  /**
   *    Constructs a new audio bus proxy for use in a short-term temporary fashion.
   *    The implementation does not maintain dummy and empty buses for the case that
   *    there is only one reader or only one writer. As a consequence, it should not
   *    be used in such a scenario, as precious bus indices will be occupied. On the
   *    other hand, this method is useful for internal temporary buses, because when
   *    both a reader and a writer release the resource, there are no spurious
   *    bus re-assignments causing further busChanged notifications (which would go
   *    to concurrently freed nodes).
   */
  def tmpAudio(server: Server, numChannels: Int): AudioBus = new TempAudioImpl(server, numChannels)

  def soundIn(server: Server, numChannels: Int, offset: Int = 0): AudioBus = {
    val o = server.peer.config
    require(offset + numChannels <= o.inputBusChannels, "soundIn - offset is beyond allocated hardware channels")
    FixedImpl(server, SAudioBus(server.peer, index = o.outputBusChannels + offset, numChannels = numChannels))
  }

  def soundOut(server: Server, numChannels: Int, offset: Int = 0): AudioBus = {
    val o = server.peer.config
    require(offset + numChannels <= o.outputBusChannels, "soundOut - offset is beyond allocated hardware channels")
    FixedImpl(server, SAudioBus(server.peer, index = offset, numChannels = numChannels))
  }

  def wrap(server: Server, bus: SAudioBus): AudioBus = {
    require(server.peer == bus.server)
    FixedImpl(server, bus)
  }

  //   trait User {
  //      def busChanged( bus: AudioBus )( implicit tx: Txn ) : Unit
  //   }

  var verbose = false

  private sealed trait BusHolder[T <: SBus] {
    def peer: T

    //      def server: Server

    private val useCount = ScalaRef(0) // Ref.withCheck( 0 ) { case 0 => peer.free() }

    // increments use count
    final def alloc()(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      useCount += 1
      if (verbose) println(peer.toString + ".alloc -> " + useCount.get)
    }

    // decrements use count and calls `remove` if that count reaches zero
    final def free()(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val cnt = useCount.get - 1
      if (verbose) println(peer.toString + ".free -> " + cnt)
      require(cnt >= 0)
      useCount.set(cnt)
      if (cnt == 0) {
        remove()
      }
    }

    final def index: Int = peer.index

    final def numChannels: Int = peer.numChannels

    protected def remove()(implicit tx: Txn): Unit
  }

  private type AudioBusHolder   = BusHolder[SAudioBus  ]
  private type ControlBusHolder = BusHolder[SControlBus]

  private type ABusHolderMap = Map[Server, ISortedMap[Int, AudioBusHolder]]

  private final class PlainAudioBusHolder(server: Server, val peer: SAudioBus)
    extends BusHolder[SAudioBus] {
    protected def remove()(implicit tx: Txn): Unit =
      server.freeAudioBus(peer.index, peer.numChannels)
  }

  private final class PlainControlBusHolder(server: Server, val peer: SControlBus)
    extends BusHolder[SControlBus] {

    protected def remove()(implicit tx: Txn): Unit =
      server.freeControlBus(peer.index, peer.numChannels)
  }

  private final class AudioBusHolderImpl(val server: Server, val peer: SAudioBus, mapScalaRef: ScalaRef[ABusHolderMap])
    extends AudioBusHolder {

    def add()(implicit tx: Txn): Unit =
      mapScalaRef.transform(map => map +
        (server -> (map.getOrElse(server, ISortedMap.empty[Int, AudioBusHolder]) + (numChannels -> this)))
      )(tx.peer)

    protected def remove()(implicit tx: Txn): Unit = {
      server.freeAudioBus(peer.index, peer.numChannels)
      mapScalaRef.transform(map => {
        val newMap = map(server) - numChannels
        if (newMap.isEmpty) {
          map - server
        } else {
          map + (server -> newMap)
        }
      })(tx.peer)
    }
  }

  // XXX TODO
  private val readOnlyBuses  = ScalaRef(Map.empty[Server, ISortedMap[Int, AudioBusHolder]])
  private val writeOnlyBuses = ScalaRef(Map.empty[Server, ISortedMap[Int, AudioBusHolder]])

  private def createReadOnlyBus(server: Server, numChannels: Int)(implicit tx: Txn): AudioBusHolder =
    createAudioBus(server, numChannels, readOnlyBuses)

  private def createWriteOnlyBus(server: Server, numChannels: Int)(implicit tx: Txn): AudioBusHolder =
    createAudioBus(server, numChannels, writeOnlyBuses)

  private def createAudioBus(server: Server, numChannels: Int,
                                 mapScalaRef: ScalaRef[Map[Server, ISortedMap[Int, AudioBusHolder]]])
                                (implicit tx: Txn): AudioBusHolder = {
    val chanMapO = mapScalaRef.get(tx.peer).get(server)
    val bus: AudioBusHolder = chanMapO.flatMap(_.from(numChannels).headOption.map(_._2)).getOrElse {
      val index = server.allocAudioBus(numChannels)
      val peer  = SAudioBus(server.peer, index = index, numChannels = numChannels)
      val res   = new AudioBusHolderImpl(server, peer, mapScalaRef)
      res.add()
      res
    }
    bus
  }

  private def createAudioBus(server: Server, numChannels: Int)(implicit tx: Txn): AudioBusHolder = {
    val index = server.allocAudioBus(numChannels)
    val peer  = SAudioBus(server.peer, index = index, numChannels = numChannels)
    new PlainAudioBusHolder(server, peer)
  }

  private def createControlBus(server: Server, numChannels: Int)(implicit tx: Txn): ControlBusHolder = {
    val index = server.allocControlBus(numChannels)
    val peer  = SControlBus(server.peer, index = index, numChannels = numChannels)
    new PlainControlBusHolder(server, peer)
  }

  private abstract class AbstractAudioImpl extends AudioBus {
    import AudioBus.{User => AU}

    final protected val readers = ScalaRef(Set.empty[AU])
    final protected val writers = ScalaRef(Set.empty[AU])
  }

  private final case class FixedImpl(server: Server, bus: SAudioBus)
    extends AbstractAudioImpl {

    import AudioBus.{User => AU}

    def numChannels = bus.numChannels

    def busOption(implicit tx: Txn): Option[SAudioBus] = Some(bus)

    def addReader(u: AU)(implicit tx: Txn): Unit = add(readers, u)
    def addWriter(u: AU)(implicit tx: Txn): Unit = add(writers, u)

    private def add(users: ScalaRef[Set[AU]], u: AU)(implicit tx: Txn): Unit = {
      users.transform(_ + u)(tx.peer)
      u.busChanged(bus)
    }

    def removeReader(u: AU)(implicit tx: Txn): Unit = remove(readers, u)
    def removeWriter(u: AU)(implicit tx: Txn): Unit = remove(writers, u)

    private def remove(users: ScalaRef[Set[AU]], u: AU)(implicit tx: Txn): Unit =
      users.transform(_ - u)(tx.peer)

    override def toString = "h-abus(" + bus + ")"
  }

  private abstract class BasicAudioImpl extends AbstractAudioImpl {
    final protected val bus = ScalaRef.make[AudioBusHolder]

    final def busOption(implicit tx: Txn): Option[SAudioBus] = {
      val bh = bus.get(tx.peer)
      if (bh != null) Some(bh.peer) else None
    }
  }

  private final class AudioImpl(val server: Server, val numChannels: Int) extends BasicAudioImpl {
    import AudioBus.{User => AU}

    override def toString = "sh-abus(numChannels=" + numChannels + ")@" + hashCode

    def addReader(u: AU)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val rs = readers.get
      require(!rs.contains(u))
      val bh = if (rs.isEmpty) {
        val ws = writers.get
        if (ws.isEmpty) {
          // no bus yet, create an empty shared one
          val res = createReadOnlyBus(server, numChannels)
          bus.set(res)
          //println( "addReader : " + this + " ; allocReadOnlyBus " + res )
          res
        } else {
          // dispose old dummy bus, create new bus
          val res       = createAudioBus(server, numChannels)
          val newBus    = res.peer // AudioBus( server.peer, index = res.index, numChannels = numChannels )
          val oldHolder = bus.swap(res)
          rs.foreach { r =>
            oldHolder.free()
            r.busChanged(newBus)
            res.alloc()
          }
          ws.foreach { w =>
            oldHolder.free()
            w.busChanged(newBus)
            res.alloc()
          }
          //println( "addReader : " + this + " ; allocAudioBus " + res )
          res
        }
      } else {
        // re-use existing bus
        bus.get
        //            val res = new AudioBus( server, bh.index, numChannels )
        //println( "addReader : " + this + " ; re-alloc " + res )
        //            res
      }
      readers.set(rs + u)
      // always perform this on the newly added
      // reader no matter if the bus is new:
      bh.alloc()
      val newBus = bh.peer // AudioBus( server.peer, index = bh.index, numChannels = numChannels )
      u.busChanged(newBus)
    }

    def addWriter(u: AU)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val ws = writers.get
      require(!ws.contains(u))
      val bh = if (ws.isEmpty) {
        val rs = readers.get
        if (rs.isEmpty) {
          // no bus yet, create an empty shared one
          val res = createWriteOnlyBus(server, numChannels)
          bus.set(res)
          res
        } else {
          // dispose old dummy bus, create new bus
          val res       = createAudioBus(server, numChannels)
          val newBus    = res.peer // AudioBus( server.peer, index = res.index, numChannels = numChannels )
          val oldHolder = bus.swap(res)
          rs foreach { r =>
            oldHolder.free()
            r.busChanged(newBus)
            res.alloc()
          }
          ws foreach { w =>
            oldHolder.free()
            w.busChanged(newBus)
            res.alloc()
          }
          res
        }
      } else {
        // re-use existing bus
        bus.get
      }
      writers.set(ws + u)
      // always perform this on the newly added
      // reader no matter if the bus is new:
      bh.alloc()
      val newBus = bh.peer // new AudioBus( server, bh.index, numChannels )
      u.busChanged(newBus)
    }

    def removeReader(u: AU)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val rs0 = readers()
      if (!rs0.contains(u)) return
      val rs = rs0 - u
      readers.set(rs)
      val oldHolder = bus()
      oldHolder.free()
      if (rs.isEmpty) {
        val ws = writers()
        if (ws.nonEmpty) {
          // they can all go to write only
          val bh = createWriteOnlyBus(server, numChannels)
          bus.set(bh)
          val newBus = bh.peer // new AudioBus( server, bh.index, numChannels )
          ws foreach { w =>
            oldHolder.free()
            w.busChanged(newBus)
            bh.alloc()
          }
        }
      }
    }

    def removeWriter(u: AU)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val ws0 = writers.get
      if (!ws0.contains(u)) return
      val ws = ws0 - u
      writers.set(ws)
      val oldHolder = bus.get
      oldHolder.free()
      if (ws.isEmpty) {
        val rs = readers.get
        if (rs.nonEmpty) {
          // they can all go to write only
          val bh = createReadOnlyBus(server, numChannels)
          bus.set(bh)
          val newBus = bh.peer // new AudioBus( server, bh.index, numChannels )
          rs foreach { r =>
            oldHolder.free()
            r.busChanged(newBus)
            bh.alloc()
          }
        }
      }
    }
  }

  private final class TempAudioImpl(val server: Server, val numChannels: Int) extends BasicAudioImpl {
    import AudioBus.{User => AU}

    override def toString = "tmp-abus(numChannels=" + numChannels + ")@" + hashCode

    def addReader(u: AU)(implicit tx: Txn): Unit = add(readers, writers, u)
    def addWriter(u: AU)(implicit tx: Txn): Unit = add(writers, readers, u)

    private def add(users: ScalaRef[Set[AU]], others: ScalaRef[Set[AU]], u: AU)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val us = users.get
      require(!us.contains(u))
      // do _not_ check for null
      // because we might have a disposed
      // bus there, so we must make sure to
      // re-allocate a new bus each time
      // the users count goes to 1!
      val bh = if (us.isEmpty && others.get.isEmpty) {
        val res = createAudioBus(server, numChannels)
        bus.set(res)
        res
      } else {
        // re-use existing bus
        bus.get
      }
      users.set(us + u)
      // always perform this on the newly added
      // reader no matter if the bus is new:
      bh.alloc()
      val newBus = bh.peer // new AudioBus( server, bh.index, numChannels )
      u.busChanged(newBus)
    }

    def removeReader(u: AU)(implicit tx: Txn): Unit = remove(readers, u)
    def removeWriter(u: AU)(implicit tx: Txn): Unit = remove(writers, u)

    private def remove(users: ScalaRef[Set[AU]], u: AU)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val rw = users.get
      if (!rw.contains(u)) return
      users.set(rw - u)
      bus.get.free()
    }
  }

  private final class ControlImpl(val server: Server, val numChannels: Int) extends ControlBus {
    import ControlBus.{User => CU}

    private val bus     = ScalaRef.make[ControlBusHolder]
    private val readers = ScalaRef(Set.empty[CU])
    private val writers = ScalaRef(Set.empty[CU])

    override def toString = "cbus(numChannels=" + numChannels + ")@" + hashCode

    def busOption(implicit tx: Txn) = {
      val bh = bus.get(tx.peer)
      if (bh != null) Some(bh.peer) else None
    }

    def addReader(u: CU)(implicit tx: Txn): Unit = add(readers, writers, u)
    def addWriter(u: CU)(implicit tx: Txn): Unit = add(writers, readers, u)

    private def add(users: ScalaRef[Set[CU]], others: ScalaRef[Set[CU]], u: CU)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val us = users.get
      require(!us.contains(u))
      // do _not_ check for null
      // because we might have a disposed
      // bus there, so we must make sure to
      // re-allocate a new bus each time
      // the users count goes to 1!
      val bh = if (us.isEmpty && others.get.isEmpty) {
        val res = createControlBus(server, numChannels)
        bus.set(res)
        res
      } else {
        // re-use existing bus
        bus.get
      }
      users.set(us + u)
      // always perform this on the newly added
      // reader no matter if the bus is new:
      bh.alloc()
      val newBus = bh.peer // new ControlBus( server, bh.index, numChannels )
      u.busChanged(newBus)
    }

    def removeReader(u: CU)(implicit tx: Txn): Unit = remove(readers, u)
    def removeWriter(u: CU)(implicit tx: Txn): Unit = remove(writers, u)

    private def remove(users: ScalaRef[Set[CU]], u: CU)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val rw = users.get
      if (!rw.contains(u)) return
      users.set(rw - u)
      bus.get.free()
    }
  }
}
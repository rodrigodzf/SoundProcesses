/*
 *  ProcImpl.scala
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

package de.sciss.synth
package proc
package impl

import de.sciss.lucre.{event => evt, data, bitemp}
import evt.{Event, impl => evti}
import data.SkipList
import annotation.switch
import collection.breakOut
import collection.immutable.{IndexedSeq => Vec}
import de.sciss.serial.{DataOutput, ImmutableSerializer, DataInput}
import language.higherKinds
import de.sciss.lucre.synth.{InMemory, Sys}
import de.sciss.lucre.expr.ExprType1
import de.sciss.lucre

object ProcImpl {
  private final val SER_VERSION = 0x5073  // was "Pr"

  implicit val paramType: ExprType1[Param] = lucre.expr.Double

  def apply[S <: Sys[S]](implicit tx: S#Tx): Proc[S] = new New[S]

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Proc[S] =
    serializer[S].read(in, access)

  def serializer[S <: Sys[S]]: evt.NodeSerializer[S, Proc[S]] =
    anySer.asInstanceOf[evt.NodeSerializer[S, Proc[S]]]

  private val anySer = new Serializer[InMemory]

  private class Serializer[S <: Sys[S]] extends evt.NodeSerializer[S, Proc[S]] {
    def read(in: DataInput, access: S#Acc, targets: evt.Targets[S])(implicit tx: S#Tx): Proc[S] =
      new Read(in, access, targets)
  }

  private type ScanEntry     [S <: Sys[S]] = KeyMapImpl.Entry[S, String, Scan     [S], Scan     .Update[S]]
  private type AttrEntry[S <: Sys[S]] = KeyMapImpl.Entry[S, String, Attr[S], Attr.Update[S]]

  private type I = InMemory

  implicit def scanEntryInfo[S <: Sys[S]]: KeyMapImpl.ValueInfo[S, String, Scan[S], Scan.Update[S]] =
    anyScanEntryInfo.asInstanceOf[KeyMapImpl.ValueInfo[S, String, Scan[S], Scan.Update[S]]]

  private val anyScanEntryInfo = new KeyMapImpl.ValueInfo[I, String, Scan[I], Scan.Update[I]] {
    def valueEvent(value: Scan[I]) = value.changed

    val keySerializer   = ImmutableSerializer.String
    val valueSerializer = Scan.serializer[I]
  }

  implicit def attributeEntryInfo[S <: Sys[S]]: KeyMapImpl.ValueInfo[S, String, Attr[S], Attr.Update[S]] =
     anyAttrEntryInfo.asInstanceOf[KeyMapImpl.ValueInfo[S, String, Attr[S], Attr.Update[S]]]

  private val anyAttrEntryInfo = new KeyMapImpl.ValueInfo[I, String, Attr[I], Attr.Update[I]] {
    def valueEvent(value: Attr[I]) = value.changed

    val keySerializer   = ImmutableSerializer.String
    val valueSerializer = Attr.serializer[I]
  }

  private sealed trait Impl[S <: Sys[S]]
    extends Proc[S] {
    proc =>

    import Proc._

    protected def attributeMap: SkipList.Map[S, String, AttrEntry[S]]
    protected def scanMap     : SkipList.Map[S, String, ScanEntry[S]]

    //    final def graph(implicit tx: S#Tx): Expr[S, SynthGraph] = _graph()
    //
    //    final def graph_=(g: Expr[S, SynthGraph])(implicit tx: S#Tx): Unit = {
    //      val old = _graph()
    //      if (old != g) {
    //        _graph() = g
    //        val ch = evt.Change(old.value, g.value)
    //        if (ch.isSignificant) StateEvent(Proc.Update(proc, Vector(GraphChange(ch))))
    //      }
    //    }

    // ---- key maps ----

    sealed trait ProcEvent {
      final protected def reader: evt.Reader[S, Proc[S]] = ProcImpl.serializer
      final def node: Proc[S] with evt.Node[S] = proc
    }

    sealed trait KeyMap[Value, ValueUpd, OuterUpd]
      extends evti.EventImpl [S, OuterUpd, Proc[S]]
      with evt.InvariantEvent[S, OuterUpd, Proc[S]]
      with ProcEvent
      with impl.KeyMapImpl[S, String, Value, ValueUpd] {
      protected def wrapKey(key: String): Proc.AssociativeKey

      // ---- keymapimpl details ----

      final protected def fire(added: Set[String], removed: Set[String])(implicit tx: S#Tx): Unit = {
        val seqAdd: Vec[Proc.StateChange[S]] = added  .map(key => Proc.AssociationAdded  [S](wrapKey(key)))(breakOut)
        val seqRem: Vec[Proc.StateChange[S]] = removed.map(key => Proc.AssociationRemoved[S](wrapKey(key)))(breakOut)
        // convention: first the removals, then the additions. thus, overwriting a key yields
        // successive removal and addition of the same key.
        val seq = if (seqAdd.isEmpty) seqRem else if (seqRem.isEmpty) seqAdd else seqRem ++ seqAdd

        StateEvent(Proc.Update(proc, seq))
      }

      final protected def isConnected(implicit tx: S#Tx): Boolean = proc.targets.nonEmpty
    }

    object attributes extends AttrMap.Modifiable[S] with KeyMap[Attr[S], Attr.Update[S], Proc.Update[S]] {
      final val slot = 0

      protected def wrapKey(key: String) = AttrKey(key)

      def put(key: String, value: Attr[S])(implicit tx: S#Tx): Unit = add(key, value)

      def contains(key: String)(implicit tx: S#Tx): Boolean = map.contains(key)

      def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Proc.Update[S]] = {
        val changes = foldUpdate(pull)
        if (changes.isEmpty) None
        else Some(Proc.Update(proc,
          changes.map({
            case (key, u) => Proc.AttrChange(key, u.element, u.change)
          })(breakOut)))
      }

      protected def map: SkipList.Map[S, String, Entry] = attributeMap

      protected def valueInfo = attributeEntryInfo[S]

      def apply[A[~ <: Sys[~]] <: Attr[_]](key: String)(implicit tx: S#Tx,
                                                      tag: reflect.ClassTag[A[S]]): Option[A[S]#Peer] =
        get(key) match {
          // cf. stackoverflow #16377741
          case Some(attr) => tag.unapply(attr).map(_.peer) // Some(attr.peer)
          case _          => None
        }
    }

    object scans extends Scans.Modifiable[S] with KeyMap[Scan[S], Scan.Update[S], Proc.Update[S]] {
      final val slot = 1

      protected def wrapKey(key: String) = ScanKey(key)

      def add(key: String)(implicit tx: S#Tx): Scan[S] =
        get(key).getOrElse {
          val res = Scan[S]
          add(key, res)
          res
        }

      def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Proc.Update[S]] = {
        val changes = foldUpdate(pull)
        if (changes.isEmpty) None
        else Some(Proc.Update(proc,
          changes.map({
            case (key, u) => Proc.ScanChange(key, u.scan, u.changes)
          })(breakOut)))
      }

      protected def map: SkipList.Map[S, String, Entry] = scanMap

      protected def valueInfo = scanEntryInfo[S]
    }

    private object StateEvent
      extends evti.TriggerImpl[S, Proc.Update[S], Proc[S]]
      with evt.InvariantEvent [S, Proc.Update[S], Proc[S]]
      with evti.Root          [S, Proc.Update[S]]
      with ProcEvent {

      final val slot = 2
    }

    private object ChangeEvent
      extends evt.impl.EventImpl[S, Proc.Update[S], Proc[S]]
      with evt.InvariantEvent   [S, Proc.Update[S], Proc[S]]
      with ProcEvent {

      final val slot = 3

      def connect   ()(implicit tx: S#Tx): Unit = {
        graph.changed ---> this
        attributes    ---> this
        scans         ---> this
        StateEvent    ---> this
      }
      def disconnect()(implicit tx: S#Tx): Unit = {
        graph.changed -/-> this
        attributes    -/-> this
        scans         -/-> this
        StateEvent    -/-> this
      }

      def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Proc.Update[S]] = {
        // val graphOpt = if (graphemes .isSource(pull)) graphemes .pullUpdate(pull) else None
        val graphCh  = graph.changed
        val graphOpt = if (pull.contains(graphCh   )) pull(graphCh   ) else None
        val attrOpt  = if (pull.contains(attributes)) pull(attributes) else None
        val scansOpt = if (pull.contains(scans     )) pull(scans     ) else None
        val stateOpt = if (pull.contains(StateEvent)) pull(StateEvent) else None

        val seq0 = graphOpt.fold(Vec.empty[Change[S]]) { u =>
          Vector(GraphChange(u))
        }
        val seq1 = attrOpt.fold(seq0) { u =>
          if (seq0.isEmpty) u.changes else seq0 ++ u.changes
        }
        val seq2 = scansOpt.fold(seq1) { u =>
          if (seq1.isEmpty) u.changes else seq1 ++ u.changes
        }
        val seq3 = stateOpt.fold(seq2) { u =>
          if (seq2.isEmpty) u.changes else seq2 ++ u.changes
        }
        if (seq3.isEmpty) None else Some(Proc.Update(proc, seq3))
      }
    }

    final def select(slot: Int /*, invariant: Boolean */): Event[S, Any, Any] = (slot: @switch) match {
      case ChangeEvent.slot => ChangeEvent
      // case graphemes .slot => graphemes
      case attributes.slot => attributes
      case scans     .slot => scans
      case StateEvent.slot => StateEvent
    }

    //      final def stateChanged : evt.Event[ S, StateChange[ S ], Proc[ S ]] = StateEvent
    final def changed: evt.Event[S, Update[S], Proc[S]] = ChangeEvent

    final protected def writeData(out: DataOutput): Unit = {
      out.writeShort(SER_VERSION)
      // name_#     .write(out)
      graph       .write(out)
      // graphemeMap .write(out)
      attributeMap.write(out)
      scanMap     .write(out)
    }

    final protected def disposeData()(implicit tx: S#Tx): Unit = {
      // name_#     .dispose()
      graph       .dispose()
      // graphemeMap.dispose()
      attributeMap.dispose()
      scanMap     .dispose()
    }

    override def toString() = "Proc" + id
  }

  private final class New[S <: Sys[S]](implicit tx0: S#Tx) extends Impl[S] {
    protected val targets       = evt.Targets[S](tx0)
    val graph                   = SynthGraphs.newVar(SynthGraphs.empty)
    protected val scanMap       = SkipList.Map.empty[S, String, ScanEntry[S]]
    protected val attributeMap  = SkipList.Map.empty[S, String, AttrEntry[S]]
  }

  private final class Read[S <: Sys[S]](in: DataInput, access: S#Acc, protected val targets: evt.Targets[S])
                                       (implicit tx0: S#Tx)
    extends Impl[S] {

    {
      val serVer = in.readShort()
      require(serVer == SER_VERSION, s"Incompatible serialized (found $serVer, required $SER_VERSION)")
    }

    val graph                   = SynthGraphs.readVar(in, access)
    protected val attributeMap  = SkipList.Map.read[S, String, AttrEntry[S]](in, access)
    protected val scanMap       = SkipList.Map.read[S, String, ScanEntry[S]](in, access)
  }
}
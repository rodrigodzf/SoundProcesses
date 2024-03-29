/*
 *  BiPin.scala
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

package de.sciss.lucre
package bitemp

import impl.{BiPinImpl => Impl}
import de.sciss.lucre.{event => evt}
import collection.immutable.{IndexedSeq => Vec}
import evt.{EventLike, Sys}
import stm.Disposable
import de.sciss.serial.{Writable, Serializer, DataInput}
import de.sciss.model
import expr.ExprType1

object BiPin {
  final case class Update[S <: Sys[S], A](pin: BiPin[S, A], changes: Vec[Change[S, A]])

  sealed trait Change[S <: Sys[S], A]

  sealed trait Collection[S <: Sys[S], A] extends Change[S, A] {
    def value: (Long, A)
    def elem: BiExpr[S, A]
  }

  final case class Added  [S <: Sys[S], A](value: (Long, A), elem: BiExpr[S, A]) extends Collection[S, A]
  final case class Removed[S <: Sys[S], A](value: (Long, A), elem: BiExpr[S, A]) extends Collection[S, A]
  final case class Element[S <: Sys[S], A](elem: BiExpr[S, A], elemUpdate: model.Change[(Long, A)])
    extends Change[S, A]

  type Leaf[S <: Sys[S], A] = Vec[BiExpr[S, A]]

  object Modifiable {
    /** Extractor to check if a `BiPin` is actually a `BiPin.Modifiable`. */
    def unapply[S <: Sys[S], A](v: BiPin[S, A]): Option[Modifiable[S, A]] = {
      if (v.isInstanceOf[Modifiable[_, _]]) Some(v.asInstanceOf[Modifiable[S, A]]) else None
    }

    def read[S <: Sys[S], A](in: DataInput, access: S#Acc)
                            (implicit tx: S#Tx, biType: ExprType1[A]): Modifiable[S, A] = {
      Impl.readModifiable[S, A](in, access)
    }

    def apply[S <: Sys[S], A](implicit tx: S#Tx, biType: ExprType1[A]): Modifiable[S, A] =
      Impl.newModifiable[S, A]

    def serializer[S <: Sys[S], A](implicit biType: ExprType1[A]): Serializer[S#Tx, S#Acc, BiPin.Modifiable[S, A]] =
      Impl.modifiableSerializer[S, A]
  }

  trait Modifiable[S <: Sys[S], A] extends BiPin[S, A] {
    def add(elem: BiExpr[S, A])(implicit tx: S#Tx): Unit
    def remove(elem: BiExpr[S, A])(implicit tx: S#Tx): Boolean
    def clear()(implicit tx: S#Tx): Unit
  }

  def read[S <: Sys[S], A](in: DataInput, access: S#Acc)
                          (implicit tx: S#Tx, biType: ExprType1[A]): BiPin[S, A] = {
    Impl.read[S, A](in, access)
  }

  def serializer[S <: Sys[S], A](implicit biType: ExprType1[A]): Serializer[S#Tx, S#Acc, BiPin[S, A]] =
    Impl.serializer[S, A]
}

sealed trait BiPin[S <: Sys[S], A] extends Writable with Disposable[S#Tx] {

  import BiPin.Leaf

  final protected type Elem = BiExpr[S, A]

  //   def value( implicit tx: S#Tx, time: Chronos[ S ]) : A

  def modifiableOption: Option[BiPin.Modifiable[S, A]]

  /** Queries the element valid for the given point in time.
    * Unlike, `intersect`, if there are multiple elements sharing
    * the same point in time, this returns the most recently added element.
    *
    * We propose that this should be the unambiguous way to evaluate
    * the `BiPin` for a given moment in time.
    *
    * @param time the query time point
    * @return  an element for the given time point, if it exists, otherwise `None`
    */
  def at(time: Long)(implicit tx: S#Tx): Option[Elem]

  def valueAt(time: Long)(implicit tx: S#Tx): Option[A]

  /** Finds the entry at the given time, or the closest entry before the given time.
    *
    * @param time the query time
    * @return     the entry nearest in time to the query time, but not later than the
    *             query time, or `None` if there is no entry at such time
    */
  def floor(time: Long)(implicit tx: S#Tx): Option[Elem]

  /** Finds the entry at the given time, or the closest entry after the given time.
    *
    * @param time the query time
    * @return     the entry nearest in time to the query time, but not earlier than the
    *             query time, or `None` if there is no entry at such time
    */
  def ceil(time: Long)(implicit tx: S#Tx): Option[Elem]

  /** Queries all elements which are found at a given point in time.
    * There may be multiple time expressions which are not equal but
    * evaluate to the same moment in time. It is thus possible that
    * for a given point, multiple elements are found.
    *
    * @param time the query point
    * @return  the sequence of elements found along with their time expressions
    */
  def intersect(time: Long)(implicit tx: S#Tx): Leaf[S, A]

  //   def projection( implicit tx: S#Tx, time: Chronos[ S ]) : Expr[ S, A ]

  //   def collectionChanged:  EventLike[ S, BiPin.Collection[ S, A ], BiPin[ S, A ]]
  //   def elementChanged:     EventLike[ S, BiPin.Element[    S, A ], BiPin[ S, A ]]
  def changed: EventLike[S, BiPin.Update[S, A]]

  /** Finds the entry with the smallest time which is greater than _or equal_ to the query time.
    *
    * @param time the query time
    * @return     the time corresponding to the next entry, or `None` if there is no entry
    *             at or later than the given time
    */
  def nearestEventAfter(time: Long)(implicit tx: S#Tx): Option[Long]

  def debugList()(implicit tx: S#Tx): List[(Long, A)]
}
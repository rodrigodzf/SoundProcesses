/*
 *  BiGroupImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2012 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.lucre.expr
package impl

import de.sciss.lucre.{event => evt, DataInput, DataOutput}
import evt.{Event, EventLike}
import de.sciss.lucre.stm.{TxnSerializer, Sys}
import de.sciss.collection.txn
import txn.{SpaceSerializers, SkipOctree}
import collection.immutable.{IndexedSeq => IIdxSeq}
import de.sciss.collection.geom.{Rectangle, Point2D, Point2DLike, Square, Space}
import Space.TwoDim

/**
 * TODO need a Long based 2D space
 */
object BiGroupImpl {
   import BiGroup.Var

   var VERBOSE = true

   private val MAX_SQUARE  = Square( 0, 0, 0x40000000 )
   private val MIN_COORD   = MAX_SQUARE.left
   private val MAX_COORD   = MAX_SQUARE.right

//   private final case class Entry[ Elem ]( )

   private type Leaf[ S <: Sys[ S ], Elem ] = (SpanLike, IIdxSeq[ (Expr[ S, SpanLike ], Elem) ])
   private type Tree[ S <: Sys[ S ], Elem ] = SkipOctree[ S, TwoDim, Leaf[ S, Elem ]]

   private def spanToPoint( span: SpanLike ) : Point2D = span match {
      case Span( start, stop )=> Point2D( start.toInt, (stop - 1).toInt )
      case Span.From( start ) => Point2D( start.toInt, MAX_COORD )
      case Span.Until( stop ) => Point2D( MIN_COORD, (stop - 1).toInt )
      case Span.All           => Point2D( MIN_COORD, MAX_COORD )
      case Span.Void          => Point2D( MAX_COORD, MIN_COORD )  // ??? what to do with this case ??? forbid?
   }

   def newGenericVar[ S <: Sys[ S ], Elem, U ]( eventView: Elem => EventLike[ S, U, Elem ])(
      implicit tx: S#Tx, elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ], spanType: Type[ SpanLike ]) : Var[ S, Elem, U ] = {

      implicit val pointView: (Leaf[ S, Elem ], S#Tx) => Point2DLike = (tup, tx) => spanToPoint( tup._1 )
      implicit val hyperSer   = SpaceSerializers.SquareSerializer
      implicit val exprSer: TxnSerializer[ S#Tx, S#Acc, Expr[ S, SpanLike ]] = spanType.serializer[ S ]
      val tree: Tree[ S, Elem ] = SkipOctree.empty[ S, TwoDim, Leaf[ S, Elem ]]( MAX_SQUARE )
      new ImplNew( evt.Targets[ S ], tree, eventView )
   }

   private def serializer[ S <: Sys[ S ], Elem, U ]( eventView: Elem => EventLike[ S, U, Elem ])(
      implicit elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ],
      spanType: Type[ SpanLike ]) : evt.NodeSerializer[ S , Impl[ S, Elem, U ]] = new Ser( eventView )

   private class Ser[ S <: Sys[ S ], Elem, U ]( eventView: Elem => EventLike[ S, U, Elem ])
                                              ( implicit elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ],
                                                spanType: Type[ SpanLike ])
   extends evt.NodeSerializer[ S , Impl[ S, Elem, U ]] {
      def read( in: DataInput, access: S#Acc, targets: evt.Targets[ S ])( implicit tx: S#Tx ) : Impl[ S, Elem, U ] = {
         implicit val pointView: (Leaf[ S, Elem ], S#Tx) => Point2DLike = (tup, tx) => spanToPoint( tup._1 )
         implicit val hyperSer   = SpaceSerializers.SquareSerializer
         implicit val exprSer: TxnSerializer[ S#Tx, S#Acc, Expr[ S, SpanLike ]] = spanType.serializer[ S ]
         val tree: Tree[ S, Elem ] = SkipOctree.read[ S, TwoDim, Leaf[ S, Elem ]]( in, access )
         new ImplNew( targets, tree, eventView )
      }
   }

   private sealed trait Impl[ S <: Sys[ S ], Elem, U ]
   extends Var[ S, Elem, U ]
   with evt.Trigger.Impl[ S, BiGroup.Update[ S, Elem, U ], BiGroup.Update[ S, Elem, U ], BiGroup[ S, Elem, U ]]
   with evt.StandaloneLike[ S, BiGroup.Update[ S, Elem, U ], BiGroup[ S, Elem, U ]]
   {
      protected def tree: Tree[ S, Elem ]
      protected def eventView: Elem => EventLike[ S, U, Elem ]
      implicit protected def elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ]
      implicit protected def spanType: Type[ SpanLike ]

      override def toString() = "BiGroup" + tree.id

      // ---- event behaviour ----

      final protected def disposeData()( implicit tx: S#Tx ) {
         tree.dispose()
      }

      final protected def writeData( out: DataOutput ) {
         tree.write( out )
      }

      private def foreach( fun: Elem => Unit )( implicit tx: S#Tx ) {
         tree.iterator.foreach { case (_, seq) =>
            seq.foreach { case (_, elem) =>
               fun( elem )
            }
         }
      }

      final def connect()( implicit tx: S#Tx ) {
         foreach( eventView( _ ) ---> this )
      }

      final def disconnect()( implicit tx: S#Tx ) {
         foreach( eventView( _ ) -/-> this )
      }

      protected def reader : evt.Reader[ S, BiGroup[ S, Elem, U ]] = serializer( eventView )

      final def pullUpdate( pull: evt.Pull[ S ])( implicit tx: S#Tx ) : Option[ BiGroup.Update[ S, Elem, U ]] = {
         if( pull.parents( this ).isEmpty ) {
            pull.resolve[ BiGroup.Update[ S, Elem, U ]]

         } else {
            println( "TODO" )
            None
         }
      }

      // ---- collection behaviour ----

      final def add( span: Expr[ S, SpanLike ], elem: Elem )( implicit tx: S#Tx ) {
         val spanVal = span.value
         val point   = spanToPoint( spanVal )
if( VERBOSE ) println( "add at point " + point )
         val entry   = (span, elem)
         tree.transformAt( point ) {
            case None               => Some( spanVal -> IIdxSeq( entry ))
            case Some( (_, seq) )   => Some( spanVal -> (seq :+ entry) )
         }
         fire( BiGroup.Added( this, elem ))
      }
      final def remove( span: Expr[ S, SpanLike ], elem: Elem )( implicit tx: S#Tx ) : Boolean = {
         val spanVal = span.value
         val point   = spanToPoint( spanVal )
         val entry   = (span, elem)
         val res     = tree.get( point ) match {
            case Some( (_, IIdxSeq( single )) ) =>
               if( single == entry ) {
                  tree.removeAt( point )
                  true
               } else {
                  false
               }
            case Some( (_, seq) ) =>
               val seqNew = seq.filterNot( _ == entry )
               if( seqNew.size != seq.size ) {
                  tree.add( (spanVal, seqNew) )
                  true
               } else {
                  false
               }
            case None => false
         }
         if( res ) fire( BiGroup.Removed( this, elem ))
         res
      }

      final def debugList()( implicit tx: S#Tx ) : List[ (SpanLike, Elem) ] =
         tree.toList.flatMap { case (span, seq) => seq.map { case (_, elem) => span -> elem }}

      final def iterator( implicit tx: S#Tx, time: Chronos[ S ]) : txn.Iterator[ S#Tx, (SpanLike, IIdxSeq[ (Expr[ S, SpanLike ], Elem) ])]  =
         iteratorAt( time.time.value )

      final def iteratorAt( time: Long )( implicit tx: S#Tx ) : txn.Iterator[ S#Tx, (SpanLike, IIdxSeq[ (Expr[ S, SpanLike ], Elem) ])] = {
         val ti      = time.toInt
         val start   = ti
         val stop    = ti + 1
//         val shape = Rectangle( ti, MIN_COORD, MAX_COORD - ti + 1, ti - MIN_COORD + 1 )
         // horizontally: until query_stop; vertically: from query_start
         val shape = Rectangle( MIN_COORD, start, stop - MIN_COORD, MAX_COORD - start + 1 )
         rangeSearch( shape )
      }

      final def iteratorWithin( span: SpanLike )( implicit tx: S#Tx ) : txn.Iterator[ S#Tx, (SpanLike, IIdxSeq[ (Expr[ S, SpanLike ], Elem) ])] = {
         // horizontally: until query_stop; vertically: from query_start
         span match {
            case Span( startL, stopL ) =>
               val start = startL.toInt
               val stop  = stopL.toInt
               val shape = Rectangle( MIN_COORD, start, stop - MIN_COORD, MAX_COORD - start /* + 1 XXX int overflow */ )
               rangeSearch( shape )

            case Span.From( startL ) =>
               val start = startL.toInt
               val shape = Rectangle( MIN_COORD, start, MAX_COORD - MIN_COORD /* + 1 XXX int overflow */, MAX_COORD - start /* + 1 XXX int overflow */ )
               rangeSearch( shape )

            case Span.Until( stopL ) =>
               val stop  = stopL.toInt
               val shape = Rectangle( MIN_COORD, MIN_COORD, stop - MIN_COORD, MAX_COORD - MIN_COORD /* + 1 XXX int overflow */ )
               rangeSearch( shape )

            case Span.All  => tree.iterator
            case Span.Void => sys.error( "TODO" ) // txn.Iterator.empty
         }
      }

      private def rangeSearch( shape: Rectangle )( implicit tx: S#Tx ) : txn.Iterator[ S#Tx, (SpanLike, IIdxSeq[ (Expr[ S, SpanLike ], Elem) ])] = {
         val res = tree.rangeQuery( shape ) // .flatMap ....
if( VERBOSE ) println( "Range in " + shape + " --> right = " + shape.right + "; bottom = " + shape.bottom + " --> found some? " + !res.isEmpty )
         res
      }

      final def changed : Event[ S, BiGroup.Update[ S, Elem, U ], BiGroup[ S, Elem, U ]] = this
   }

   private final class ImplNew[ S <: Sys[ S ], Elem, U ]( protected val targets: evt.Targets[ S ],
                                                          protected val tree: Tree[ S, Elem ],
                                                          protected val eventView: Elem => EventLike[ S, U, Elem ])
                                                        ( implicit protected val elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ],
                                                          protected val spanType: Type[ SpanLike ])
   extends Impl[ S, Elem, U ]
}

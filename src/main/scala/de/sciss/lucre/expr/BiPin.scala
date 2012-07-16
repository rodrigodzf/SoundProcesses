/*
 *  BiPin.scala
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

package de.sciss.lucre
package expr

import impl.BiPinImpl
import de.sciss.lucre.{event => evt, DataInput}
import collection.immutable.{IndexedSeq => IIdxSeq}
import de.sciss.lucre.stm.{TxnSerializer, Sys}
import evt.{Event, EventLike}

object BiPin {
   import expr.{Expr => Ex}

   type ExprUpdate[ S <: Sys[ S ], A ] = Update[ S, Ex[ S, A ], evt.Change[ A ]]

   sealed trait Update[ S <: Sys[ S ], Elem, U ] {
      def pin: BiPin[ S, Elem, U ]
   }
   final case class Collection[ S <: Sys[ S ], Elem, U ]( pin: BiPin[ S, Elem, U ], changes: IIdxSeq[ Region[ Elem ]]) extends Update[ S, Elem, U ]
   final case class Element[ S <: Sys[ S ], Elem, U ]( pin: BiPin[ S, Elem, U ], changes: IIdxSeq[ (Elem, U) ]) extends Update[ S, Elem, U ]

   type Region[ Elem ] = (SpanLike, Elem)

   type TimedElem[ S <: Sys[ S ], Elem ] = (Ex[ S, Long ], Elem)
   type Leaf[      S <: Sys[ S ], Elem ] = /* (Long, */ IIdxSeq[ TimedElem[ S, Elem ]] /* ) */

   type Expr[ S <: Sys[ S ], A ]    = BiPin[ S, Ex[ S, A ], evt.Change[ A ]]
   type ExprVar[ S <: Sys[ S ], A ] = Var[   S, Ex[ S, A ], evt.Change[ A ]]

   object isVar {
      def unapply[ S <: Sys[ S ], Elem, U ]( v: BiPin[ S, Elem, U ]) : Option[ Var[ S, Elem, U ]] = {
         if( v.isInstanceOf[ Var[ _, _, _ ]]) Some( v.asInstanceOf[ Var[ S, Elem, U ]]) else None
      }
   }

   trait Var[ S <: Sys[ S ], Elem, U ] extends BiPin[ S, Elem, U ] {
//      def get( implicit tx: S#Tx, time: Chronos[ S ]) : Expr[ S, A ]
//      def getAt( time: Long )( implicit tx: S#Tx ) : Expr[ S, A ]
//      def set( value: Expr[ S, A ])( implicit tx: S#Tx ) : Unit
//      def add( time: Expr[ S, Long ], value: Expr[ S, A ])( implicit tx: S#Tx ) : Option[ Expr[ S, A ]]
//      def remove( time: Expr[ S, Long ])( implicit tx: S#Tx ) : Boolean
//      def removeAt( time: Long )( implicit tx: S#Tx ) : Option[ Expr[ S, Long ]]
//      def removeAll( span: SpanLike )( implicit tx: S#Tx ) : Unit

      def add(    time: Ex[ S, Long ], elem: Elem )( implicit tx: S#Tx ) : Unit
      def remove( time: Ex[ S, Long ], elem: Elem )( implicit tx: S#Tx ) : Boolean
      def clear()( implicit tx: S#Tx ) : Unit
   }

//   def newVar[ S <: Sys[ S ], A ]( implicit tx: S#Tx, elemType: BiType[ A ]) : Var[ S, Expr[ S, A ], evt.Change[ A ]] =
//      BiGroupImpl.newGenericVar[ S, Expr[ S, A ], evt.Change[ A ]]( _.changed )( tx, elemType.serializer[ S ], elemType.spanLikeType )
//
//   def newGenericVar[ S <: Sys[ S ], Elem, U ]( eventView: Elem => EventLike[ S, U, Elem ])
//      ( implicit tx: S#Tx, elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ] with evt.Reader[ S, Elem ],
//        spanType: Type[ SpanLike ]) : Var[ S, Elem, U ] = BiGroupImpl.newGenericVar( eventView )
//
//   def readGenericVar[ S <: Sys[ S ], Elem, U ]( in: DataInput, access: S#Acc, eventView: Elem => EventLike[ S, U, Elem ])
//         ( implicit tx: S#Tx, elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ] with evt.Reader[ S, Elem ],
//           spanType: Type[ SpanLike ]) : Var[ S, Elem, U ] = BiGroupImpl.readGenericVar( in, access, eventView )

//   def newVar[ S <: Sys[ S ], A ]( init: Expr[ S, A ])
//                                 ( implicit tx: S#Tx, peerType: BiType[ A ]) : Var[ S, Expr[ S, A ], evt.Change[ A ]] =
//      BiPinImpl.newVar( init )

   def newExprVar[ S <: Sys[ S ], A ]( default: Ex[ S, A ])( implicit tx: S#Tx, elemType: BiType[ A ]) : ExprVar[ S, A ] =
      BiPinImpl.newVar[ S, Ex[ S, A ], evt.Change[ A ]]( default, _.changed )( tx, elemType.serializer[ S ], elemType.longType )

   def newConfluentExprVar[ S <: Sys[ S ], A ]( default: Ex[ S, A ])
                                              ( implicit tx: S#Tx,
                                                elemType: BiType[ A ]) : ExprVar[ S, A ] =
      BiPinImpl.newConfluentVar[ S, Ex[ S, A ], evt.Change[ A ]]( default, _.changed )( tx, elemType.serializer[ S ], elemType.longType )

   def readExprVar[ S <: Sys[ S ], A ]( in: DataInput, access: S#Acc )
                                      ( implicit tx: S#Tx, elemType: BiType[ A ]) : ExprVar[ S, A ] = {

//      BiPinImpl.readExprVar( in, access )
      BiPinImpl.readVar[ S, Ex[ S, A ], evt.Change[ A ]]( in, access, _.changed )( tx, elemType.serializer[ S ], elemType.longType )
   }

   def readExpr[ S <: Sys[ S ], A ]( in: DataInput, access: S#Acc )
                                   ( implicit tx: S#Tx, elemType: BiType[ A ]) : Expr[ S, A ] = {

//      BiPinImpl.readExpr( in, access )
      BiPinImpl.read[ S, Ex[ S, A ], evt.Change[ A ]]( in, access, _.changed )( tx, elemType.serializer[ S ], elemType.longType )
   }

   def serializer[ S <: Sys[ S ], Elem, U ]( eventView: Elem => EventLike[ S, U, Elem ])
                                           ( implicit elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ] with evt.Reader[ S, Elem ],
                                             timeType: Type[ Long ]) : TxnSerializer[ S#Tx, S#Acc, BiPin[ S, Elem, U ]] =
      BiPinImpl.serializer[ S, Elem, U ]( eventView )

   def exprSerializer[ S <: Sys[ S ], A ]( implicit elemType: BiType[ A ]) : TxnSerializer[ S#Tx, S#Acc, Expr[ S, A ]] = {
      import elemType.serializer
      implicit val timeType = elemType.longType
      BiPinImpl.serializer[ S, Ex[ S, A ], evt.Change[ A ]]( _.changed )
   }

   def varSerializer[ S <: Sys[ S ], Elem, U ]( eventView: Elem => EventLike[ S, U, Elem ])
                                              ( implicit elemSerializer: TxnSerializer[ S#Tx, S#Acc, Elem ] with evt.Reader[ S, Elem ],
                                                timeType: Type[ Long ]) : TxnSerializer[ S#Tx, S#Acc, BiPin.Var[ S, Elem, U ]] =
      BiPinImpl.varSerializer[ S, Elem, U ]( eventView )

   def exprVarSerializer[ S <: Sys[ S ], A ]( implicit elemType: BiType[ A ]) : TxnSerializer[ S#Tx, S#Acc, BiPin.ExprVar[ S, A ]] = {
      import elemType.serializer
      implicit val timeType = elemType.longType
      BiPinImpl.varSerializer[ S, Ex[ S, A ], evt.Change[ A ]]( _.changed )
   }

//   implicit def serializer[ S <: Sys[ S ], A ]( implicit peerType: BiType[ A ]) :
//      evt.Reader[ S, BiPin[ S, A ]] with TxnSerializer[ S#Tx, S#Acc, BiPin[ S, A ]] = BiPinImpl.serializer[ S, A ]
//
//   implicit def varSerializer[ S <: Sys[ S ], A ]( implicit peerType: BiType[ A ]) :
//      evt.Reader[ S, Var[ S, A ]] with TxnSerializer[ S#Tx, S#Acc, Var[ S, A ]] = BiPinImpl.varSerializer[ S, A ]
}
sealed trait BiPin[ S <: Sys[ S ], Elem, U ] extends evt.Node[ S ] {
   import BiPin.Leaf

//   def value( implicit tx: S#Tx, time: Chronos[ S ]) : A

   /**
    * Queries the element valid for the given point in time.
    * Unlike, `intersect`, if there are multiple elements sharing
    * the same point in time, this returns the most recently added element.
    *
    * We propose that this should be the unambiguous way to evaluate
    * the `BiPin` for a given moment in time.
    *
    * @param time the query time point
    * @return  an element for the given time point
    */
   def at( time: Long )( implicit tx: S#Tx ) : Elem

   /**
    * Queries all elements which are found at a given point in time.
    * There may be multiple time expressions which are not equal but
    * evaluate to the same moment in time. It is thus possible that
    * for a given point, multiple elements are found.
    *
    * @param time the query point
    * @return  the sequence of elements found along with their time expressions
    */
   def intersect( time: Long )( implicit tx: S#Tx ) : Leaf[ S, Elem ]
//   def projection( implicit tx: S#Tx, time: Chronos[ S ]) : Expr[ S, A ]

   def collectionChanged:  Event[ S, BiPin.Collection[ S, Elem, U ], BiPin[ S, Elem, U ]]
   def elementChanged:     Event[ S, BiPin.Element[    S, Elem, U ], BiPin[ S, Elem, U ]]
   def changed :           Event[ S, BiPin.Update[     S, Elem, U ], BiPin[ S, Elem, U ]]

   def nearestEventAfter( time: Long )( implicit tx: S#Tx ) : Option[ Long ]

   def debugList()( implicit tx: S#Tx ) : List[ (Long, Elem) ]
}
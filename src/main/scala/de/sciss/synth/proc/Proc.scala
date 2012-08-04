/*
 *  Proc.scala
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

package de.sciss.synth.proc

import de.sciss.synth.SynthGraph
import de.sciss.lucre.{stm, event => evt, bitemp, expr, DataInput}
import stm.Sys
import bitemp.{BiPin, Chronos}
import expr.Expr
import collection.immutable.{IndexedSeq => IIdxSeq}

object Proc {
   // ---- implementation forwards ----

   def apply[ S <: Sys[ S ]]()( implicit tx: S#Tx ) : Proc[ S ] = impl.ProcImpl[ S ]()

   def read[ S <: Sys[ S ]]( in: DataInput, access: S#Acc )( implicit tx: S#Tx ) : Proc[ S ] = impl.ProcImpl.read( in, access )

   implicit def serializer[ S <: Sys[ S ]] : evt.NodeSerializer[ S, Proc[ S ]] = impl.ProcImpl.serializer[ S ]

   // ---- event types ----

   sealed trait Update[ S <: Sys[ S ]] {
      def proc: Proc[ S ]
   }
   sealed trait StateChange[ S <: Sys[ S ]] extends Update[ S ]
   final case class Rename[ S <: Sys[ S ]](        proc: Proc[ S ], change: evt.Change[ String ])             extends StateChange[ S ]
   final case class GraphChange[ S <: Sys[ S ]](   proc: Proc[ S ], change: evt.Change[ ProcGraph ])          extends StateChange[ S ]
   final case class PlayingChange[ S <: Sys[ S ]]( proc: Proc[ S ], change: BiPin.Expr.Update[ S, Boolean ])  extends StateChange[ S ]
//   final case class FreqChange[ S <: Sys[ S ]](    proc: Proc[ S ], change: BiPin.ExprUpdate[ S, Double ])    extends Update[ S ]
   final case class ParamChange[ S <: Sys[ S ]]( proc: Proc[ S ], changes: Map[ String, IIdxSeq[ BiPin.Expr.Update[ S, Param ]]]) extends Update[ S ]
}
trait Proc[ S <: Sys[ S ]] extends evt.Node[ S ] {
   import Proc._

//   def id: S#ID

   // ---- "fields" ----

// OOO
//   def name_# : Expr.Var[ S, String ]
   def name( implicit tx: S#Tx ) : Expr[ S, String ]
   def name_=( expr: Expr[ S, String ])( implicit tx: S#Tx ) : Unit

   def graph( implicit tx: S#Tx ) : ProcGraph
   def graph_=( g: ProcGraph )( implicit tx: S#Tx ) : Unit
   def graph_=( block: => Any )( implicit tx: S#Tx ) : Unit

// OOO
//   def playing_# : Expr.Var[ S, Boolean ]
   def playing( implicit tx: S#Tx, chr: Chronos[ S ]) : Expr[ S, Boolean ]
   def playing_=( expr: Expr[ S, Boolean ])( implicit tx: S#Tx, chr: Chronos[ S ]) : Unit

   // ---- controls preview demo ----

//   def freq_# : Expr.Var[ S, Double ]
//   def freq( implicit tx: S#Tx, chr: Chronos[ S ]) : Expr[ S, Double ]
//   def freq_=( f: Expr[ S, Double ])( implicit tx: S#Tx, chr: Chronos[ S ]) : Unit

   def par: ParamMap[ S ]

   /**
    * Same as `playing = true`
    */
   def play()( implicit tx: S#Tx, chr: Chronos[ S ]) : Unit
   /**
    * Same as `playing = false`
    */
   def stop()( implicit tx: S#Tx, chr: Chronos[ S ]) : Unit

   // ---- events ----

   def stateChanged:    evt.Event[ S, StateChange[ S ],  Proc[ S ]]
//   def graphChanged:    evt.Event[ S, GraphChange[ S ],    Proc[ S ]]
//   def playingChanged:  evt.Event[ S, PlayingChange[ S ],  Proc[ S ]]
   def paramChanged:    evt.Event[ S, ParamChange[ S ],    Proc[ S ]]
//   def freqChanged:     evt.Event[ S, FreqChange[ S ],     Proc[ S ]]

   def changed:         evt.Event[ S, Update[ S ],         Proc[ S ]]
}
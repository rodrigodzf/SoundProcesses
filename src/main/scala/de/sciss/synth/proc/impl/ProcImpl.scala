/*
 *  ProcImpl.scala
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
package impl

import de.sciss.lucre.{event => evt}
import de.sciss.synth.SynthGraph
import de.sciss.lucre.expr.Expr
import de.sciss.synth.expr._
import de.sciss.lucre.{DataInput, DataOutput}
import de.sciss.lucre.stm.{InMemory, TxnSerializer, Sys}
import ExprImplicits._

object ProcImpl {
   private val SER_VERSION = 11

   def apply[ S <: Sys[ S ]]()( implicit tx: S#Tx ) : Proc[ S ] = new New[ S ]( tx )

   def read[ S <: Sys[ S ]]( in: DataInput, access: S#Acc )( implicit tx: S#Tx ) : Proc[ S ] =
      serializer[ S ].read( in, access )

   def serializer[ S <: Sys[ S ]] : TxnSerializer[ S#Tx, S#Acc, Proc[ S ]] =
      anySer.asInstanceOf[ TxnSerializer[ S#Tx, S#Acc, Proc[ S ]]]

   val emptyGraph = SynthGraph {}

   private val anySer = new Serializer[ InMemory ]

   private class Serializer[ S <: Sys[ S ]] extends evt.NodeSerializer[ S, Proc[ S ]] {
      def read( in: DataInput, access: S#Acc, targets: evt.Targets[ S ])( implicit tx: S#Tx ) : Proc[ S ] =
         new Read( in, access, targets, tx )
   }

   @volatile private var declMap = Map.empty[ Class[ _ ], Decl[ _ ]]

   private def getDecl[ S <: Sys[ S ]]( implicit tx: S#Tx ) : Decl[ S ] = {
      val clz = tx.system.manifest.erasure
      declMap.getOrElse( clz, {
         val declNew = new Decl[ S ]
         declMap += clz -> declNew
         declNew
      }).asInstanceOf[ Decl[ S ]]
   }

   private class Decl[ S <: Sys[ S ]] extends evt.Decl[ S, Proc[ S ]] {
      val serializer: evt.Reader[ S, Impl[ S ]] = new evt.Reader[ S, Impl[ S ]] {
         def read( in: DataInput, access: S#Acc, targets: evt.Targets[ S ])( implicit tx: S#Tx ) : Impl[ S ] =
            new Read( in, access, targets, tx )
      }

      type Update = Proc.Update[ S ]

      import Proc._

// OOO
//      declare[ Renamed[        S ]]( _.renamed        )
//      declare[ GraphChanged[   S ]]( _.graphChanged   )
//      declare[ PlayingChanged[ S ]]( _.playingChanged )

      declare[ FreqChanged[ S ]]( _.freqChanged )
   }

   private sealed trait Impl[ S <: Sys[ S ]] extends Proc[ S ] with evt.Compound[ S, Proc[ S ], Decl[ S ]] {
      import Proc._

// OOO
//      protected def graphVar : S#Var[ SynthGraph ]

      final def name( implicit tx: S#Tx ) : String = {
//         name_#.value
"name"
      }
      final def name_=( s: Expr[ S, String ])( implicit tx: S#Tx ) {
// OOO
//         name_#.set( s )
      }
      final def playing( implicit tx: S#Tx ) : Boolean = {
//         playing_#.value
false
      }
      final def playing_=( b: Expr[ S, Boolean ])( implicit tx: S#Tx ) {
// OOO
//         playing_#.set( b )
      }
      final def graph( implicit tx: S#Tx ) : SynthGraph = {
// OOO
//         graphVar.get
emptyGraph
      }
      final def graph_=( g: SynthGraph )( implicit tx: S#Tx ) {
// OOO
//         val old = graphVar.get
//         if( old != g ) {
//            graphVar.set( g )
//            graphChanged( GraphChanged( this, evt.Change( old, g )))
//         }
      }
      final def graph_=( block: => Any )( implicit tx: S#Tx ) { graph_=( SynthGraph( block ))}
      final def play()( implicit tx: S#Tx ) {
// OOO
//         playing_#.set( true  )
      }
      final def stop()( implicit tx: S#Tx ) {
// OOO
//         playing_#.set( false )
      }

      protected def freqVar : S#Var[ Expr[ S, Double ]]

      final def freq( implicit tx: S#Tx ) : Double = freqVar.get.value
      final def freq_=( f: Expr[ S, Double ])( implicit tx: S#Tx ) {
         val before = freqVar.get
         if( before != f ) {
            val con = targets.nonEmpty
//            logEvent( this.toString + " set " + expr + " (con = " + con + ")" )
            if( con ) evt.Intruder.-/->( before.changed, freqChanged )
            freqVar.set( f )
            if( con ) {
               evt.Intruder.--->( f.changed, freqChanged )
               val beforeV = before.value
               val exprV   = f.value
               freqChanged( FreqChanged( this, evt.Change( beforeV, exprV )))
            }
         }
      }

// OOO
//      final def renamed             = name_#.changed.map( Renamed( this, _ ))
//      final def graphChanged        = event[ GraphChanged[ S ]]
//      final def playingChanged      = playing_#.changed.map( PlayingChanged( this, _ ))
      final def freqChanged         = event[ FreqChanged[ S ]] // freq_#.changed.map( FreqChanged( this, _ ))
// OOO
//      final def changed             = renamed | graphChanged | playingChanged | freqChanged

      final protected def writeData( out: DataOutput ) {
         out.writeUnsignedByte( SER_VERSION )
// OOO
//         name_#.write( out )
//         playing_#.write( out )

         freqVar.write( out )

// OOO
//         graphVar.write( out )
      }

      final protected def disposeData()( implicit tx: S#Tx ) {
// OOO
//         name_#.dispose()
//         playing_#.dispose()

         freqVar.dispose()

// OOO
//          graphVar.dispose()
      }

      override def toString() = "Proc" + id

      override def hashCode() : Int = id.##
      override def equals( that: Any ) = that.isInstanceOf[ Proc[ _ ]] &&
         (that.asInstanceOf[ Proc[ _ ]].id == id)
   }

   private final class New[ S <: Sys[ S ]]( tx0: S#Tx ) extends Impl[ S ] {
      protected val decl      = getDecl[ S ]( tx0 )
      protected val targets   = evt.Targets[ S ]( tx0 )
// OOO
//      val name_#              = Strings.newVar[ S ]( "unnamed" )( tx0 )
//      val playing_#           = Booleans.newVar[ S ]( true )( tx0 )
      protected val freqVar = {
         implicit val peerSer = Doubles.serializer[ S ]
         tx0.newVar[ Expr[ S, Double ]]( id, 441 )
      } //            = Doubles.newVar[ S ]( 441 )( tx0 )
// OOO
//      protected val graphVar  = tx0.newVar[ SynthGraph ]( id, emptyGraph )( SynthGraphSerializer )
   }

   private final class Read[ S <: Sys[ S ]]( in: DataInput, access: S#Acc, protected val targets: evt.Targets[ S ],
                                             tx0: S#Tx )
   extends Impl[ S ] {
      protected val decl      = getDecl[ S ]( tx0 );

      {
         val serVer = in.readUnsignedByte()
         require( serVer == SER_VERSION, "Incompatible serialized  (found " + serVer + ", required " + SER_VERSION + ")" )
      }

// OOO
//      val name_#              = Strings.readVar[  S ]( in, access )( tx0 )
//      val playing_#           = Booleans.readVar[ S ]( in, access )( tx0 )
      protected val freqVar = {
         implicit val peerSer = Doubles.serializer[ S ]
         tx0.readVar[ Expr[ S, Double ]]( id, in )
      }  // = Doubles.readVar[ S ]( in, access )( tx0 )
// OOO
//      protected val graphVar  = tx0.readVar[ SynthGraph ]( id, in )( SynthGraphSerializer )
   }
}

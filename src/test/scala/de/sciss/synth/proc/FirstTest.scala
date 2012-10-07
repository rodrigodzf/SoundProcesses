//package de.sciss.synth.proc
//
//import de.sciss.synth
//import synth.{SynthDef, Server, expr}
//import expr._
//import de.sciss.lucre.stm.{Sys, Cursor, InMemory}
//
//object FirstTest extends App {
//   implicit val system: InMemory = InMemory()
//   run[ InMemory ]()
//
//   def run[ S <: Sys[ S ]]()( implicit system: S, cursor: Cursor[ S ]) {
//      implicit val whyOhWhy = Proc.serializer[ S ]
//      val imp = ExprImplicits[ S ]
//      import imp._
//
//      val access = system.root { implicit tx => Proc[ S ]() }
//
//      cursor.step { implicit tx =>
//         val p = access.get
//         println( "Old name is " + p.name )
//         p.name = "Schnuckendorfer"
//         println( "New name is " + p.name )
//         p.graph = {
//            import synth._; import ugen._
//            val f = LFSaw.kr( 0.4 ).madd( 24, LFSaw.kr( Seq( 8, 7.23 )).madd( 3, 80 )).midicps
//            val c = CombN.ar( SinOsc.ar( f ) * 0.04, 0.2, 0.2, 4 )
//            Out.ar( 0, c )
//         }
//      }
//
//      val gr = cursor.step { implicit tx => access.get.graph }
//      println( "Graph now " + gr )
//
//      Server.run { s =>
//         val sd = SynthDef( "test", gr.expand )
//         sd.play
//         Thread.sleep( 4000 )
//         sys.exit( 0 )
//      }
//   }
//}

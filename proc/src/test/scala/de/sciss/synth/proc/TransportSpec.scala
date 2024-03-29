package de.sciss
package synth
package proc

import lucre.{bitemp, expr}
import bitemp.BiExpr
import expr.Expr
import collection.immutable.{IndexedSeq => Vec}
import span.Span
import de.sciss.synth.Curve.linear
import de.sciss.lucre.synth.InMemory

/** To run only this suite:
  *
  * test-only de.sciss.synth.proc.TransportSpec
  */
class TransportSpec extends ConfluentEventSpec {

  import Transport.Advance
  import Grapheme.Segment
  import Transport.Proc.{GraphemesChanged, Changed => ProcChanged}

  type I = InMemory

  //   import ConfluentReactive.inMemory

  import imp._

  def curve(amp: Expr[S, Double], shape: Curve = Curve.linear)(implicit tx: S#Tx) =
    Grapheme.Elem.Curve(amp -> shape)

  "Transport" should "notify observers about all relevant events" in { implicit system =>
    val obs = new Observation[S]
    val (pgH, t) = system.step { implicit tx =>
      val pg  = ProcGroup.Modifiable[S]
      val _t  = Transport.offline[S, I](pg, 10000.0) // ( tx, inMemory )
      _t.seek(0L)
      _t.react(obs.register)
      val res = tx.newHandle(pg)(ProcGroup.Modifiable.serializer[S])
      obs.assertEmpty()
      (res, _t)
    }

    system.step { implicit tx =>
      val pg = pgH()
      val p1 = Proc[S]
      val g1 = Grapheme.Modifiable[S]
      p1.scans.add("freq").addSource(Scan.Link.Grapheme(g1))
      p1.scans.add("egal")
      g1.add(7000L -> curve(441.0))
      val p2 = Proc[S]
      val g2 = Grapheme.Modifiable[S]
      p2.scans.add("amp").addSource(Scan.Link.Grapheme(g2))
      val pt1 = pg.add(Span(0L, 10000L), p1)
      val pt2 = pg.add(Span(5000L, 20000L), p2)
      obs.assertEquals(
        Advance(t, time = 0L, isSeek = false, isPlaying = false, added = Vec(pt1))
      )
      obs.clear()

      t.play()
      obs.assertEquals(
        Transport.Play(t, 0L)
      )
      obs.clear()

      g2.add(10000L, curve(0.5))
      g2.add(15000L, curve(0.7))
      g2.add(25000L, curve(1.0))

      t.step()
      obs.assertEquals(
        Advance(t, time = 5000L, isSeek = false, isPlaying = true, added = Vec(pt2))
      )
      obs.clear()

      t.step()
      obs.assertEquals(
        Advance(t, time = 7000L, isSeek = false, isPlaying = true, changes =
          Vec(pt1 -> GraphemesChanged[S](Map("freq" -> Vec(Segment.Const(Span.from(7000L), Vec(441.0))))))
        )
      )
      obs.clear()

      t.step()
// XXX TODO: fix this one
//      obs.assertEquals(
//        Advance(t, time = 10000L, isSeek = false, isPlaying = true, removed = Vec(pt1), changes =
//          Vec(pt2 -> GraphemesChanged[S](Map("amp" -> Vec(Segment.Curve(Span(10000L, 15000L), Vec((0.5, 0.7, linear)))))))
//        )
//      )
      obs.clear()

      t.step()
// XXX TODO: fix this one
//      obs.assertEquals(
//        Advance(t, time = 15000L, isSeek = false, isPlaying = true, changes =
//          Vec(pt2 -> GraphemesChanged[S](Map("amp" -> Vec(Segment.Curve(Span(15000L, 25000L), Vec((0.7, 1.0, linear)))))))
//        )
//      )
      obs.clear()

      t.step()
// XXX TODO: fix this one
//      obs.assertEquals(
//        Advance(t, time = 20000L, isSeek = false, isPlaying = true, removed = Vec(pt2))
//      )
      obs.clear()

      t.step()
      obs.assertEmpty()

      t.stop()
// XXX TODO: fix this one
//      obs.assertEquals(
//        Transport.Stop(t, 25000L) // will advance to grapheme stuff even beyond proc spans
//      )
      obs.clear()
    }
  }

  it should "handle process updates in a sensible way" in { implicit system =>
    val obs = new Observation[S]
    val (pgH, t) = system.step { implicit tx =>
      val pg = ProcGroup.Modifiable[S]
      val _t = Transport.offline[S, I](pg, 10000.0) // ( tx, inMemory )
      _t.seek(0L)
      _t.react(obs.register)
      val res = tx.newHandle(pg)(ProcGroup.Modifiable.serializer[S])
      obs.assertEmpty()
      (res, _t)
    }

    system.step { implicit tx =>
      val pg = pgH()
      val p1 = Proc[S]
      val g1 = Grapheme.Modifiable[S]
      //         p1.scans.add( "freq" ).source_=( Some( Scan.Link.Grapheme( g1 )))
      g1.add(7000L -> curve(441.0))
      val pt1 = pg.add(Span(-1000L, 10000L), p1)
      obs.assertEquals(
        Advance(t, time = 0L, isSeek = false, isPlaying = false, added = Vec(pt1))
      )
      obs.clear()

      t.play()
      obs.assertEquals(
        Transport.Play(t, 0L)
      )
      obs.clear()

      //println( "PROC " + p1 + " WITH GRAPHEMES " + p1.graphemes + " AND SCANS " + p1.scans )
      //println( "GRAPHEME " + g1 )

      t.elapse(0.1) // t now at 1000 frames
      val scan    = p1.scans.add("freq")
      val source  = Scan.Link.Grapheme(g1)
      scan.addSource(source)
      // note: there will be separate Advance messages because there is no way to bundle them if they
      // originate from distinct actions (scans.add versus scan.source_=)

      obs.assertEquals(
        Advance(t, time = 1000L, isSeek = false, isPlaying = true, changes =
          Vec(pt1 -> ProcChanged[S](
            Proc.AssociationAdded(Proc.ScanKey("freq"))))),
        Advance(t, time = 1000L, isSeek = false, isPlaying = true, changes =
          Vec(pt1 -> ProcChanged[S](
            Proc.ScanChange("freq", scan, Vec(Scan.SourceAdded(source)))
          ))
        )
      )
      obs.clear()

      g1.add(6000L -> curve(882.0))
      obs.assertEmpty()

      t.elapse(0.1) // t now at 2000 frames
      val a0 = Advance(t, time = 2000L, isSeek = false, isPlaying = true)
      p1.scans.add("egal")
      obs.assertEquals(
        a0.copy(changes = Vec(pt1 -> ProcChanged[S](
          Proc.AssociationAdded(Proc.ScanKey("egal")))))
      )
      obs.clear()

      // p1.graphemes.add( "graph", g1 )
      // obs.assertEquals(
      //    a0.copy( changes = Vec( pt1 -> ProcChanged(
      //       Proc.AssociationAdded( Proc.GraphemeKey( "graph" )))))
      // )
      // obs.clear()

      p1.scans.remove( "egal" )
      obs.assertEquals(
        a0.copy(changes = Vec(pt1 -> ProcChanged[S](
          Proc.AssociationRemoved(Proc.ScanKey("egal")))))
      )
      obs.clear()

      // since g1 is part of p1.graphemes, first of all there should be a ProcChanged with underlying
      // GraphemeChange. secondly, because it is connected to the freq-scan and overlaps the current time,
      // there should be a GraphemesChanged as well
      val elem: BiExpr[S, Grapheme.Value] = 1000L -> curve(441.0)
      g1.add(elem)
      val segm = Segment.Curve(Span(1000L, 6000L), Vec((441.0, 882.0, linear)))
      obs.assertEquals(
        a0.copy(changes = Vec(
          // pt1 -> ProcChanged(
          //    Proc.GraphemeChange( "graph", Grapheme.Update( g1, Vec( segm )))
          // ),
          pt1 -> GraphemesChanged[S](
            Map("freq" -> Vec(segm))
          )
        ))
      )
      obs.clear()

      // p1.graphemes.remove( "graph" )
      // obs.assertEquals(
      //    a0.copy( changes = Vec( pt1 -> ProcChanged(
      //       Proc.AssociationRemoved( Proc.GraphemeKey( "graph" )))))
      // )
      // obs.clear()
    }
   }
}
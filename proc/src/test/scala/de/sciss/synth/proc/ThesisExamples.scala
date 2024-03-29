package de.sciss
package synth
package proc

import de.sciss.span.Span
import de.sciss.lucre.synth.{InMemory, Sys}

object ThesisExamples extends App {
  playScans()

  import ugen._

  def sg = SynthGraph {
    val sig   = PinkNoise.ar
    val bus   = graph.attribute(ProcKeys.attrBus ).ir(0)
    val mute  = graph.attribute(ProcKeys.attrMute).ir(0)
    val env   = graph.FadeInOut(ProcKeys.attrFadeIn, ProcKeys.attrFadeOut).ar
    val amp   = env * (1 - mute)
    Out.ar(bus, sig * amp)
  }

  def configure[S <: Sys[S]](proc: Proc[S])(implicit tx: S#Tx): Unit = {
    val imp = ExprImplicits[S]
    import imp._

    val bus       = lucre.expr.Int .newVar[S](    0)
    val fadeInLen = lucre.expr.Long.newVar[S](44100)
    val fadeIn    = FadeSpec.Elem(fadeInLen, Curve.sine, 0.0)
    proc.attributes.put("bus"    , Attr.Int     (bus   ))
    proc.attributes.put("mute"   , Attr.Boolean (false ))
    proc.attributes.put("fade-in", Attr.FadeSpec(fadeIn))
    proc.graph()  = sg

    bus()       = 1       // adjust bus
    fadeInLen() = 22050   // adjust fade-in duration
  }

  import ugen._

  def scans[S <: Sys[S]]()(implicit tx: S#Tx): (Proc[S], Proc[S]) = {
    val imp = ExprImplicits[S]
    import imp._

    val gMute     = Grapheme.Modifiable[S]
    gMute.add(    0L -> Grapheme.Value.Curve(0.0 -> Curve.step))
    gMute.add(22050L -> Grapheme.Value.Curve(1.0 -> Curve.step))
    gMute.add(44100L -> Grapheme.Value.Curve(0.0 -> Curve.step))

    val p1        = Proc[S]
    val p2        = Proc[S]

    val sMute     = p1.scans.add("mute")
    sMute addSource Scan.Link.Grapheme(gMute)
    val sOut      = p1.scans.add("out")
    val sIn       = p2.scans.add("in")
    sOut addSink sIn

    p1.graph() = SynthGraph {
      val mute           = graph.scan.In("mute")
      graph.scan.Out("out", PinkNoise.ar * (1 - mute))
    }

    p2.graph() = SynthGraph {
      val sig = graph.scan.In("in")
      Out.ar(0, Pan2.ar(sig, LFSaw.ar(1)))
    }

    (p1, p2)
  }

  def playScans(): Unit = {
    type S            = InMemory
    implicit val sys  = InMemory()

    val imp = ExprImplicits[S]
    import imp._

    val transp = sys.step { implicit tx =>
      val (p1, p2)  = scans[S]()
      val span      = Span(0L, 66150L)
      val group     = ProcGroup.Modifiable[S]
      group.add(span, p1)
      group.add(span, p2)

      Transport[S, S](group)
    }

    sys.step { implicit tx =>
      val aural = AuralSystem.start()
      AuralPresentation.run(transp, aural)

      aural.whenStarted { _ =>
        sys.step { implicit tx =>
          transp.play()
        }
      }
    }
  }
}
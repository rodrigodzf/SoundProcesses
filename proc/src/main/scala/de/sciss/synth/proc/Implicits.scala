/*
 *  Implicits.scala
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

package de.sciss.synth.proc

import de.sciss.lucre
import de.sciss.lucre.synth.Sys

object Implicits {
  implicit final class RichProc[S <: Sys[S]](val proc: Proc[S]) extends AnyVal {
    def name(implicit tx: S#Tx): String =
      proc.attributes[Attr.String](ProcKeys.attrName).fold("<unnamed>")(_.value)

    def name_=(value: String)(implicit tx: S#Tx): Unit = {
      proc.attributes.put(ProcKeys.attrName, Attr.String(lucre.expr.String.newConst(value)))
    }
  }
}
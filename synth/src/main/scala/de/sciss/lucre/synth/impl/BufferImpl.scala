/*
 *  BufferImpl.scala
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

package de.sciss.lucre.synth
package impl

import de.sciss.synth.{Buffer => SBuffer, FillRange}
import de.sciss.synth.io.{SampleFormat, AudioFileType}

final case class BufferImpl(server: Server, peer: SBuffer)
                           (val numFrames: Int, val numChannels: Int, closeOnDisposal: Boolean)
  extends ResourceImpl with Buffer.Modifiable {
  //   val isAlive:    State = State(                this, "isAlive", init = true )
  //   val isOnline:   State = State.and( isAlive )( this, "isOnline", init = false )
  //   val hasContent: State = State(                this, "hasContent", init = false )

  def id: Int = peer.id

  def alloc()(implicit tx: Txn): Unit = {
    requireOffline()
    require(numFrames >= 0 && numChannels >= 0, s"numFrames ($numFrames) and numChannels ($numChannels) must be >= 0")
    tx.addMessage(this, peer.allocMsg(numFrames = numFrames, numChannels = numChannels), audible = false)
    setOnline(value = true)
  }

  /** Allocates and reads the buffer content once (closes the file). */
  def allocRead(path: String, startFrame: Long)(implicit tx: Txn): Unit = {
    requireOffline()
    require(startFrame <= 0x7FFFFFFFL, s"Cannot encode start frame >32 bit ($startFrame)")
    require(numFrames >= 0, s"numFrames ($numFrames) must be >= 0")
    val frameI = startFrame.toInt
    tx.addMessage(this, peer.allocReadMsg(path, startFrame = frameI, numFrames = numFrames), audible = false)
    setOnline(value = true)
  }

  /** Opens a file to write to in streaming mode (leaving it open), as useable for DiskOut. */
  def record(path: String, fileType: AudioFileType = AudioFileType.AIFF,
             sampleFormat: SampleFormat = SampleFormat.Float)(implicit tx: Txn): Unit = {
    write(path, fileType, sampleFormat, numFrames = 0, leaveOpen = true)
  }

  /** Writes the buffer contents once (closes the target file). */
  def write(path: String, fileType: AudioFileType, sampleFormat: SampleFormat, numFrames: Int = -1,
            startFrame: Int = 0, leaveOpen: Boolean = false)(implicit tx: Txn): Unit = {
    requireOnline()
    require(leaveOpen == closeOnDisposal, s"leaveOpen is $leaveOpen but should be $closeOnDisposal")
    require(startFrame >= 0, s"startFrame ($startFrame) must be >= 0")
    tx.addMessage(this, peer.writeMsg(path, fileType, sampleFormat, numFrames = numFrames, startFrame = startFrame,
      leaveOpen = leaveOpen), audible = false)
  }

  /** Cues the input sound file for streaming via DiskIn (leaves the file open). */
  def cue(path: String, fileStartFrame: Long = 0L, numFrames: Int = -1)(implicit tx: Txn): Unit = {
    requireOnline()
    require(fileStartFrame <= 0x7FFFFFFFL, s"Cannot encode start frame >32 bit ($fileStartFrame)")
    require(numFrames >= -1 /* && bufStartFrame >= 0 */, s"numFrames ($numFrames) must be >= -1")
    val frameI = fileStartFrame.toInt
    tx.addMessage(this, peer.readMsg(path, fileStartFrame = frameI, numFrames = numFrames, leaveOpen = true),
      audible = false)
  }

  /** Reads the buffer contents from a file (closes the file). */
  def read(path: String, fileStartFrame: Long = 0L, numFrames: Int = -1, bufStartFrame: Int = 0)(implicit tx: Txn): Unit = {
    requireOnline()
    require(fileStartFrame <= 0x7FFFFFFFL, s"Cannot encode start frame >32 bit ($fileStartFrame)")
    require(numFrames >= 0 && bufStartFrame >= 0, s"numFrames ($numFrames) and bufStartFrame ($bufStartFrame) must be >= 0")
    val frameI = fileStartFrame.toInt
    tx.addMessage(this, peer.readMsg(path, fileStartFrame = frameI, bufStartFrame = bufStartFrame,
      numFrames = numFrames, leaveOpen = false), audible = false)
  }

  /** Clears the buffer contents. */
  def zero()(implicit tx: Txn): Unit = {
    requireOnline()
    tx.addMessage(this, peer.zeroMsg, audible = false)
  }

  /** Clears the buffer contents. */
  def fill(index: Int, num: Int, value: Float)(implicit tx: Txn): Unit = {
    val size = numFrames * numChannels
    if (index < 0         ) throw new IllegalArgumentException (s"index ($index) must be >= 0")
    if (num   < 0         ) throw new IllegalArgumentException (s"num ($num) must be >= 0")
    if (index + num > size) throw new IndexOutOfBoundsException(s"index ($index) + num ($num) > size ($size)")
    requireOnline()
    tx.addMessage(this, peer.fillMsg(FillRange(index = index, num = num, value = value)), audible = false) // XXX TODO audible?
  }

  def dispose()(implicit tx: Txn): Unit = {
    requireOnline()
    if (closeOnDisposal) {
      tx.addMessage(this, peer.closeMsg, audible = false)
    }
    tx.addMessage(this, peer.freeMsg(release = false), audible = false)
    server.freeBuffer(peer.id)
    setOnline(value = false)
  }
}
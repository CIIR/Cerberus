package cerberus

/**
 * There are only a handful of Pull nodes (Source) in the execution graph, but they hand off to a Push node (Node)
 */

import cerberus.io._
import scala.collection.GenTraversableOnce

/**
 * This class is responsible for execution of a source and a graph
 */
case class Executor[T <:Encodable](val src: Source[T], val pushTo: Node[T]) {
  def run(cfg: RuntimeConfig) {
    // init runtime configuration of the graph
    pushTo.conf(cfg)
    
    // process all the data coming from the source
    val iter: Reader[T] = src.getReader()
    iter.foreach(pushTo.process(_))
    iter.close()

    // flush out any buffered steps
    pushTo.flush()
  }
}

/**
 * Generic interface to a Source
 * A Reader[T] is nothing more than a closeable Iterator[T]
 */
trait Source[T <:Encodable] {
  def getReader(): Reader[T]
}

class FileSource[T <:Encodable](val path: String, val encoding: Protocol) extends Source[T] {
  def getReader(): Reader[T] =
    encoding.getReader[T](path)
}

class MergedFileSource[T <:Encodable](val paths: Set[String], val encoding: Protocol) extends Source[T] {
  assert(paths.size != 0)
  def getReader() = new Reader[T] {
    val orderedFiles = paths.toIndexedSeq
    var fp = encoding.getReader[T](orderedFiles(0))
    var i = 1

    def hasNext: Boolean = {
      // if this one is done and there's another
      if(!fp.hasNext && i < paths.size) {
        fp.close() // close the current
        fp = encoding.getReader[T](orderedFiles(i)) // open the next
        i+=1
      }
      fp.hasNext
    }
    def next(): T = fp.next()
    def close() {
      fp.close()
    }
  }
}

class SortedMergeSource[T <:Encodable](val paths: Set[String], val encoding: Protocol)(implicit ord: math.Ordering[T]) extends Source[T] {
  assert(paths.size != 0)
  def getReader() = new Reader[T] {
    // keep the files around for closing
    val files = paths.map(encoding.getReader[T](_))
    // grab buffered iterators to perform the sorted merge
    val iters = files.map(_.buffered)

    def hasNext: Boolean = iters.exists(_.hasNext)
    def next(): T = {
      // return the minimum item
      iters.filter(_.hasNext).minBy(_.head).next()
    }
    def close() {
      files.foreach(_.close())
    }
  }
}

class TraversableSource[T <:Encodable](val seq: Seq[T]) extends Source[T] {
  def getReader ={
    val iter = seq.toIterator
    new Reader[T] {
      def hasNext = iter.hasNext
      def next() = iter.next()
      def close() { }
    }
  }
}


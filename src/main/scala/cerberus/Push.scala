/**
 * Push node execution
 */

package cerberus

import cerberus.io._

// TODO, make this configuration better
class RuntimeConfig(val jobUniq: String) {
  // for helping make files 
  var uid = 0
  def nextFileName() = {
    uid += 1
    jobUniq + "/file"+uid
  }
  def nextScratchFileName() = {
    uid += 1
    "/tmp/"+jobUniq+"/scratch"+uid
  }
}

trait Node[T <:Encodable] {
  def conf(cfg: RuntimeConfig): Unit
  def process(next: T): Unit
  def flush(): Unit
}

class FileNode[T <:Encodable](val path: String, val encoding: Protocol) extends Node[T] {
  val output = encoding.getWriter[T]
  def conf(cfg: RuntimeConfig) { }
  def process(next: T) {
    output.put(next)
  }
  def flush() {
    output.flush()
    output.close()
  }
}

class MappedNode[A <:Encodable, B <:Encodable](val child: Node[B], oper: A=>B) extends Node[A] {
  def conf(cfg: RuntimeConfig) = child.conf(cfg)
  def process(next: A) = child.process(oper(next))
  def flush() = child.flush()
}

class FilteredNode[T <:Encodable](val child: Node[T], oper: T=>Boolean) extends Node[T] {
  def conf(cfg: RuntimeConfig) = child.conf(cfg)
  def process(next: A) = if(oper(next)) { child.process(next) }
  def flush() = child.flush()
}

class MultiNode[T <:Encodable](val children: Seq[Node[T]]) extends Node[T] {
  def conf(cfg: RuntimeConfig) = children.foreach(_.conf(cfg))
  def process(next: T) = children.foreach(_.process(next))
  def flush() = children.foreach(_.flush())
}

class SortedNode[T <:Encodable](val child: Node[T], val encoding: Protocol, val bufferSize: Int=8192)(implicit ord: math.Ordering[T]) {
  // keep up to bufferSize elements in memory at once
  val buffer = new Array[T](bufferSize)
  // fill up diskBuffers with the list of files to merge later
  var diskBuffers = Set[String]()
  // how many are in the buffer
  var count = 0 

  // save this locally
  var rcfg: RuntimeConfig = null
  def conf(cfg: RuntimeConfig) {
    rcfg = cfg
    child.conf(cfg)
  }

  def pushBufferToDisk() {
    val tmpName = rcfg.nextScratchFileName()
    
    // put up to count things
    val fp = encoding.getWriter[T](tmpName)
    var idx =0
    while(idx < count) {
      fp.put(buffer(idx))
      idx += 1
    }
    fp.close()
    
    // keep this buffer
    diskBuffers += tmpName
    count = 0
  }

  def deleteBuffers() {
    //TODO
    //diskBuffers.foreach(path => (new java.io.File(path)).delete() )
  }

  def process(next: T) {
    if(count == bufferSize) {
      pushBufferToDisk()
    }
    buffer(count) = next
    count += 1
  }

  def flush() {
    pushBufferToDisk()
    
    // turn each diskBuffer into a sorted, BufferedIterator[T]
    val pullStreams = diskBuffers.foreach(encoding.getReader).buffered

    while(pullStreams.exists(_.hasNext)) {
      // find the minimum of all the flows and return that
      val minIter = pullStreams.filter(_.hasNext).minBy(_.head)
      child.process(minIter.next)
    }

    deleteBuffers()
    child.flush()
  }
}



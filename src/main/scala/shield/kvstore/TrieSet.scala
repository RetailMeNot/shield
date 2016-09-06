package shield.kvstore

import scala.collection.concurrent
import scala.collection.generic.{GenericSetTemplate, MutableSetFactory}

object TrieSet extends MutableSetFactory[TrieSet] {
  override def empty[T]: TrieSet[T] = new TrieSet[T]
}
class TrieSet[T]
extends scala.collection.mutable.Set[T]
  with GenericSetTemplate[T, TrieSet]
  with scala.collection.mutable.SetLike[T, TrieSet[T]]
{
  private val map = concurrent.TrieMap[T, Null]()

  override def empty : TrieSet[T] = new TrieSet[T]

  override def companion = TrieSet

  override def +=(elem: T): TrieSet.this.type = {
    map.put(elem, null)
    this
  }

  override def -=(elem: T): TrieSet.this.type = {
    map.remove(elem)
    this
  }

  override def contains(elem: T): Boolean = {
    map.contains(elem)
  }

  override def iterator: Iterator[T] = {
    map.keysIterator
  }

  override def foreach[U](f: (T) => U) : Unit = {
    map.keys.foreach(f)
  }

  override def size : Int = {
    map.size
  }
}

package com.twitter.finagle.stats

import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.collection.{SortedMap, mutable}

object InMemoryStatsReceiver {
  private[stats] implicit class RichMap[K, V](val self: mutable.Map[K, V]) {
    def mapKeys[T](func: K => T): mutable.Map[T, V] = {
      for ((k, v) <- self) yield {
        func(k) -> v
      }
    }

    def toSortedMap(implicit ordering: Ordering[K]): SortedMap[K, V] = {
      SortedMap[K, V]() ++ self
    }
  }

  private[stats] def statValuesToStr(values: Seq[Float]): String = {
    if (values.length <= MaxStatsValues) {
      values.mkString("[", ",", "]")
    } else {
      val numOmitted = values.length - MaxStatsValues
      values.take(MaxStatsValues).mkString("[", ",", OmittedValuesStr.format(numOmitted))
    }
  }

  private[stats] val OmittedValuesStr = "... (omitted %s value(s))]"
  private[stats] val MaxStatsValues = 3
}

/**
 * An in-memory implementation of [[StatsReceiver]], which is mostly used for testing.
 *
 * Note that an [[InMemoryStatsReceiver]] does not conflate `Seq("a", "b")` and `Seq("a/b")`
 * names no matter how they look when printed.
 *
 * {{{
 * val isr = new InMemoryStatsReceiver
 * isr.counter("a", "b", "foo")
 * isr.counter("a/b", "bar")
 *
 * isr.print(Console.out) // will print two lines "a/b/foo 0" and "a/b/bar 0"
 *
 * assert(isr.counters(Seq("a", "b", "foo") == 0)) // ok
 * assert(isr.counters(Seq("a", "b", "bar") == 0)) // fail
 * }}}
 *
 * @param maxStats the maximum number of stats, zero or negative value means unlimited (default is unlimited)
 **/
class InMemoryStatsReceiver(maxStats: Int) extends StatsReceiver with WithHistogramDetails {
  import InMemoryStatsReceiver._

  def this() = this(0)

  def repr: InMemoryStatsReceiver = this

  val verbosity: mutable.Map[Seq[String], Verbosity] =
    new ConcurrentHashMap[Seq[String], Verbosity]().asScala

  val counters: mutable.Map[Seq[String], Long] =
    new ConcurrentHashMap[Seq[String], Long]().asScala

  val stats: mutable.Map[Seq[String], Seq[Float]] =
    new ConcurrentHashMap[Seq[String], Seq[Float]]().asScala

  val gauges: mutable.Map[Seq[String], () => Float] =
    new ConcurrentHashMap[Seq[String], () => Float]().asScala

  override def counter(name: String*): ReadableCounter =
    counter(CounterSchema(new MetricBuilder(name = name, statsReceiver = this)))

  /**
   * Creates a [[ReadableCounter]] of the given `name`.
   */
  def counter(schema: CounterSchema): ReadableCounter =
    new ReadableCounter {

      verbosity += schema.metricBuilder.name -> schema.metricBuilder.verbosity

      // eagerly initialize
      counters.synchronized {
        if (!counters.contains(schema.metricBuilder.name)) {
          counters(schema.metricBuilder.name) = 0
        }
      }

      def incr(delta: Long): Unit = counters.synchronized {
        val oldValue = apply()
        counters(schema.metricBuilder.name) = oldValue + delta
      }

      def apply(): Long = counters.getOrElse(schema.metricBuilder.name, 0)

      override def toString: String =
        s"Counter(${schema.metricBuilder.name.mkString("/")}=${apply()})"
    }

  override def stat(name: String*): ReadableStat =
    stat(HistogramSchema(new MetricBuilder(name = name, statsReceiver = this)))

  /**
   * Creates a [[ReadableStat]] of the given `name`.
   */
  def stat(schema: HistogramSchema): ReadableStat =
    new ReadableStat {

      verbosity += schema.metricBuilder.name -> schema.metricBuilder.verbosity

      // eagerly initialize
      stats.synchronized {
        if (!stats.contains(schema.metricBuilder.name)) {
          stats(schema.metricBuilder.name) = Nil
        }
      }

      def add(value: Float): Unit = stats.synchronized {
        val oldValue = apply()
        stats(schema.metricBuilder.name) = oldValue.takeRight(maxStats) :+ value
      }
      def apply(): Seq[Float] = stats.getOrElse(schema.metricBuilder.name, Seq.empty)

      override def toString: String = {
        val vals = apply()
        s"Stat(${schema.metricBuilder.name.mkString("/")}=${statValuesToStr(vals)})"
      }
    }

  /**
   * Creates a [[Gauge]] of the given `name`.
   */
  def addGauge(schema: GaugeSchema)(f: => Float) =
    new Gauge {

      gauges += schema.metricBuilder.name -> (() => f)
      verbosity += schema.metricBuilder.name -> schema.metricBuilder.verbosity

      def remove(): Unit = {
        gauges -= schema.metricBuilder.name
      }

      override def toString: String = {
        // avoid holding a reference to `f`
        val current = gauges.get(schema.metricBuilder.name) match {
          case Some(fn) => fn()
          case None => -0.0f
        }
        s"Gauge(${schema.metricBuilder.name.mkString("/")}=$current)"
      }
    }

  override def toString: String = "InMemoryStatsReceiver"

  /**
   * Dumps this in-memory stats receiver to the given [[PrintStream]].
   * @param p the [[PrintStream]] to which to write in-memory values.
   */
  def print(p: PrintStream): Unit = {
    print(p, includeHeaders = false)
  }

  /**
   * Dumps this in-memory stats receiver to the given [[PrintStream]].
   * @param p the [[PrintStream]] to which to write in-memory values.
   * @param includeHeaders optionally include printing underlines headers for the different types
   *                       of stats printed, e.g., "Counters:", "Gauges:", "Stats;"
   */
  def print(p: PrintStream, includeHeaders: Boolean): Unit = {
    val sortedCounters = counters.mapKeys(_.mkString("/")).toSortedMap
    val sortedGauges = gauges.mapKeys(_.mkString("/")).toSortedMap
    val sortedStats = stats.mapKeys(_.mkString("/")).toSortedMap

    if (includeHeaders && sortedCounters.nonEmpty) {
      p.println("Counters:")
      p.println("---------")
    }
    for ((k, v) <- sortedCounters)
      p.print(f"$k%s $v%d\n")
    if (includeHeaders && sortedGauges.nonEmpty) {
      p.println("\nGauges:")
      p.println("-------")
    }
    for ((k, g) <- sortedGauges)
      p.print(f"$k%s ${g()}%f\n")
    if (includeHeaders && sortedStats.nonEmpty) {
      p.println("\nStats:")
      p.println("------")
    }
    for ((k, s) <- sortedStats if s.nonEmpty) {
      p.print(f"$k%s ${s.sum / s.size}%f ${statValuesToStr(s)}\n")
    }
  }

  /**
   * Clears all registered counters, gauges and stats.
   * @note this is not atomic. If new metrics are added while this method is executing, those metrics may remain.
   */
  def clear(): Unit = {
    counters.clear()
    stats.clear()
    gauges.clear()
  }

  private[this] def toHistogramDetail(addedValues: Seq[Float]): HistogramDetail = {
    def nearestPosInt(f: Float): Int = {
      if (f < 0) 0
      else if (f >= Int.MaxValue) Int.MaxValue - 1
      else f.toInt
    }

    new HistogramDetail {
      def counts: Seq[BucketAndCount] = {
        addedValues
          .groupBy(nearestPosInt)
          .map { case (k, vs) => BucketAndCount(k, k + 1, vs.size) }
          .toSeq
          .sortBy(_.lowerLimit)
      }
    }
  }

  def histogramDetails: Map[String, HistogramDetail] = stats.toMap.map {
    case (k, v) => (k.mkString("/"), toHistogramDetail(v))
  }
}

/**
 * A variation of [[Counter]] that also supports reading of the current value via the `apply` method.
 */
trait ReadableCounter extends Counter {
  def apply(): Long
}

/**
 * A variation of [[Stat]] that also supports reading of the current time series via the `apply` method.
 */
trait ReadableStat extends Stat {
  def apply(): Seq[Float]
}

package retry

import java.util.Random

import org.scalatest.{ AsyncFunSpec, BeforeAndAfterAll }
import odelay.Timer
import scala.concurrent.{ Future }
import scala.concurrent.duration._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicLong }

abstract class PolicySpec extends AsyncFunSpec with BeforeAndAfterAll {

    // needed so we do not get a scalatest EC error
  implicit override def executionContext =
    scala.concurrent.ExecutionContext.Implicits.global

  implicit val timer: Timer
  val random = new Random()
  val randomSource = Jitter.randomSource(random)

  override def afterAll() {
    timer.stop()
  }

  def forwardCountingFutureStream(value: Int = 0): Stream[Future[Int]] =
    Future(value) #:: forwardCountingFutureStream(value + 1)

  def backwardCountingFutureStream(value: Int): Stream[Future[Int]] =
    if (value < 0) Stream.empty
    else Future(value) #:: backwardCountingFutureStream(value - 1)

  def time[T](f: => T): Duration = {
    val before = System.currentTimeMillis
    f
    Duration(System.currentTimeMillis - before, MILLISECONDS)
  }

  describe("retry.Directly") {
    it ("should retry a future for a specified number of times") {
      implicit val success = Success[Int](_ == 3)
      val tries = forwardCountingFutureStream().iterator
      retry.Directly(3)(tries.next).map(result => assert(success.predicate(result) === true))
    }

    it ("should fail when expected") {
      val success = implicitly[Success[Option[Int]]]
      val tries = Future(None: Option[Int])
      retry.Directly(2)(tries).map(result => assert(success.predicate(result) === false))
    }

    it ("should deal with future failures") {
      implicit val success = Success.always
      val policy = retry.Directly(3) // was 3
      val counter = new AtomicInteger(0)
      val future = policy {
        counter.incrementAndGet()
        Future.failed(new RuntimeException("always failing"))
      }
      // expect failure after 1+3 tries
      future.failed.map{t =>
        assert(counter.get() === 4 && t.getMessage === "always failing")
      }
    }

    it ("should accept a future in reduced syntax format") {
      implicit val success = Success.always
      val counter = new AtomicInteger()
      val future = retry.Directly(1) {
        counter.incrementAndGet()
        Future.failed(new RuntimeException("always failing"))
      }
      future.failed.map(t => assert(counter.get() === 2 && t.getMessage === "always failing"))
    }

    it ("should retry futures passed by-name instead of caching results") {
      implicit val success = Success.always
      val counter = new AtomicInteger()
      val future = retry.Directly(1) {
        counter.getAndIncrement() match {
          case 1 => Future.successful("yay!")
          case _ => Future.failed(new RuntimeException("failed"))
        }
      }
      future.map(result => assert(counter.get() === 2 && result === "yay!"))
    }

    it ("should repeat on not expected value until success") {
      implicit val success = Success[Boolean](identity)
      class MyException extends RuntimeException
      val retried = new AtomicInteger()
      val retriedUntilSuccess = 10000
      def run() = if (retried.get() < retriedUntilSuccess) {
        retried.incrementAndGet()
        Future(false)
      }  else {
        Future(true)
      }
      val policy = Directly.forever
      policy(run).map {result =>
        assert(result === true)
        assert(retried.get() == 10000)
      }
    }
  }

  describe("retry.Pause") {
    it ("should pause in between retries") {
      implicit val success = Success[Int](_ == 3)
      val tries = forwardCountingFutureStream().iterator
      val policy = retry.Pause(3, 30.millis)
      val marker_base = System.currentTimeMillis
      val marker = new AtomicLong(0)

      val runF = policy({marker.set(System.currentTimeMillis); tries.next})
      runF.map{ result =>
        val delta = marker.get() - marker_base
        assert(success.predicate(result) == true &&
          delta >= 90 && delta <= 200) // was 110, depends on how hot runtime is
      }
    }

    it ("should repeat on unexpected value with pause until success") {
      implicit val success = Success[Boolean](identity)
      class MyException extends RuntimeException
      val retried = new AtomicInteger()
      val retriedUntilSuccess = 1000
      def run() = if (retried.get() < retriedUntilSuccess) {
        retried.incrementAndGet()
        Future(false)
      }  else {
        Future(true)
      }
      val policy = Pause.forever(1.millis)
      policy(run).map {result =>
        assert(result === true)
        assert(retried.get() == 1000)
      }
    }
  }

  describe("retry.Backoff") {
    it ("should pause with multiplier between retries") {
      implicit val success = Success[Int](_ == 2)
      val tries = forwardCountingFutureStream().iterator
      val policy = retry.Backoff(2, 30.millis)
      val marker_base = System.currentTimeMillis
      val marker = new AtomicLong(0)
      val runF = policy({marker.set(System.currentTimeMillis); tries.next})
      runF.map{result =>
        val delta = marker.get() - marker_base
        assert(success.predicate(result)===true &&
          delta >= 90 && delta <= 200) // was 110
      }
    }

    it ("should repeat on unexpected value with backoff until success") {
      implicit val success = Success[Boolean](identity)
      class MyException extends RuntimeException
      val retried = new AtomicInteger()
      val retriedUntilSuccess = 5
      def run() = if (retried.get() < retriedUntilSuccess) {
        retried.incrementAndGet()
        Future(false)
      }  else {
        Future(true)
      }
      val policy = Backoff.forever(1.millis)
      policy(run).map {result =>
        assert(result === true)
        assert(retried.get() == 5)
      }
    }
  }


  def testJitterBackoff(name: String, algoCreator: FiniteDuration => Jitter): Unit = {
    describe(s"retry.JitterBackoff.$name") {

      it ("should retry a future for a specified number of times") {
        implicit val success = Success[Int](_ == 3)
        implicit val algo = algoCreator(10.millis)
        val tries = forwardCountingFutureStream().iterator
        val policy = retry.JitterBackoff(3, 1.milli)
        policy(tries.next).map(result => assert(success.predicate(result) === true))
      }

      it ("should fail when expected") {
        implicit val algo = algoCreator(10.millis)
        val success = implicitly[Success[Option[Int]]]
        val tries = Future(None: Option[Int])
        val policy = retry.JitterBackoff(3, 1.milli)
        policy({ () => tries }).map(result => assert(success.predicate(result) === false))
      }

      it ("should deal with future failures") {
        implicit val success = Success.always
        implicit val algo = algoCreator(10.millis)
        val policy = retry.JitterBackoff(3, 5.millis)
        val counter = new AtomicInteger()
        val future = policy { () =>
          counter.incrementAndGet()
          Future.failed(new RuntimeException("always failing"))
        }
        future.failed.map(t => assert(counter.get() === 4 && t.getMessage === "always failing"))
      }

      it ("should retry futures passed by-name instead of caching results") {
        implicit val success = Success.always
        implicit val algo = algoCreator(10.millis)
        val counter = new AtomicInteger()
        val policy = retry.JitterBackoff(1, 1.milli)
        val future = policy {
          counter.getAndIncrement() match {
            case 1 => Future.successful("yay!")
            case _ => Future.failed(new RuntimeException("failed"))
          }
        }
        future.map(result => assert(counter.get() == 2 && result === "yay!"))
      }

      it ("should pause with multiplier and jitter between retries") {
        implicit val success = Success[Int](_ == 2)
        implicit val algo = algoCreator(1000.millis)
        val tries = forwardCountingFutureStream().iterator
        val policy = retry.JitterBackoff(5, 50.millis)
        val marker_base = System.currentTimeMillis
        val marker = new AtomicLong(0)

        policy({marker.set(System.currentTimeMillis); tries.next}).map{ result =>
          val delta = marker.get() - marker_base
          assert(success.predicate(result) === true &&
            delta >= 0 && delta <= 2000)
        }
      }

      it ("should also work when invoked as forever") {
        implicit val success = Success[Int](_ == 5)
        implicit val algo = algoCreator(1000.millis)
        val tries = forwardCountingFutureStream().iterator
        val policy = retry.JitterBackoff.forever(50.millis)
        val marker_base = System.currentTimeMillis
        val marker = new AtomicLong(0)

        policy({marker.set(System.currentTimeMillis); tries.next}).map{ result =>
          val delta = marker.get() - marker_base
          assert(success.predicate(result) === true &&
            delta >= 0 && delta <= 2000)
        }
      }

      it ("should repeat on unexpected value with jitter backoff until success") {
        implicit val success = Success[Boolean](identity)
        class MyException extends RuntimeException
        val retried = new AtomicInteger()
        val retriedUntilSuccess = 10
        def run() = if (retried.get() < retriedUntilSuccess) {
          retried.incrementAndGet()
          Future(false)
        }  else {
          Future(true)
        }
        val policy = JitterBackoff.forever(1.millis)
        policy(run).map {result =>
          assert(result === true)
          assert(retried.get() == 10)
        }
      }
    }
  }

  testJitterBackoff("none", t => Jitter.none(t))
  testJitterBackoff("full", t => Jitter.full(t, random = randomSource))
  testJitterBackoff("equal", t => Jitter.equal(t, random = randomSource))
  testJitterBackoff("decorrelated", t => Jitter.decorrelated(t, random = randomSource))

  describe("retry.When") {
    it ("should retry conditionally when a condition is met") {
      implicit val success = Success[Int](_ == 2)
      val tries = forwardCountingFutureStream().iterator
      val policy = retry.When {
        // this is very constrived but should serve as an example
        // of matching then dispatching a retry depending on
        // the value of the future when completed
        case 0 => retry.When {
          case 1 => retry.Pause(delay = 2.seconds)
        }
      }
      val future = policy(tries.next)
      future.map(result => assert(success.predicate(result) === true))
    }

    it ("should retry but only when condition is met") {
      implicit val success = Success[Int](_ == 2)
      val tries = forwardCountingFutureStream().iterator
      val policy = retry.When {
        // this cond will never be met because
        // a cond for n == 0 is not defined
        case 1 => retry.Directly()
      }

      val future = policy(tries.next)
      future.map( result => assert(success.predicate(result) === false))
    }

    it ("should handle future failures") {
      implicit val success = Success[Boolean](identity)
      case class RetryAfter(duration: FiniteDuration) extends RuntimeException
      val retried = new AtomicBoolean
      def run() = if (retried.get()) Future(true) else {
        retried.set(true)
        Future.failed(RetryAfter(1.second))
      }      
      val policy = retry.When {
        // lift an exception into a new policy
        case RetryAfter(duration) => Pause(delay = duration)
      }
      policy(run).map(result => assert(result === true))
    }

    it ("should repeat on failure until success") {
      implicit val success = Success[Boolean](identity)
      class MyException extends RuntimeException
      val retried = new AtomicInteger()
      val retriedUntilSuccess = 10000
      def run() = if (retried.get() < retriedUntilSuccess) {
        retried.incrementAndGet()
        Future.failed(new MyException)
      }  else {
        Future(true)
      }
      val policy = retry.When {
        // lift an exception into a new policy
        case _:MyException => Directly.forever
      }
      policy(run).map {result =>
        assert(result === true)
        assert(retried.get() == 10000)
      }
    }

    it ("should repeat on failure with pause until success") {
      implicit val success = Success[Boolean](identity)
      class MyException extends RuntimeException
      val retried = new AtomicInteger()
      val retriedUntilSuccess = 1000
      def run() = if (retried.get() < retriedUntilSuccess) {
        retried.incrementAndGet()
        Future.failed(new MyException)
      }  else {
        Future(true)
      }
      val policy = retry.When {
        // lift an exception into a new policy
        case _:MyException => Pause.forever(1.millis)

      }
      policy(run).map {result =>
        assert(result === true)
        assert(retried.get() == 1000)
      }
    }

    it ("should repeat on failure with backoff until success") {
      implicit val success = Success[Boolean](identity)
      class MyException extends RuntimeException
      val retried = new AtomicInteger()
      val retriedUntilSuccess = 5
      def run() = if (retried.get() < retriedUntilSuccess) {
        retried.incrementAndGet()
        Future.failed(new MyException)
      }  else {
        Future(true)
      }
      val policy = retry.When {
        // lift an exception into a new policy
        case _:MyException => Backoff.forever(1.millis)
      }
      policy(run).map {result =>
        assert(result === true)
        assert(retried.get() == 5)
      }
    }

    it ("should repeat on failure with jitter backoff until success") {
      implicit val success = Success[Boolean](identity)
      class MyException extends RuntimeException
      val retried = new AtomicInteger()
      val retriedUntilSuccess = 10
      def run() = if (retried.get() < retriedUntilSuccess) {
        retried.incrementAndGet()
        Future.failed(new MyException)
      }  else {
        Future(true)
      }
      val policy = retry.When {
        // lift an exception into a new policy
        case _:MyException => JitterBackoff.forever(1.millis)
      }
      policy(run).map {result =>
        assert(result === true)
        assert(retried.get() == 10)
      }
    }
  }
}

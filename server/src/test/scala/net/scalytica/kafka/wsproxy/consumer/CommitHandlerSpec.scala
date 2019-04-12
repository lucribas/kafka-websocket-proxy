package net.scalytica.kafka.wsproxy.consumer

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import akka.kafka.testkit.ConsumerResultFactory
import net.scalytica.kafka.wsproxy.consumer.CommitHandler._
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import net.scalytica.kafka.wsproxy.models.{
  ConsumerKeyValueRecord,
  Formats,
  Offset,
  Partition,
  WsCommit,
  WsMessageId
}
import net.scalytica.test.WSProxySpecLike
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Minute, Span}
import org.scalatest.{BeforeAndAfter, MustMatchers, WordSpec}

import scala.collection.immutable

class CommitHandlerSpec
    extends WordSpec
    with MustMatchers
    with BeforeAndAfter
    with Eventually
    with WSProxySpecLike {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  private[this] def createKeyValueRecord(
      groupId: String,
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long
  ) = {
    ConsumerKeyValueRecord(
      topic = topic,
      partition = partition,
      offset = offset,
      timestamp = timestamp,
      key = OutValueDetails[String](
        value = s"$topic-$partition-$offset",
        format = Some(Formats.StringType)
      ),
      value = OutValueDetails[String](
        value = s"$topic-$partition-$offset",
        format = Some(Formats.StringType)
      ),
      committableOffset = Some(
        ConsumerResultFactory.committableOffset(
          groupId = groupId,
          topic = topic,
          partition = partition,
          offset = offset,
          metadata = null // scalastyle:off
        )
      )
    )
  }

  private[this] def validateStack(
      recs: immutable.Seq[ConsumerKeyValueRecord[String, String]],
      removeIds: Option[Seq[WsMessageId]] = None
  )(
      implicit
      tk: BehaviorTestKit[Protocol],
      inbox: TestInbox[Stack]
  ): TestInbox[Stack] = {
    val fullStack = recs.map { r =>
      r.partition -> List(
        Uncommitted(r.wsProxyMessageId, r.committableOffset.get)
      )
    }.toMap

    val stack = removeIds
      .map { remIds =>
        fullStack.map {
          case (p, m) => p -> m.filterNot(u => remIds.contains(u.wsProxyMsgId))
        }
      }
      .getOrElse(fullStack)

    tk.run(GetStack(inbox.ref))
    inbox.expectMessage(stack)
  }

  "The CommitHandler" should {

    "add a message to the stack" in {
      implicit val testCfg = defaultApplicationTestConfig
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[Stack]()

      val rec =
        createKeyValueRecord("grp1", "topic1", 0, 0, System.currentTimeMillis())

      val stashCommands = Stash(rec)
      // send stash command
      tk.run(stashCommands)
      // ask for updated stack
      validateStack(immutable.Seq(rec))
    }

    "add messages from different partitions to the stack" in {
      implicit val testCfg = defaultApplicationTestConfig
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[Stack]()

      val recs = 0 until 5 map { i =>
        createKeyValueRecord("grp1", "topic1", i, 0, System.currentTimeMillis())
      }
      val stashCommands = recs.map(Stash.apply)
      // send stash commands
      stashCommands.foreach(cmd => tk.run(cmd))
      // ask for updated stack
      validateStack(recs)
    }

    "optionally auto commit and drop messages older than a given age" in {
      pending
    }

    "drop the oldest messages in the stack when max size is reached" in {
      implicit val testCfg = defaultApplicationTestConfig
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[Stack]()

      val stackSize = testCfg.commitHandler.maxStackSize

      val recs =
        0 until 3 flatMap { p =>
          0 until 25 map { i =>
            createKeyValueRecord(
              groupId = "grp1",
              topic = "topic1",
              partition = p,
              offset = i,
              timestamp = System.currentTimeMillis()
            )
          }
        }

      recs.foreach(cmd => tk.run(Stash(cmd)))
      validateStack(recs, Some(recs.take(stackSize).map(_.wsProxyMessageId)))
    }

    "accept a WsCommit command, commit the message and clean up the stack" in {
      implicit val testCfg = defaultApplicationTestConfig
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[Stack]()

      val recs = 0 until 3 map { i =>
        createKeyValueRecord("grp1", "topic1", i, 0, System.currentTimeMillis())
      }

      recs.foreach(cmd => tk.run(Stash(cmd)))
      validateStack(recs)

      tk.run(Commit(WsCommit(recs.head.wsProxyMessageId)))
      validateStack(recs, Some(Seq(recs.head.wsProxyMessageId)))
    }

    "do nothing if the WsCommit message references a non-existing message" in {
      implicit val testCfg = defaultApplicationTestConfig
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[Stack]()

      val recs = 0 until 3 map { i =>
        createKeyValueRecord("grp1", "topic1", i, 0, System.currentTimeMillis())
      }
      val bogusId =
        WsMessageId("topic1", Partition(2), Offset(1), recs(2).timestamp)

      recs.foreach(cmd => tk.run(Stash(cmd)))
      validateStack(recs)

      tk.run(Commit(WsCommit(bogusId)))
      validateStack(recs)
    }

  }

}

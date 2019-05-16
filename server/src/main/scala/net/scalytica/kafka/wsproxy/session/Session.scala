package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.session.Session._

case class Session(
    consumerGroupId: String,
    consumers: Set[ConsumerInstance],
    consumerLimit: Int
) {

  def canOpenSocket: Boolean = consumers.size < consumerLimit

  def hasConsumer(consumerId: String): Boolean =
    consumers.exists(_.id == consumerId)

  def addConsumer(consumerId: String, serverId: Int): SessionOpResult =
    addConsumer(ConsumerInstance(consumerId, serverId))

  def addConsumer(consumerInstance: ConsumerInstance): SessionOpResult =
    if (hasConsumer(consumerInstance.id)) ConsumerExists(this)
    else {
      if (!canOpenSocket) ConsumerLimitReached(this)
      else ConsumerAdded(copy(consumers = consumers + consumerInstance))
    }

  def removeConsumer(consumerId: String): SessionOpResult = {
    if (hasConsumer(consumerId))
      ConsumerRemoved(copy(consumers = consumers.filterNot(_.id == consumerId)))
    else ConsumerDoesNotExists(this)
  }
}

case object Session {

  def apply(consumerGroupId: String, consumerLimit: Int = 2): Session = {
    Session(
      consumerGroupId = consumerGroupId,
      consumers = Set.empty,
      consumerLimit = consumerLimit
    )
  }

  sealed trait SessionOpResult { self =>
    def session: Session

    def asString: String = {
      val tn = self.getClass.getTypeName
      tn.substring(tn.lastIndexOf("$"))
        .stripPrefix("$")
        .foldLeft("") { (str, in) =>
          if (in.isUpper) str + " " + in.toLower
          else str + in
        }
        .trim
    }
  }

  case class SessionInitialised(session: Session)    extends SessionOpResult
  case class ConsumerAdded(session: Session)         extends SessionOpResult
  case class ConsumerRemoved(session: Session)       extends SessionOpResult
  case class ConsumerExists(session: Session)        extends SessionOpResult
  case class ConsumerLimitReached(session: Session)  extends SessionOpResult
  case class ConsumerDoesNotExists(session: Session) extends SessionOpResult
  case class SessionNotFound(groupId: String) extends SessionOpResult {

    override def session = throw new NoSuchElementException(
      "Cannot access session value when it's not found"
    )
  }

}

case class ConsumerInstance(id: String, serverId: Int)
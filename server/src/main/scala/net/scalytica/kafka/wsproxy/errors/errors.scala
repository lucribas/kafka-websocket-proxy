package net.scalytica.kafka.wsproxy.errors

import scala.util.control.NoStackTrace

case class AuthenticationError(message: String, cause: Throwable)
    extends Exception(message, cause)
    with NoStackTrace

case class AuthorisationError(message: String, cause: Throwable)
    extends Exception(message, cause)
    with NoStackTrace

case class TopicNotFoundError(message: String)
    extends Exception(message)
    with NoStackTrace

case class ConfigurationError(message: String) extends RuntimeException(message)

case class IllegalFormatTypeError(message: String) extends Exception(message)

package net.scalytica.kafka

import io.confluent.monitoring.clients.interceptor.{
  MonitoringConsumerInterceptor,
  MonitoringProducerInterceptor
}

package object wsproxy {

  val ProducerInterceptorClass =
    classOf[MonitoringProducerInterceptor[_, _]].getName

  val ConsumerInterceptorClass =
    classOf[MonitoringConsumerInterceptor[_, _]].getName

  implicit class OptionExtensions[T](underlying: Option[T]) {

    def getUnsafe: T = {
      underlying.getOrElse {
        throw new NoSuchElementException(
          "Cannot lift the value from the Option[T] because it was empty."
        )
      }
    }
  }

  implicit class StringExtensions(val underlying: String) extends AnyVal {

    def toSnakeCase: String = {
      underlying.foldLeft("") { (str, c) =>
        if (c.isUpper) {
          if (str.isEmpty) str + c.toLower
          else str + "_" + c.toLower
        } else {
          str + c
        }
      }
    }
  }
}

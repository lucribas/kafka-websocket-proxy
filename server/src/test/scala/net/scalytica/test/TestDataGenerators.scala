package net.scalytica.test

import net.scalytica.kafka.wsproxy.avro.SchemaTypes._
import shapeless.Coproduct

import scala.concurrent.duration._

trait TestDataGenerators extends TestTypes { self =>

  def sessionJson(
      groupId: String,
      consumer: Map[String, Int] = Map.empty
  ): String = {
    val consumersJson = consumer
      .map(c => s"""{ "id": "${c._1}", "serverId": ${c._2} }""")
      .mkString(",")

    s"""{
      |  "consumerGroupId": "$groupId",
      |  "consumers": [$consumersJson],
      |  "consumerLimit": 2
      |}""".stripMargin
  }

  def produceKeyValueJson(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[String] = {
    (1 to num).map { i =>
      val headers =
        if (withHeaders)
          s"""
             |  "headers": [
             |    {
             |      "key": "key$i",
             |      "value": "value$i"
             |    }
             |  ],""".stripMargin
        else ""

      s"""{$headers
         |  "key": {
         |    "value": "foo-$i",
         |    "format": "string"
         |  },
         |  "value": {
         |    "value": "bar-$i",
         |    "format": "string"
         |  }
         |}""".stripMargin
    }
  }

  def produceValueJson(num: Int, withHeaders: Boolean = false): Seq[String] = {
    (1 to num).map { i =>
      val headers =
        if (withHeaders)
          s"""
             |  "headers": [
             |    {
             |      "key": "key$i",
             |      "value": "value$i"
             |    }
             |  ],""".stripMargin
        else ""

      s"""{$headers
         |  "value": {
         |    "value": "bar-$i",
         |    "format": "string"
         |  }
         |}""".stripMargin
    }
  }

  def produceCoproductKeyValueAvro(
      num: Int,
      withHeaders: Boolean = false
  )(
      keyGen: Int => Option[AvroValueTypesCoproduct],
      valGen: Int => AvroValueTypesCoproduct
  ): Seq[AvroProducerRecord] = {
    (1 to num).map { i =>
      val headers =
        if (withHeaders) Option(Seq(KafkaMessageHeader(s"key$i", s"value$i")))
        else None

      val k = keyGen(i)
      val v = valGen(i)

      AvroProducerRecord(
        key = k,
        value = v,
        headers = headers
      )
    }
  }

  def produceKeyValueAvro(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
    val now = java.time.Instant.now().toEpochMilli

    produceCoproductKeyValueAvro(num, withHeaders)(
      keyGen = { i =>
        val key = TestKey(s"foo-$i", now)
        val sk  = TestSerdes.keySerdes.serialize(key)
        Option(Coproduct[AvroValueTypesCoproduct](sk))
      },
      valGen = { i =>
        val value = Album(
          artist = s"artist-$i",
          title = s"title-$i",
          tracks = (1 to 3).map { tnum =>
            Track(
              name = s"track-$tnum",
              duration = (120 seconds).toMillis
            )
          }
        )
        val sv = TestSerdes.valueSerdes.serialize(value)
        Coproduct[AvroValueTypesCoproduct](sv)
      }
    )
  }

  def produceKeyStringValueAvro(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
    produceCoproductKeyValueAvro(num, withHeaders)(
      keyGen = i => Option(Coproduct[AvroValueTypesCoproduct](s"foo-$i")),
      valGen = { i =>
        val value = Album(
          artist = s"artist-$i",
          title = s"title-$i",
          tracks = (1 to 3).map { tnum =>
            Track(
              name = s"track-$tnum",
              duration = (120 seconds).toMillis
            )
          }
        )
        val sv = TestSerdes.valueSerdes.serialize(value)
        Coproduct[AvroValueTypesCoproduct](sv)
      }
    )
  }

  def produceKeyStringValueString(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
    produceCoproductKeyValueAvro(num, withHeaders)(
      keyGen = i => Option(Coproduct[AvroValueTypesCoproduct](s"foo-$i")),
      valGen = i => Coproduct[AvroValueTypesCoproduct](s"artist-$i")
    )
  }

  def produceKeyLongValueString(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
    produceCoproductKeyValueAvro(num, withHeaders)(
      keyGen = i => Option(Coproduct[AvroValueTypesCoproduct](i.toLong)),
      valGen = i => Coproduct[AvroValueTypesCoproduct](s"artist-$i")
    )
  }

  def produceValueAvro(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
    produceCoproductKeyValueAvro(num, withHeaders)(
      keyGen = _ => None,
      valGen = { i =>
        val value = Album(
          artist = s"artist-$i",
          title = s"title-$i",
          tracks = (1 to 3).map { tnum =>
            Track(
              name = s"track-$tnum",
              duration = (120 seconds).toMillis
            )
          }
        )
        val sv = TestSerdes.valueSerdes.serialize(value)
        Coproduct[AvroValueTypesCoproduct](sv)
      }
    )
  }

  def produceAvroWithStringValue(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
    produceCoproductKeyValueAvro(num, withHeaders)(
      keyGen = _ => None,
      valGen = i => Coproduct[AvroValueTypesCoproduct](s"artist-$i")
    )
  }
}

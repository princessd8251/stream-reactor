/*
 * *
 *   * Copyright 2016 Datamountaineer.
 *   *
 *   * Licensed under the Apache License, Version 2.0 (the "License");
 *   * you may not use this file except in compliance with the License.
 *   * You may obtain a copy of the License at
 *   *
 *   * http://www.apache.org/licenses/LICENSE-2.0
 *   *
 *   * Unless required by applicable law or agreed to in writing, software
 *   * distributed under the License is distributed on an "AS IS" BASIS,
 *   * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   * See the License for the specific language governing permissions and
 *   * limitations under the License.
 *   *
 */

package com.datamountaineer.streamreactor.connect.hazelcast.writers

import java.io.ByteArrayOutputStream

import com.datamountaineer.connector.config.FormatType
import com.datamountaineer.streamreactor.connect.errors.ErrorHandler
import com.datamountaineer.streamreactor.connect.hazelcast.HazelCastConnection
import com.datamountaineer.streamreactor.connect.hazelcast.config.{HazelCastSinkSettings, HazelCastStoreAsType, TargetType}
import com.datamountaineer.streamreactor.connect.schemas.ConverterUtil
import com.fasterxml.jackson.databind.JsonNode
import com.hazelcast.core.HazelcastInstance
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.EncoderFactory
import org.apache.avro.reflect.ReflectDatumWriter
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.sink.SinkRecord

import scala.util.Try

/**
  * Created by andrew@datamountaineer.com on 10/08/16. 
  * stream-reactor
  */

object HazelCastWriter {
  def apply(settings: HazelCastSinkSettings): HazelCastWriter = {
    val conn = HazelCastConnection(settings.connConfig)
    new HazelCastWriter(conn, settings)
  }
}

class HazelCastWriter(client: HazelcastInstance, settings: HazelCastSinkSettings) extends StrictLogging with ConverterUtil
  with ErrorHandler {
  logger.info("Initialising Hazelcast writer.")

  //initialize error tracker
  initialize(settings.maxRetries, settings.errorPolicy)
  val writers: Map[String, Writer] = getWriters(settings.topicObject)

  def getWriters(tp: Map[String, HazelCastStoreAsType]): Map[String, Writer] = {
    tp.map({
      case (t, o) =>
        val target = o.targetType match {
          case TargetType.RELIABLE_TOPIC => ReliableTopicWriter(client, o.name)
          case TargetType.RING_BUFFER => RingBufferWriter(client, o.name)
        }
        (t, target)
    })
  }

  /**
    * Write records to Hazelcast
    *
    * @param records The sink records to write.
    **/
  def write(records: Set[SinkRecord]): Unit = {
    if (records.isEmpty) {
      logger.debug("No records received.")
    } else {
      logger.debug(s"Received ${records.size} records.")
      val converted = records.map(r => (r.topic, convert(r)))
      converted.foreach({
        case (topic, payload) =>
          val writer = writers.get(topic)
          val t = Try(writer.foreach(w => w.write(payload)))
          handleTry(t)
      })
      logger.debug(s"Written ${records.size}")
    }
  }

  /**
    * Convert a sink record to avro or Json string bytes
    *
    * @param record The sinkrecord to convert
    * @return an array of bytes
    **/
  private def convert(record: SinkRecord): Array[Byte] = {
    val storedAs = settings.format(record.topic())
    storedAs match {
      case FormatType.AVRO =>
        val avro = toAvro(record)
        serializeAvro(avro, avro.getSchema)
      case FormatType.JSON | FormatType.TEXT => toJson(record).toString.getBytes
      case _ => throw new ConnectException(s"Unknown WITHFORMAT type ${storedAs.toString}")
    }
  }

  /**
    * Serialize an object to a avro encoded byte array
    *
    * @param datum  The object to serialize
    * @param schema The avro schema for the object
    * @return Avro encoded byte array.
    **/
  private def serializeAvro(datum: Object, schema: Schema): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val writer = new ReflectDatumWriter[Object](schema)
    val encoder = EncoderFactory.get().binaryEncoder(out, null)
    out.reset()
    writer.write(datum, encoder)
    encoder.flush()
    out.toByteArray
  }

  /**
    * Convert sink records to json
    *
    * @param record A sink records to convert.
    **/
  private def toJson(record: SinkRecord): JsonNode = {
    val extracted = convert(record, settings.fieldsMap(record.topic()), settings.ignoreFields(record.topic()))
    convertValueToJson(extracted)
  }

  /**
    * Convert sink records to avro
    *
    * @param record A sink records to convert.
    **/
  private def toAvro(record: SinkRecord): GenericRecord = {
    val extracted = convert(record, settings.fieldsMap(record.topic()), settings.ignoreFields(record.topic()))
    convertValueToGenericAvro(extracted)
  }

  def close(): Unit = {
    logger.info("Shutting down Hazelcast client.")
    client.shutdown()
  }

  def flush(): Unit = {}
}

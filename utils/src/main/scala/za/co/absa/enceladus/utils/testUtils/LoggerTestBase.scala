/*
 * Copyright 2018-2019 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.enceladus.utils.testUtils

import java.io.ByteArrayOutputStream

import org.apache.spark.sql.DataFrame
import org.slf4j.{Logger, LoggerFactory}

trait LoggerTestBase {
  import LoggerTestBase.LogLevel._

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  def logLevelToLogFunction(logLevel: LogLevel): String => Unit = {
    logLevel match {
      case TRACE => logger.trace
      case DEBUG => logger.debug
      case INFO  => logger.info
      case WARN  => logger.warn
      case _     => logger.error
    }
  }

  protected def logDataFrameContent(df: DataFrame, logLevel: LogLevel = DEBUG): Unit = {
    val logFnc = logLevelToLogFunction(logLevel)
    logFnc(df.schema.treeString)

    val outCapture = new ByteArrayOutputStream
    Console.withOut(outCapture) {
      df.show(truncate = false)
    }
    val dfData = new String(outCapture.toByteArray).replace("\r\n", "\n")
    logFnc(dfData)
  }
}

object LoggerTestBase {
  object LogLevel extends Enumeration {
    type LogLevel = Value
    val TRACE, DEBUG, INFO, WARN, ERROR = Value
  }
}

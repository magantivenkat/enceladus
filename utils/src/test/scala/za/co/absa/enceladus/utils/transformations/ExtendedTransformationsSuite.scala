/*
 * Copyright 2020 ABSA Group Limited
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

package za.co.absa.enceladus.utils.transformations

import org.apache.commons.io.IOUtils
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.StringType
import org.scalatest.FunSuite
import za.co.absa.enceladus.utils.error.UDFLibrary
import za.co.absa.enceladus.utils.general.JsonUtils
import za.co.absa.enceladus.utils.testUtils.{LoggerTestBase, SparkTestBase}

class ExtendedTransformationsSuite extends FunSuite with SparkTestBase with LoggerTestBase {
  implicit val udfLib: UDFLibrary = new UDFLibrary

  private val nestedTestCaseFactory = new NestedTestCaseFactory()

  test("Test extended array transformations work on root level fields") {
    val expectedSchema = getResourceString("/test_data/nested/nested1Schema.txt")
    val expectedResults = getResourceString("/test_data/nested/nested1Results.json")

    val df = nestedTestCaseFactory.getTestCase

    val dfOut = DeepArrayTransformations.nestedExtendedStructMap(df, "", "id_str", (_, gf) =>
      concat(gf("id"), lit(" "), gf("key1").cast(StringType), lit(" "), gf("key2"))
    )

    val actualSchema = dfOut.schema.treeString
    val actualResults = JsonUtils.prettySparkJSON(dfOut.orderBy("id").toJSON.collect())

    assertSchema(actualSchema, expectedSchema)
    assertResults(actualResults, expectedResults)
  }

  test("Test extended array transformations work on an inner struct level fields") {
    val expectedSchema = getResourceString("/test_data/nested/nested2Schema.txt")
    val expectedResults = getResourceString("/test_data/nested/nested2Results.json")

    val df = nestedTestCaseFactory.getTestCase

    val dfOut = DeepArrayTransformations.nestedExtendedStructMap(df, "struct2", "skey2", (c, gf) =>
      concat(gf("key1"), lit(" "), gf("struct2.inner1.key5").cast(StringType), lit(" "),
        c.getField("inner1").getField("key6"))
    ).select("key1", "struct2")

    val actualSchema = dfOut.schema.treeString
    val actualResults = JsonUtils.prettySparkJSON(dfOut.orderBy("id").toJSON.collect())

    assertSchema(actualSchema, expectedSchema)
    assertResults(actualResults, expectedResults)
  }

  test("Test extended array transformations work on a double nested inner struct level fields") {
    val expectedSchema = getResourceString("/test_data/nested/nested3Schema.txt")
    val expectedResults = getResourceString("/test_data/nested/nested3Results.json")

    val df = nestedTestCaseFactory.getTestCase

    val dfOut = DeepArrayTransformations.nestedExtendedStructMap(df, "struct2.inner1", "skey2", (c, gf) =>
      concat(gf("key1"), lit(" "), gf("struct2.inner1.key5").cast(StringType), lit(" "), c.getField("key6"))
    ).select("key1", "struct2")

    val actualSchema = dfOut.schema.treeString
    val actualResults = JsonUtils.prettySparkJSON(dfOut.orderBy("id").toJSON.collect())

    assertSchema(actualSchema, expectedSchema)
    assertResults(actualResults, expectedResults)
  }

  test("Test extended array transformations work on a nested struct in an array") {
    val expectedSchema = getResourceString("/test_data/nested/nested4Schema.txt")
    val expectedResults = getResourceString("/test_data/nested/nested4Results.json")

    val df = nestedTestCaseFactory.getTestCase

    val dfOut = DeepArrayTransformations.nestedExtendedStructMap(df, "array1", "skey3", (c, gf) =>
      concat(gf("key1"), lit(" "), gf("array1.key7").cast(StringType), lit(" "), c.getField("key8"))
    ).select("key1", "array1")

    val actualSchema = dfOut.schema.treeString
    val actualResults = JsonUtils.prettySparkJSON(dfOut.orderBy("id").toJSON.collect())

    assertSchema(actualSchema, expectedSchema)
    assertResults(actualResults, expectedResults)
  }

  test("Test extended array transformations work on a nested struct in an array of an array") {
    val expectedSchema = getResourceString("/test_data/nested/nested5Schema.txt")
    val expectedResults = getResourceString("/test_data/nested/nested5Results.json")

    val df = nestedTestCaseFactory.getTestCase

    val dfOut = DeepArrayTransformations.nestedExtendedStructMap(df, "array2.inner2", "out", (c, gf) =>
      concat(gf("key1"),
        lit(" "),
        gf("array2.key2").cast(StringType),
        lit(" "),
        gf("array2.inner2.key9"),
        lit(" "),
        c.getField("key10"))
    ).select("key1", "array2")

    val actualSchema = dfOut.schema.treeString
    val actualResults = JsonUtils.prettySparkJSON(dfOut.orderBy("id").toJSON.collect())

    assertSchema(actualSchema, expectedSchema)
    assertResults(actualResults, expectedResults)
  }

  test("Test extended array transformations work if a nested struct in an array is accessed") {
    val expectedSchema = getResourceString("/test_data/nested/nested6Schema.txt")
    val expectedResults = getResourceString("/test_data/nested/nested6Results.json")

    val df = nestedTestCaseFactory.getTestCase

    val dfOut = DeepArrayTransformations.nestedExtendedStructMap(df, "array2.inner2", "out", (c, gf) =>
      concat(c.getField("key10"),
        lit(" "),
        gf("array2.inner2.struct3.k1").cast(StringType))
    ).select("array2")

    val actualSchema = dfOut.schema.treeString
    val actualResults = JsonUtils.prettySparkJSON(dfOut.orderBy("id").toJSON.collect())

    assertSchema(actualSchema, expectedSchema)
    assertResults(actualResults, expectedResults)
  }

  test("Test extended array transformations work for a nested struct in an array is accessed") {
    val expectedSchema = getResourceString("/test_data/nested/nested7Schema.txt")
    val expectedResults = getResourceString("/test_data/nested/nested7Results.json")

    val df = nestedTestCaseFactory.getTestCase

    val dfOut = DeepArrayTransformations.nestedExtendedStructMap(df, "array2.inner2.struct3", "out", (c, gf) =>
      concat(c.getField("k1"),
        lit(" "),
        gf("array2.inner2.key10").cast(StringType))
    ).select("array2")

    val actualSchema = dfOut.schema.treeString
    val actualResults = JsonUtils.prettySparkJSON(dfOut.orderBy("id").toJSON.collect())

    assertSchema(actualSchema, expectedSchema)
    assertResults(actualResults, expectedResults)
  }

  test("Test extended array transformations with error column work if a nested struct in an array is accessed") {
    val expectedSchema = getResourceString("/test_data/nested/nested8Schema.txt")
    val expectedResults = getResourceString("/test_data/nested/nested8Results.json")

    val df = nestedTestCaseFactory.getTestCase

    val dfOut = DeepArrayTransformations.nestedExtendedStructAndErrorMap(df, "array2.inner2", "out", "errCol", (c, gf) =>
      concat(c.getField("key10"),
        lit(" "),
        gf("array2.inner2.struct3.k1").cast(StringType))
      ,
      (_, gf) => {
        when(gf("array2.inner2.struct3.k1") !== 1,
          callUDF("confCastErr", lit("k1!==1"), gf("array2.inner2.struct3.k1").cast(StringType))
        ).otherwise(null)
      }
    ).select("array2", "errCol")

    val actualSchema = dfOut.schema.treeString
    val actualResults = JsonUtils.prettySparkJSON(dfOut.orderBy("id").toJSON.collect())

    assertSchema(actualSchema, expectedSchema)
    assertResults(actualResults, expectedResults)
  }

  private def getResourceString(name: String): String =
    IOUtils.toString(getClass.getResourceAsStream(name), "UTF-8")

  private def assertSchema(actualSchema: String, expectedSchema: String): Unit = {
    if (actualSchema != expectedSchema) {
      logger.error("EXPECTED:")
      logger.error(expectedSchema)
      logger.error("ACTUAL:")
      logger.error(actualSchema)
      fail("Actual conformed schema does not match the expected schema (see above).")
    }
  }

  private def assertResults(actualResults: String, expectedResults: String): Unit = {
    if (!expectedResults.startsWith(actualResults)) {
      logger.error("EXPECTED:")
      logger.error(expectedResults)
      logger.error("ACTUAL:")
      logger.error(actualResults)
      fail("Actual conformed dataset JSON does not match the expected JSON (see above).")
    }
  }
}

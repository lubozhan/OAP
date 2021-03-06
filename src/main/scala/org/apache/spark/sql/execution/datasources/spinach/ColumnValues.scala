/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.spinach

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.catalyst.util.{ArrayData, MapData}
import org.apache.spark.sql.execution.datasources.spinach.filecache.DataFiberCache
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}
import org.apache.spark.util.collection.BitSet


class ColumnValues(defaultSize: Int, dataType: DataType, val raw: DataFiberCache) {
  require(dataType.isInstanceOf[AtomicType], "Only atomic type accepted for now.")

  private val baseObject = raw.fiberData.getBaseObject
  // for any FiberData, the first defaultSize / 8 will be the bitmask
  // TODO what if defaultSize / 8 is not an integer?

  // TODO get the bitset from the FiberByteData
  val bitset: BitSet = {
    val bs = new BitSet(defaultSize)
    val longs = bs.toLongArray()
    Platform.copyMemory(baseObject, raw.fiberData.getBaseOffset,
      longs, Platform.LONG_ARRAY_OFFSET, longs.length * 8)
    bs
  }

  // TODO should be in FiberByteData
  private val baseOffset = raw.fiberData.getBaseOffset + bitset.toLongArray().length * 8

  def isNullAt(idx: Int): Boolean = !bitset.get(idx)

  private def genericGet(idx: Int): Any = dataType match {
    case BinaryType => getBinaryValue(idx)
    case BooleanType => getBooleanValue(idx)
    case ByteType => getByteValue(idx)
    case DateType => getDateValue(idx)
    case DoubleType => getDoubleValue(idx)
    case FloatType => getFloatValue(idx)
    case IntegerType => getIntValue(idx)
    case LongType => getLongValue(idx)
    case ShortType => getShortValue(idx)
    case StringType => getStringValue(idx)
    case _: ArrayType => throw new NotImplementedError(s"Array")
    case CalendarIntervalType => throw new NotImplementedError(s"CalendarInterval")
    case _: DecimalType => throw new NotImplementedError(s"Decimal")
    case _: MapType => throw new NotImplementedError(s"Map")
    case _: StructType => throw new NotImplementedError(s"Struct")
    case TimestampType => throw new NotImplementedError(s"Timestamp")
    case other => throw new NotImplementedError(s"other")
  }

  private def getAs[T](idx: Int): T = genericGet(idx).asInstanceOf[T]
  def get(idx: Int): AnyRef = getAs(idx)

  def getBooleanValue(idx: Int): Boolean = {
    Platform.getBoolean(baseObject, baseOffset + idx * BooleanType.defaultSize)
  }
  def getByteValue(idx: Int): Byte = {
    Platform.getByte(baseObject, baseOffset + idx * ByteType.defaultSize)
  }
  def getDateValue(idx: Int): Int = {
    Platform.getInt(baseObject, baseOffset + idx * IntegerType.defaultSize)
  }
  def getDoubleValue(idx: Int): Double = {
    Platform.getDouble(baseObject, baseOffset + idx * DoubleType.defaultSize)
  }
  def getIntValue(idx: Int): Int = {
    Platform.getInt(baseObject, baseOffset + idx * IntegerType.defaultSize)
  }
  def getLongValue(idx: Int): Long = {
    Platform.getLong(baseObject, baseOffset + idx * LongType.defaultSize)
  }
  def getShortValue(idx: Int): Short = {
    Platform.getShort(baseObject, baseOffset + idx * ShortType.defaultSize)
  }
  def getFloatValue(idx: Int): Float = {
    Platform.getFloat(baseObject, baseOffset + idx * FloatType.defaultSize)
  }

  def getStringValue(idx: Int): UTF8String = {
    //  The byte data format like:
    //    value #1 length (int)
    //    value #1 offset, (0 - based to the start of this Fiber Group)
    //    value #2 length
    //    value #2 offset, (0 - based to the start of this Fiber Group)
    //    …
    //    …
    //    value #N length
    //    value #N offset, (0 - based to the start of this Fiber Group)
    //    value #1
    //    value #2
    //    …
    //    value #N
    val length = getIntValue(idx * 2)
    val offset = getIntValue(idx * 2 + 1)
    UTF8String.fromAddress(baseObject, raw.fiberData.getBaseOffset + offset, length)
  }

  def getBinaryValue(idx: Int): Array[Byte] = {
    //  The byte data format like:
    //    value #1 length (int)
    //    value #1 offset, (0 - based to the start of this Fiber Group)
    //    value #2 length
    //    value #2 offset, (0 - based to the start of this Fiber Group)
    //    …
    //    …
    //    value #N length
    //    value #N offset, (0 - based to the start of this Fiber Group)
    //    value #1
    //    value #2
    //    …
    //    value #N
    val length = getIntValue(idx * 2)
    val offset = getIntValue(idx * 2 + 1)
    val result = new Array[Byte](length)
    Platform.copyMemory(baseObject, raw.fiberData.getBaseOffset + offset, result,
      Platform.BYTE_ARRAY_OFFSET, length)

    result
  }
}

class BatchColumn {
  private var currentIndex: Int = 0
  private var rowCount: Int = 0
  private var values: Array[ColumnValues] = _

  def reset(rowCount: Int, values: Array[ColumnValues]): BatchColumn = {
    this.rowCount = rowCount
    this.values = values
    currentIndex = -1
    this
  }

  def toIterator: Iterator[InternalRow] = new Iterator[InternalRow]() {
    override def hasNext: Boolean = currentIndex < rowCount - 1

    override def next(): InternalRow = {
      currentIndex += 1
      internalRow
    }
  }

  def moveToRow(idx: Int): InternalRow = {
    currentIndex = idx
    internalRow
  }

  object internalRow extends InternalRow {
    override def numFields: Int = values.length

    override def copy(): InternalRow = {
      val row = new Array[Any](values.length)
      var i = 0
      while (i < row.length) {
        row(i) = values(i).get(currentIndex)
        i += 1
      }
      new GenericInternalRow(row)
    }

    override def anyNull: Boolean = {
      var i = 0
      while (i < values.length) {
        if (values(i).isNullAt(currentIndex)) return true
        i += 1
      }
      return false
    }

    override def getUTF8String(ordinal: Int): UTF8String =
      values(ordinal).getStringValue(currentIndex)

    override def get(ordinal: Int, dataType: DataType): AnyRef = values(ordinal).get(currentIndex)

    override def getArray(ordinal: Int): ArrayData =
      throw new NotImplementedError("")

    override def getBinary(ordinal: Int): Array[Byte] = values(ordinal).getBinaryValue(currentIndex)

    override def getBoolean(ordinal: Int): Boolean = values(ordinal).getBooleanValue(currentIndex)

    override def getByte(ordinal: Int): Byte = values(ordinal).getByteValue(currentIndex)

    override def getDecimal(ordinal: Int, precision: Int, scale: Int): Decimal =
      throw new NotImplementedError("")

    override def getDouble(ordinal: Int): Double = values(ordinal).getDoubleValue(currentIndex)

    override def getFloat(ordinal: Int): Float = values(ordinal).getFloatValue(currentIndex)

    override def getInt(ordinal: Int): Int = values(ordinal).getIntValue(currentIndex)

    override def getInterval(ordinal: Int): CalendarInterval =
      throw new NotImplementedError("")

    override def getLong(ordinal: Int): Long = values(ordinal).getLongValue(currentIndex)

    override def getMap(ordinal: Int): MapData =
      throw new NotImplementedError("")

    override def getShort(ordinal: Int): Short = values(ordinal).getShortValue(currentIndex)

    override def getStruct(ordinal: Int, numFields: Int): InternalRow =
      throw new NotImplementedError("")

    override def isNullAt(ordinal: Int): Boolean = values(ordinal).isNullAt(currentIndex)
  }
}

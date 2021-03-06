/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.client;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.IncompatibleFilterException;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Used to perform Scan operations.
 * <p>
 * All operations are identical to {@link Get} with the exception of
 * instantiation.  Rather than specifying a single row, an optional startRow
 * and stopRow may be defined.  If rows are not specified, the Scanner will
 * iterate over all rows.
 * <p>
 * To scan everything for each row, instantiate a Scan object.
 * <p>
 * To modify scanner caching for just this scan, use {@link #setCaching(int) setCaching}.
 * If caching is NOT set, we will use the caching value of the hosting {@link HTable}.  See
 * {@link HTable#setScannerCaching(int)}. In addition to row caching, it is possible to specify a
 * maximum result size, using {@link #setMaxResultSize(long)}. When both are used,
 * single server requests are limited by either number of rows or maximum result size, whichever
 * limit comes first.
 * <p>
 * To further define the scope of what to get when scanning, perform additional
 * methods as outlined below.
 * <p>
 * To get all columns from specific families, execute {@link #addFamily(byte[]) addFamily}
 * for each family to retrieve.
 * <p>
 * To get specific columns, execute {@link #addColumn(byte[], byte[]) addColumn}
 * for each column to retrieve.
 * <p>
 * To only retrieve columns within a specific range of version timestamps,
 * execute {@link #setTimeRange(long, long) setTimeRange}.
 * <p>
 * To only retrieve columns with a specific timestamp, execute
 * {@link #setTimeStamp(long) setTimestamp}.
 * <p>
 * To limit the number of versions of each column to be returned, execute
 * {@link #setMaxVersions(int) setMaxVersions}.
 * <p>
 * To limit the maximum number of values returned for each call to next(),
 * execute {@link #setBatch(int) setBatch}.
 * <p>
 * To add a filter, execute {@link #setFilter(org.apache.hadoop.hbase.filter.Filter) setFilter}.
 * <p>
 * Expert: To explicitly disable server-side block caching for this scan,
 * execute {@link #setCacheBlocks(boolean)}.
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class Scan extends OperationWithAttributes {
  private static final String RAW_ATTR = "_raw_";
  private static final String ISOLATION_LEVEL = "_isolationlevel_";

  private static final byte VERSION_WITH_PAGINATION = (byte)4;
  private static final byte VERSION_WITH_RESULT_SIZE = (byte)3;
  private static final byte VERSION_WITH_ATTRIBUTES = (byte)2;
  
  private static final byte SCAN_VERSION = VERSION_WITH_PAGINATION;
  
  private byte [] startRow = HConstants.EMPTY_START_ROW;
  private byte [] stopRow  = HConstants.EMPTY_END_ROW;
  private int maxVersions = 1;
  private int batch = -1;

  private int storeLimit = -1;
  private int storeOffset = 0;
  
  // If application wants to collect scan metrics, it needs to
  // call scan.setAttribute(SCAN_ATTRIBUTES_ENABLE, Bytes.toBytes(Boolean.TRUE))
  static public String SCAN_ATTRIBUTES_METRICS_ENABLE =
    "scan.attributes.metrics.enable";
  static public String SCAN_ATTRIBUTES_METRICS_DATA =
    "scan.attributes.metrics.data";

  /*
   * -1 means no caching
   */
  private int caching = -1;
  private long maxResultSize = -1;
  private boolean cacheBlocks = true;
  private Filter filter = null;
  private TimeRange tr = new TimeRange();
  private Map<byte [], NavigableSet<byte []>> familyMap =
    new TreeMap<byte [], NavigableSet<byte []>>(Bytes.BYTES_COMPARATOR);

  /**
   * @return the most backward-compatible version for this scan possible for its parameters
   */
  private byte getVersion() {
    if (storeLimit != -1 || storeOffset != 0) {
      return VERSION_WITH_PAGINATION;
    }
    if (maxResultSize != -1) { 
      return VERSION_WITH_RESULT_SIZE;
    }
    if (getAttributeSize() != 0) {
      return VERSION_WITH_ATTRIBUTES;
    }
    return 1;
  }
  
  /**
   * Create a Scan operation across all rows.
   */
  public Scan() {}

  public Scan(byte [] startRow, Filter filter) {
    this(startRow);
    this.filter = filter;
  }

  /**
   * Create a Scan operation starting at the specified row.
   * <p>
   * If the specified row does not exist, the Scanner will start from the
   * next closest row after the specified row.
   * @param startRow row to start scanner at or after
   */
  public Scan(byte [] startRow) {
    this.startRow = startRow;
  }

  /**
   * Create a Scan operation for the range of rows specified.
   * @param startRow row to start scanner at or after (inclusive)
   * @param stopRow row to stop scanner before (exclusive)
   */
  public Scan(byte [] startRow, byte [] stopRow) {
    this.startRow = startRow;
    this.stopRow = stopRow;
  }

  /**
   * Creates a new instance of this class while copying all values.
   *
   * @param scan  The scan instance to copy from.
   * @throws IOException When copying the values fails.
   */
  public Scan(Scan scan) throws IOException {
    startRow = scan.getStartRow();
    stopRow  = scan.getStopRow();
    maxVersions = scan.getMaxVersions();
    batch = scan.getBatch();
    storeLimit = scan.getMaxResultsPerColumnFamily();
    storeOffset = scan.getRowOffsetPerColumnFamily();
    caching = scan.getCaching();
    maxResultSize = scan.getMaxResultSize();
    cacheBlocks = scan.getCacheBlocks();
    filter = scan.getFilter(); // clone?
    TimeRange ctr = scan.getTimeRange();
    tr = new TimeRange(ctr.getMin(), ctr.getMax());
    Map<byte[], NavigableSet<byte[]>> fams = scan.getFamilyMap();
    for (Map.Entry<byte[],NavigableSet<byte[]>> entry : fams.entrySet()) {
      byte [] fam = entry.getKey();
      NavigableSet<byte[]> cols = entry.getValue();
      if (cols != null && cols.size() > 0) {
        for (byte[] col : cols) {
          addColumn(fam, col);
        }
      } else {
        addFamily(fam);
      }
    }
    for (Map.Entry<String, byte[]> attr : scan.getAttributesMap().entrySet()) {
      setAttribute(attr.getKey(), attr.getValue());
    }
  }

  /**
   * Builds a scan object with the same specs as get.
   * @param get get to model scan after
   */
  public Scan(Get get) {
    this.startRow = get.getRow();
    this.stopRow = get.getRow();
    this.filter = get.getFilter();
    this.cacheBlocks = get.getCacheBlocks();
    this.maxVersions = get.getMaxVersions();
    this.storeLimit = get.getMaxResultsPerColumnFamily();
    this.storeOffset = get.getRowOffsetPerColumnFamily();
    this.tr = get.getTimeRange();
    this.familyMap = get.getFamilyMap();
  }

  public boolean isGetScan() {
    return this.startRow != null && this.startRow.length > 0 &&
      Bytes.equals(this.startRow, this.stopRow);
  }

  /**
   * Get all columns from the specified family.
   * <p>
   * Overrides previous calls to addColumn for this family.
   * @param family family name
   * @return this
   */
  public Scan addFamily(byte [] family) {
    familyMap.remove(family);
    familyMap.put(family, null);
    return this;
  }

  /**
   * Get the column from the specified family with the specified qualifier.
   * <p>
   * Overrides previous calls to addFamily for this family.
   * @param family family name
   * @param qualifier column qualifier
   * @return this
   */
  public Scan addColumn(byte [] family, byte [] qualifier) {
    NavigableSet<byte []> set = familyMap.get(family);
    if(set == null) {
      set = new TreeSet<byte []>(Bytes.BYTES_COMPARATOR);
    }
    if (qualifier != null) {
      set.add(qualifier);
    }
    familyMap.put(family, set);

    return this;
  }

  /**
   * Get versions of columns only within the specified timestamp range,
   * [minStamp, maxStamp).  Note, default maximum versions to return is 1.  If
   * your time range spans more than one version and you want all versions
   * returned, up the number of versions beyond the defaut.
   * @param minStamp minimum timestamp value, inclusive
   * @param maxStamp maximum timestamp value, exclusive
   * @throws IOException if invalid time range
   * @see #setMaxVersions()
   * @see #setMaxVersions(int)
   * @return this
   */
  public Scan setTimeRange(long minStamp, long maxStamp)
  throws IOException {
    tr = new TimeRange(minStamp, maxStamp);
    return this;
  }

  /**
   * Get versions of columns with the specified timestamp. Note, default maximum
   * versions to return is 1.  If your time range spans more than one version
   * and you want all versions returned, up the number of versions beyond the
   * defaut.
   * @param timestamp version timestamp
   * @see #setMaxVersions()
   * @see #setMaxVersions(int)
   * @return this
   */
  public Scan setTimeStamp(long timestamp) {
    try {
      tr = new TimeRange(timestamp, timestamp+1);
    } catch(IOException e) {
      // Will never happen
    }
    return this;
  }

  /**
   * Set the start row of the scan.
   * @param startRow row to start scan on (inclusive)
   * Note: In order to make startRow exclusive add a trailing 0 byte
   * @return this
   */
  public Scan setStartRow(byte [] startRow) {
    this.startRow = startRow;
    return this;
  }

  /**
   * Set the stop row.
   * @param stopRow row to end at (exclusive)
   * Note: In order to make stopRow inclusive add a trailing 0 byte
   * @return this
   */
  public Scan setStopRow(byte [] stopRow) {
    this.stopRow = stopRow;
    return this;
  }

  /**
   * Get all available versions.
   * @return this
   */
  public Scan setMaxVersions() {
    this.maxVersions = Integer.MAX_VALUE;
    return this;
  }

  /**
   * Get up to the specified number of versions of each column.
   * @param maxVersions maximum versions for each column
   * @return this
   */
  public Scan setMaxVersions(int maxVersions) {
    this.maxVersions = maxVersions;
    return this;
  }

  /**
   * Set the maximum number of values to return for each call to next()
   * @param batch the maximum number of values
   */
  public void setBatch(int batch) {
    if (this.hasFilter() && this.filter.hasFilterRow()) {
      throw new IncompatibleFilterException(
        "Cannot set batch on a scan using a filter" +
        " that returns true for filter.hasFilterRow");
    }
    this.batch = batch;
  }

  /**
   * Set the maximum number of values to return per row per Column Family
   * @param limit the maximum number of values returned / row / CF
   */
  public void setMaxResultsPerColumnFamily(int limit) {
    this.storeLimit = limit;
  }

  /**
   * Set offset for the row per Column Family.
   * @param offset is the number of kvs that will be skipped.
   */
  public void setRowOffsetPerColumnFamily(int offset) {
    this.storeOffset = offset;
  }

  /**
   * Set the number of rows for caching that will be passed to scanners.
   * If not set, the default setting from {@link HTable#getScannerCaching()} will apply.
   * Higher caching values will enable faster scanners but will use more memory.
   * @param caching the number of rows for caching
   */
  public void setCaching(int caching) {
    this.caching = caching;
  }

  /**
   * @return the maximum result size in bytes. See {@link #setMaxResultSize(long)}
   */
  public long getMaxResultSize() {
    return maxResultSize;
  }

  /**
   * Set the maximum result size. The default is -1; this means that no specific
   * maximum result size will be set for this scan, and the global configured
   * value will be used instead. (Defaults to unlimited).
   *
   * @param maxResultSize The maximum result size in bytes.
   */
  public void setMaxResultSize(long maxResultSize) {
    this.maxResultSize = maxResultSize;
  }

  /**
   * Apply the specified server-side filter when performing the Scan.
   * @param filter filter to run on the server
   * @return this
   */
  public Scan setFilter(Filter filter) {
    this.filter = filter;
    return this;
  }

  /**
   * Setting the familyMap
   * @param familyMap map of family to qualifier
   * @return this
   */
  public Scan setFamilyMap(Map<byte [], NavigableSet<byte []>> familyMap) {
    this.familyMap = familyMap;
    return this;
  }

  /**
   * Getting the familyMap
   * @return familyMap
   */
  public Map<byte [], NavigableSet<byte []>> getFamilyMap() {
    return this.familyMap;
  }

  /**
   * @return the number of families in familyMap
   */
  public int numFamilies() {
    if(hasFamilies()) {
      return this.familyMap.size();
    }
    return 0;
  }

  /**
   * @return true if familyMap is non empty, false otherwise
   */
  public boolean hasFamilies() {
    return !this.familyMap.isEmpty();
  }

  /**
   * @return the keys of the familyMap
   */
  public byte[][] getFamilies() {
    if(hasFamilies()) {
      return this.familyMap.keySet().toArray(new byte[0][0]);
    }
    return null;
  }

  /**
   * @return the startrow
   */
  public byte [] getStartRow() {
    return this.startRow;
  }

  /**
   * @return the stoprow
   */
  public byte [] getStopRow() {
    return this.stopRow;
  }

  /**
   * @return the max number of versions to fetch
   */
  public int getMaxVersions() {
    return this.maxVersions;
  }

  /**
   * @return maximum number of values to return for a single call to next()
   */
  public int getBatch() {
    return this.batch;
  }

  /**
   * @return maximum number of values to return per row per CF
   */
  public int getMaxResultsPerColumnFamily() {
    return this.storeLimit;
  }

  /**
   * Method for retrieving the scan's offset per row per column
   * family (#kvs to be skipped)
   * @return row offset
   */
  public int getRowOffsetPerColumnFamily() {
    return this.storeOffset;
  }

  /**
   * @return caching the number of rows fetched when calling next on a scanner
   */
  public int getCaching() {
    return this.caching;
  }

  /**
   * @return TimeRange
   */
  public TimeRange getTimeRange() {
    return this.tr;
  }

  /**
   * @return RowFilter
   */
  public Filter getFilter() {
    return filter;
  }

  /**
   * @return true is a filter has been specified, false if not
   */
  public boolean hasFilter() {
    return filter != null;
  }

  /**
   * Set whether blocks should be cached for this Scan.
   * <p>
   * This is true by default.  When true, default settings of the table and
   * family are used (this will never override caching blocks if the block
   * cache is disabled for that family or entirely).
   *
   * @param cacheBlocks if false, default settings are overridden and blocks
   * will not be cached
   */
  public void setCacheBlocks(boolean cacheBlocks) {
    this.cacheBlocks = cacheBlocks;
  }

  /**
   * Get whether blocks should be cached for this Scan.
   * @return true if default caching should be used, false if blocks should not
   * be cached
   */
  public boolean getCacheBlocks() {
    return cacheBlocks;
  }

  /**
   * Compile the table and column family (i.e. schema) information
   * into a String. Useful for parsing and aggregation by debugging,
   * logging, and administration tools.
   * @return Map
   */
  @Override
  public Map<String, Object> getFingerprint() {
    Map<String, Object> map = new HashMap<String, Object>();
    List<String> families = new ArrayList<String>();
    if(this.familyMap.size() == 0) {
      map.put("families", "ALL");
      return map;
    } else {
      map.put("families", families);
    }
    for (Map.Entry<byte [], NavigableSet<byte[]>> entry :
        this.familyMap.entrySet()) {
      families.add(Bytes.toStringBinary(entry.getKey()));
    }
    return map;
  }

  /**
   * Compile the details beyond the scope of getFingerprint (row, columns,
   * timestamps, etc.) into a Map along with the fingerprinted information.
   * Useful for debugging, logging, and administration tools.
   * @param maxCols a limit on the number of columns output prior to truncation
   * @return Map
   */ 
  @Override
  public Map<String, Object> toMap(int maxCols) {
    // start with the fingerpring map and build on top of it
    Map<String, Object> map = getFingerprint();
    // map from families to column list replaces fingerprint's list of families
    Map<String, List<String>> familyColumns =
      new HashMap<String, List<String>>();
    map.put("families", familyColumns);
    // add scalar information first
    map.put("startRow", Bytes.toStringBinary(this.startRow));
    map.put("stopRow", Bytes.toStringBinary(this.stopRow));
    map.put("maxVersions", this.maxVersions);
    map.put("batch", this.batch);
    map.put("caching", this.caching);
    map.put("maxResultSize", this.maxResultSize);
    map.put("cacheBlocks", this.cacheBlocks);
    List<Long> timeRange = new ArrayList<Long>();
    timeRange.add(this.tr.getMin());
    timeRange.add(this.tr.getMax());
    map.put("timeRange", timeRange);
    int colCount = 0;
    // iterate through affected families and list out up to maxCols columns
    for (Map.Entry<byte [], NavigableSet<byte[]>> entry :
      this.familyMap.entrySet()) {
      List<String> columns = new ArrayList<String>();
      familyColumns.put(Bytes.toStringBinary(entry.getKey()), columns);
      if(entry.getValue() == null) {
        colCount++;
        --maxCols;
        columns.add("ALL");
      } else {
        colCount += entry.getValue().size();
        if (maxCols <= 0) {
          continue;
        } 
        for (byte [] column : entry.getValue()) {
          if (--maxCols <= 0) {
            continue;
          }
          columns.add(Bytes.toStringBinary(column));
        }
      } 
    }       
    map.put("totalColumns", colCount);
    if (this.filter != null) {
      map.put("filter", this.filter.toString());
    }
    // add the id if set
    if (getId() != null) {
      map.put("id", getId());
    }
    return map;
  }

  /**
   * Enable/disable "raw" mode for this scan.
   * If "raw" is enabled the scan will return all
   * delete marker and deleted rows that have not
   * been collected, yet.
   * This is mostly useful for Scan on column families
   * that have KEEP_DELETED_ROWS enabled.
   * It is an error to specify any column when "raw" is set.
   * @param raw True/False to enable/disable "raw" mode.
   */
  public void setRaw(boolean raw) {
    setAttribute(RAW_ATTR, Bytes.toBytes(raw));
  }

  /**
   * @return True if this Scan is in "raw" mode.
   */
  public boolean isRaw() {
    byte[] attr = getAttribute(RAW_ATTR);
    return attr == null ? false : Bytes.toBoolean(attr);
  }

  /*
   * Set the isolation level for this scan. If the
   * isolation level is set to READ_UNCOMMITTED, then
   * this scan will return data from committed and
   * uncommitted transactions. If the isolation level 
   * is set to READ_COMMITTED, then this scan will return 
   * data from committed transactions only. If a isolation
   * level is not explicitly set on a Scan, then it 
   * is assumed to be READ_COMMITTED.
   * @param level IsolationLevel for this scan
   */
  public void setIsolationLevel(IsolationLevel level) {
    setAttribute(ISOLATION_LEVEL, level.toBytes());
  }
  /*
   * @return The isolation level of this scan.
   * If no isolation level was set for this scan object, 
   * then it returns READ_COMMITTED.
   * @return The IsolationLevel for this scan
   */
  public IsolationLevel getIsolationLevel() {
    byte[] attr = getAttribute(ISOLATION_LEVEL);
    return attr == null ? IsolationLevel.READ_COMMITTED :
                          IsolationLevel.fromBytes(attr);
  }
}

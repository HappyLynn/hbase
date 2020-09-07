/**
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
package org.apache.hadoop.hbase;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import org.apache.hadoop.hbase.TestMetaTableAccessor.SpyingRpcScheduler;
import org.apache.hadoop.hbase.TestMetaTableAccessor.SpyingRpcSchedulerFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.RSRpcServices;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.testclassification.MiscTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category({ MiscTests.class, MediumTests.class })
public class TestMetaUpdatesGoToPriorityQueue {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestMetaUpdatesGoToPriorityQueue.class);

  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();

  @BeforeClass
  public static void beforeClass() throws Exception {
    // This test has to be end-to-end, and do the verification from the server side
    UTIL.getConfiguration().set(RSRpcServices.REGION_SERVER_RPC_SCHEDULER_FACTORY_CLASS,
      SpyingRpcSchedulerFactory.class.getName());
    UTIL.startMiniCluster();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  @Test
  public void test() throws IOException, InterruptedException {
    TableName tableName = TableName.valueOf(getClass().getSimpleName());
    // create a table and prepare for a manual split
    UTIL.createTable(tableName, "cf1");
    UTIL.waitTableAvailable(tableName);
    RegionInfo parent = UTIL.getAdmin().getRegions(tableName).get(0);
    long rid = 1000;
    byte[] splitKey = Bytes.toBytes("a");
    RegionInfo splitA =
      RegionInfoBuilder.newBuilder(parent.getTable()).setStartKey(parent.getStartKey())
        .setEndKey(splitKey).setSplit(false).setRegionId(rid).build();
    RegionInfo splitB = RegionInfoBuilder.newBuilder(parent.getTable()).setStartKey(splitKey)
      .setEndKey(parent.getEndKey()).setSplit(false).setRegionId(rid).build();

    // find the meta server
    MiniHBaseCluster cluster = UTIL.getMiniHBaseCluster();
    int rsIndex = cluster.getServerWithMeta();
    HRegionServer rs;
    if (rsIndex >= 0) {
      rs = cluster.getRegionServer(rsIndex);
    } else {
      // it is in master
      rs = cluster.getMaster();
    }
    SpyingRpcScheduler scheduler = (SpyingRpcScheduler) rs.getRpcServer().getScheduler();
    long prevCalls = scheduler.numPriorityCalls;
    long time = System.currentTimeMillis();
    Put putParent = MetaTableAccessor.makePutFromRegionInfo(
      RegionInfoBuilder.newBuilder(parent).setOffline(true).setSplit(true).build(), time);
    MetaTableAccessor.addDaughtersToPut(putParent, splitA, splitB);
    Put putA = MetaTableAccessor.makePutFromRegionInfo(splitA, time);
    Put putB = MetaTableAccessor.makePutFromRegionInfo(splitB, time);
    MetaTableAccessor.multiMutate(UTIL.getConnection(), putParent.getRow(),
      Arrays.asList(putParent, putA, putB));

    assertTrue(prevCalls < scheduler.numPriorityCalls);
  }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.solr.cli.ConfigTool;
import org.apache.solr.cli.CreateCollectionTool;
import org.apache.solr.cli.DeleteTool;
import org.apache.solr.cli.HealthcheckTool;
import org.apache.solr.cli.SolrCLI;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.StreamingUpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.util.ExternalPaths;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emulates bin/solr -e cloud -noprompt; bin/post -c gettingstarted example/exampledocs/*.xml; this
 * test is useful for catching regressions in indexing the example docs in collections that use data
 * driven functionality and managed schema features of the default configset (configsets/_default).
 */
public class SolrCloudExampleTest extends AbstractFullDistribZkTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public SolrCloudExampleTest() {
    super();
    sliceCount = 2;
  }

  @Test
  public void testLoadDocsIntoGettingStartedCollection() throws Exception {
    waitForThingsToLevelOut(30, TimeUnit.SECONDS);

    log.info("testLoadDocsIntoGettingStartedCollection initialized OK ... running test logic");

    String testCollectionName = "gettingstarted";
    File defaultConfigs = new File(ExternalPaths.DEFAULT_CONFIGSET);
    assertTrue(defaultConfigs.getAbsolutePath() + " not found!", defaultConfigs.isDirectory());

    Set<String> liveNodes = cloudClient.getClusterState().getLiveNodes();
    if (liveNodes.isEmpty())
      fail(
          "No live nodes found! Cannot create a collection until there is at least 1 live node in the cluster.");
    String firstLiveNode = liveNodes.iterator().next();
    String solrUrl = ZkStateReader.from(cloudClient).getBaseUrlForNodeName(firstLiveNode);

    // create the gettingstarted collection just like the bin/solr script would do
    String[] args =
        new String[] {
          "-name",
          testCollectionName,
          "-shards",
          "2",
          "-replicationFactor",
          "2",
          "-confname",
          testCollectionName,
          "-confdir",
          "_default",
          "-configsetsDir",
          defaultConfigs.getParentFile().getParentFile().getAbsolutePath(),
          "-solrUrl",
          solrUrl
        };

    // NOTE: not calling SolrCLI.main as the script does because it calls System.exit which is a
    // no-no in a JUnit test

    CreateCollectionTool tool = new CreateCollectionTool();
    CommandLine cli = SolrCLI.processCommandLineArgs(tool.getName(), tool.getOptions(), args);
    log.info("Creating the '{}' collection using SolrCLI with: {}", testCollectionName, solrUrl);
    tool.runTool(cli);
    assertTrue(
        "Collection '" + testCollectionName + "' doesn't exist after trying to create it!",
        cloudClient.getClusterState().hasCollection(testCollectionName));

    // verify the collection is usable ...
    ensureAllReplicasAreActive(testCollectionName, "shard1", 2, 2, 20);
    ensureAllReplicasAreActive(testCollectionName, "shard2", 2, 2, 10);

    int invalidToolExitStatus = 1;
    assertEquals(
        "Collection '" + testCollectionName + "' created even though it already existed",
        invalidToolExitStatus,
        tool.runTool(cli));

    // now index docs like bin/post would, but we can't use SimplePostTool because it uses
    // System.exit when it encounters an error, which JUnit doesn't like ...
    log.info("Created collection, now posting example docs!");
    Path exampleDocsDir = Path.of(ExternalPaths.SOURCE_HOME, "example", "exampledocs");
    assertTrue(exampleDocsDir.toAbsolutePath() + " not found!", Files.isDirectory(exampleDocsDir));

    List<Path> xmlFiles;
    try (Stream<Path> stream = Files.walk(exampleDocsDir, 1)) {
      xmlFiles =
          stream
              .filter(path -> path.getFileName().toString().endsWith(".xml"))
              // don't rely on File.compareTo, it's behavior varies by OS
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              // be explicit about the collection type because we will shuffle it later
              .collect(Collectors.toCollection(ArrayList::new));
    }

    // force a deterministic random ordering of the files so seeds reproduce regardless of
    // platform/filesystem
    Collections.shuffle(xmlFiles, new Random(random().nextLong()));

    // if you add/remove example XML docs, you'll have to fix these expected values
    int expectedXmlFileCount = 14;
    int expectedXmlDocCount = 32;

    assertEquals(
        "Unexpected # of example XML files in " + exampleDocsDir.toAbsolutePath(),
        expectedXmlFileCount,
        xmlFiles.size());

    for (Path xml : xmlFiles) {
      if (log.isInfoEnabled()) {
        log.info("POSTing {}", xml.toAbsolutePath());
      }
      cloudClient.request(
          new StreamingUpdateRequest("/update", xml, "application/xml"), testCollectionName);
    }
    cloudClient.commit(testCollectionName);

    int numFound = 0;

    // give the update a chance to take effect.
    for (int idx = 0; idx < 100; ++idx) {
      QueryResponse qr = cloudClient.query(testCollectionName, new SolrQuery("*:*"));
      numFound = (int) qr.getResults().getNumFound();
      if (numFound == expectedXmlDocCount) break;
      Thread.sleep(100);
    }
    assertEquals("*:* found unexpected number of documents", expectedXmlDocCount, numFound);

    log.info("Updating Config for {}", testCollectionName);
    doTestConfigUpdate(testCollectionName, solrUrl);

    log.info("Running healthcheck for {}", testCollectionName);
    doTestHealthcheck(testCollectionName, cloudClient.getClusterStateProvider().getQuorumHosts());

    // verify the delete action works too
    log.info("Running delete for {}", testCollectionName);
    doTestDeleteAction(testCollectionName, solrUrl);

    log.info("testLoadDocsIntoGettingStartedCollection succeeded ... shutting down now!");
  }

  protected void doTestHealthcheck(String testCollectionName, String zkHost) throws Exception {
    String[] args =
        new String[] {
          "-collection", testCollectionName,
          "-zkHost", zkHost
        };
    HealthcheckTool tool = new HealthcheckTool();
    CommandLine cli = SolrCLI.processCommandLineArgs(tool.getName(), tool.getOptions(), args);
    assertEquals("Healthcheck action failed!", 0, tool.runTool(cli));
  }

  protected void doTestDeleteAction(String testCollectionName, String solrUrl) throws Exception {
    String[] args =
        new String[] {
          "-name", testCollectionName,
          "-solrUrl", solrUrl
        };
    DeleteTool tool = new DeleteTool();
    CommandLine cli = SolrCLI.processCommandLineArgs(tool.getName(), tool.getOptions(), args);
    assertEquals("Delete action failed!", 0, tool.runTool(cli));
    assertFalse(
        SolrCLI.safeCheckCollectionExists(
            solrUrl, testCollectionName)); // it should not exist anymore
  }

  /**
   * Uses the SolrCLI config action to activate soft auto-commits for the getting started
   * collection.
   */
  protected void doTestConfigUpdate(String testCollectionName, String solrUrl) throws Exception {
    if (!solrUrl.endsWith("/")) solrUrl += "/";

    try (SolrClient solrClient = SolrCLI.getSolrClient(solrUrl)) {
      NamedList<Object> configJson =
          solrClient.request(
              new GenericSolrRequest(SolrRequest.METHOD.GET, "/" + testCollectionName + "/config"));
      Object maxTimeFromConfig =
          configJson._get("/config/updateHandler/autoSoftCommit/maxTime", Collections.emptyMap());
      assertNotNull(maxTimeFromConfig);
      assertEquals(-1, maxTimeFromConfig);

      String prop = "updateHandler.autoSoftCommit.maxTime";
      Integer maxTime = 3000;
      String[] args =
          new String[] {
            "-collection", testCollectionName,
            "-property", prop,
            "-value", maxTime.toString(),
            "-solrUrl", solrUrl
          };

      Map<String, Integer> startTimes = getSoftAutocommitInterval(testCollectionName, solrClient);

      ConfigTool tool = new ConfigTool();
      CommandLine cli = SolrCLI.processCommandLineArgs(tool.getName(), tool.getOptions(), args);
      log.info("Sending set-property '{}'={} to SolrCLI.ConfigTool.", prop, maxTime);
      assertEquals("Set config property failed!", 0, tool.runTool(cli));

      configJson =
          solrClient.request(
              new GenericSolrRequest(SolrRequest.METHOD.GET, "/" + testCollectionName + "/config"));
      maxTimeFromConfig =
          configJson._get("/config/updateHandler/autoSoftCommit/maxTime", Collections.emptyMap());
      assertNotNull(maxTimeFromConfig);
      assertEquals(maxTime, maxTimeFromConfig);

      if (log.isInfoEnabled()) {
        log.info("live_nodes_count :  {}", cloudClient.getClusterState().getLiveNodes());
      }

      // Need to use the _get(List, Object) here because of /query in the path
      assertEquals(
          "Should have been able to get a value from the /query request handler",
          "explicit",
          configJson._get(
              Arrays.asList("config", "requestHandler", "/query", "defaults", "echoParams"),
              Collections.emptyMap()));

      // Since it takes some time for this command to complete we need to make sure all the reloads
      // for all the cores have been done.
      boolean allGood = false;
      Map<String, Integer> curSoftCommitInterval = null;
      for (int idx = 0; idx < 600 && !allGood; ++idx) {
        curSoftCommitInterval = getSoftAutocommitInterval(testCollectionName, solrClient);
        // no point in even trying if they're not the same size!
        if (curSoftCommitInterval.size() > 0 && curSoftCommitInterval.size() == startTimes.size()) {
          allGood = true;
          for (Map.Entry<String, Integer> currEntry : curSoftCommitInterval.entrySet()) {
            if (!currEntry.getValue().equals(maxTime)) {
              allGood = false;
            }
          }
        }
        if (!allGood) {
          Thread.sleep(100);
        }
      }
      assertTrue("All cores should have been reloaded within 60 seconds!!!", allGood);
    }
  }

  // Collect all the autoSoftCommit intervals.
  private Map<String, Integer> getSoftAutocommitInterval(String collection, SolrClient solrClient)
      throws Exception {
    Map<String, Integer> ret = new HashMap<>();
    DocCollection coll = cloudClient.getClusterState().getCollection(collection);
    for (Slice slice : coll.getActiveSlices()) {
      for (Replica replica : slice.getReplicas()) {
        NamedList<Object> configJson =
            solrClient.request(
                new GenericSolrRequest(
                    SolrRequest.METHOD.GET,
                    "/" + replica.get(ZkStateReader.CORE_NAME_PROP) + "/config"));
        Integer maxTime =
            (Integer)
                configJson._get(
                    "/config/updateHandler/autoSoftCommit/maxTime", Collections.emptyMap());
        ret.put(replica.getCoreName(), maxTime);
      }
    }
    return ret;
  }
}

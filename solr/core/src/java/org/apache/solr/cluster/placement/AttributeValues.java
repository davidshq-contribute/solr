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

package org.apache.solr.cluster.placement;

import java.util.Optional;
import org.apache.solr.cluster.Node;

public interface AttributeValues {
  /**
   * For the given node: system property value (system properties are passed to Java using {@code
   * -Dname=value}
   */
  Optional<String> getSystemProperty(Node node, String name);

  /** For the given node: metric identified by an instance of {@link NodeMetric} */
  <T> Optional<T> getNodeMetric(Node node, NodeMetric<T> metric);

  /**
   * For the given node: increase the metric identified by an instance of {@link NodeMetric} by the
   * given amount
   */
  <T> boolean increaseNodeMetric(Node node, NodeMetric<T> metric, T byValue);

  /**
   * For the given node: decrease the metric identified by an instance of {@link NodeMetric} by the
   * given amount
   */
  <T> boolean decreaseNodeMetric(Node node, NodeMetric<T> metric, T byValue);

  /** Get collection metrics. */
  Optional<CollectionMetrics> getCollectionMetrics(String collectionName);
}

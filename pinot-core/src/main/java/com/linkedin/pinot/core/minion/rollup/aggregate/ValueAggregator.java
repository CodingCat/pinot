/**
 * Copyright (C) 2014-2018 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.minion.rollup.aggregate;

import com.linkedin.pinot.common.data.MetricFieldSpec;


/**
 * Interface for value aggregator
 */
public interface ValueAggregator {

  /**
   * Given two values and its metric fieldspec, return the aggregated value
   * @param value1 first metric column value
   * @param value2 second metric column value
   * @param metricFieldSpec metric field spec
   * @return aggregated value given two column values
   */
  Object aggregate(Object value1, Object value2, MetricFieldSpec metricFieldSpec);
}

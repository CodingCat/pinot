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
package com.linkedin.pinot.controller.helix.core.minion;

import com.linkedin.pinot.common.config.PinotTaskConfig;
import com.linkedin.pinot.common.config.TableConfig;
import com.linkedin.pinot.common.config.TableTaskConfig;
import com.linkedin.pinot.common.metrics.ControllerMeter;
import com.linkedin.pinot.common.metrics.ControllerMetrics;
import com.linkedin.pinot.common.utils.PeriodicTask;
import com.linkedin.pinot.controller.ControllerConf;
import com.linkedin.pinot.controller.helix.core.PinotHelixResourceManager;
import com.linkedin.pinot.controller.helix.core.minion.generator.PinotTaskGenerator;
import com.linkedin.pinot.controller.helix.core.minion.generator.TaskGeneratorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The class <code>PinotTaskManager</code> is the component inside Pinot Controller to periodically check the Pinot
 * cluster status and schedule new tasks.
 * <p><code>PinotTaskManager</code> is also responsible for checking the health status on each type of tasks, detect and
 * fix issues accordingly.
 */
public class PinotTaskManager extends PeriodicTask {
  private static final Logger LOGGER = LoggerFactory.getLogger(PinotTaskManager.class);

  private final PinotHelixResourceManager _helixResourceManager;
  private final PinotHelixTaskResourceManager _helixTaskResourceManager;
  private final ClusterInfoProvider _clusterInfoProvider;
  private final TaskGeneratorRegistry _taskGeneratorRegistry;
  private final ControllerMetrics _controllerMetrics;

  public PinotTaskManager(@Nonnull PinotHelixTaskResourceManager helixTaskResourceManager,
      @Nonnull PinotHelixResourceManager helixResourceManager, @Nonnull ControllerConf controllerConf,
      @Nonnull ControllerMetrics controllerMetrics) {
    super("PinotTaskManager", controllerConf.getTaskManagerFrequencyInSeconds(),
        Math.min(60, controllerConf.getTaskManagerFrequencyInSeconds()));
    _helixResourceManager = helixResourceManager;
    _helixTaskResourceManager = helixTaskResourceManager;
    _clusterInfoProvider = new ClusterInfoProvider(helixResourceManager, helixTaskResourceManager, controllerConf);
    _taskGeneratorRegistry = new TaskGeneratorRegistry(_clusterInfoProvider);
    _controllerMetrics = controllerMetrics;
  }

  /**
   * Get the cluster info provider.
   * <p>Cluster info provider might be needed to initialize task generators.
   *
   * @return Cluster info provider
   */
  @Nonnull
  public ClusterInfoProvider getClusterInfoProvider() {
    return _clusterInfoProvider;
  }

  /**
   * Register a task generator.
   * <p>This is for pluggable task generators.
   *
   * @param pinotTaskGenerator Task generator to be registered
   */
  public void registerTaskGenerator(@Nonnull PinotTaskGenerator pinotTaskGenerator) {
    _taskGeneratorRegistry.registerTaskGenerator(pinotTaskGenerator);
  }

  /**
   * Check the Pinot cluster status and schedule new tasks.
   *
   * @return Map from task type to task scheduled
   */
  @Nonnull
  public Map<String, String> scheduleTasks() {
    // Only schedule new tasks from leader controller
    if (!_helixResourceManager.isLeader()) {
      LOGGER.info("Skip scheduling new tasks on non-leader controller");
      return new HashMap<>();
    }

    _controllerMetrics.addMeteredGlobalValue(ControllerMeter.NUMBER_TIMES_SCHEDULE_TASKS_CALLED, 1L);

    Set<String> taskTypes = _taskGeneratorRegistry.getAllTaskTypes();
    int numTaskTypes = taskTypes.size();
    Map<String, List<TableConfig>> enabledTableConfigMap = new HashMap<>(numTaskTypes);

    for (String taskType : taskTypes) {
      enabledTableConfigMap.put(taskType, new ArrayList<>());

      // Ensure all task queues exist
      _helixTaskResourceManager.ensureTaskQueueExists(taskType);
    }

    // Scan all table configs to get the tables with tasks enabled
    for (String tableName : _helixResourceManager.getAllTables()) {
      TableConfig tableConfig = _helixResourceManager.getTableConfig(tableName);
      if (tableConfig != null) {
        TableTaskConfig taskConfig = tableConfig.getTaskConfig();
        if (taskConfig != null) {
          for (String taskType : taskTypes) {
            if (taskConfig.isTaskTypeEnabled(taskType)) {
              enabledTableConfigMap.get(taskType).add(tableConfig);
            }
          }
        }
      }
    }

    // Generate each type of tasks
    Map<String, String> tasksScheduled = new HashMap<>(numTaskTypes);
    for (String taskType : taskTypes) {
      LOGGER.info("Generating tasks for task type: {}", taskType);
      PinotTaskGenerator pinotTaskGenerator = _taskGeneratorRegistry.getTaskGenerator(taskType);
      List<PinotTaskConfig> pinotTaskConfigs = pinotTaskGenerator.generateTasks(enabledTableConfigMap.get(taskType));
      int numTasks = pinotTaskConfigs.size();
      if (numTasks > 0) {
        LOGGER.info("Submitting {} tasks for task type: {} with task configs: {}", numTasks, taskType,
            pinotTaskConfigs);
        tasksScheduled.put(taskType, _helixTaskResourceManager.submitTask(pinotTaskConfigs,
            pinotTaskGenerator.getNumConcurrentTasksPerInstance()));
        _controllerMetrics.addMeteredTableValue(taskType, ControllerMeter.NUMBER_TASKS_SUBMITTED, numTasks);
      }
    }

    return tasksScheduled;
  }

  @Override
  public void runTask() {
    scheduleTasks();
  }

  @Override
  public void initTask() {
    LOGGER.info("Starting task manager with running frequency of {} seconds", getIntervalSeconds());
  }
}

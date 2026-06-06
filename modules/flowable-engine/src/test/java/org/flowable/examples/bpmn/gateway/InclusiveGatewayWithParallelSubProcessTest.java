/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.examples.bpmn.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flowable.engine.impl.test.PluggableFlowableTestCase;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.test.Deployment;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;

/**
 * Test for Inclusive Gateway in parallel subprocesses, verifying the fix for
 * incorrect sequence flow selection when multiple parallel subprocesses are active.
 *
 * @author Flowable Team
 */
public class InclusiveGatewayWithParallelSubProcessTest extends PluggableFlowableTestCase {

    @Test
    @Deployment
    public void testInclusiveGatewayInParallelSubProcess() {
        
        // Test case 1: decision is 'both', so both tasks should be created in both subprocesses
        Map<String, Object> variables = new HashMap<>();
        variables.put("decision", "both");
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("inclusiveGatewayInParallelSubProcess", variables);
        
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).orderByTaskName().asc().list();
        assertThat(tasks).hasSize(4);
        
        assertThat(tasks.get(0).getName()).isEqualTo("Task 1A");
        assertThat(tasks.get(1).getName()).isEqualTo("Task 1B");
        assertThat(tasks.get(2).getName()).isEqualTo("Task 2A");
        assertThat(tasks.get(3).getName()).isEqualTo("Task 2B");
        
        // Complete all tasks
        for (Task task : tasks) {
            taskService.complete(task.getId());
        }
        
        assertProcessEnded(pi.getId());
        
        // Test case 2: decision is 'a', so only task 1a and task 2a should be created
        variables = new HashMap<>();
        variables.put("decision", "a");
        pi = runtimeService.startProcessInstanceByKey("inclusiveGatewayInParallelSubProcess", variables);
        
        tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).orderByTaskName().asc().list();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).getName()).isEqualTo("Task 1A");
        assertThat(tasks.get(1).getName()).isEqualTo("Task 2A");
        
        for (Task task : tasks) {
            taskService.complete(task.getId());
        }
        
        assertProcessEnded(pi.getId());
        
        // Test case 3: decision is 'b', so only task 1b and task 2b should be created
        variables = new HashMap<>();
        variables.put("decision", "b");
        pi = runtimeService.startProcessInstanceByKey("inclusiveGatewayInParallelSubProcess", variables);
        
        tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).orderByTaskName().asc().list();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).getName()).isEqualTo("Task 1B");
        assertThat(tasks.get(1).getName()).isEqualTo("Task 2B");
        
        for (Task task : tasks) {
            taskService.complete(task.getId());
        }
        
        assertProcessEnded(pi.getId());
    }
}

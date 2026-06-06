package org.flowable.engine.test.bpmn.gateway;

import org.flowable.engine.impl.test.PluggableFlowableTestCase;
import org.flowable.engine.test.Deployment;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class InclusiveGatewayNestedSubProcessTest extends PluggableFlowableTestCase {

    @Test
    @Deployment(resources = "org/flowable/engine/test/bpmn/gateway/InclusiveGatewayNestedSubProcessTest.testNestedSubProcess.bpmn20.xml")
    public void testInclusiveGatewayInNestedSubProcess() {
        runtimeService.startProcessInstanceByKey("inclusiveGatewayNestedSubProcess");

        List<Task> tasks = taskService.createTaskQuery().list();
        assertThat(tasks).hasSize(2);

        for (Task task : tasks) {
            taskService.complete(task.getId());
        }

        Task finalTask = taskService.createTaskQuery().singleResult();
        assertThat(finalTask).isNotNull();
        assertThat(finalTask.getName()).isEqualTo("Final Task");

        taskService.complete(finalTask.getId());

        assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
        
        // Ensure history is recorded
        assertThat(historyService.createHistoricActivityInstanceQuery().activityId("inclusiveJoin").count()).isEqualTo(1);
    }
}

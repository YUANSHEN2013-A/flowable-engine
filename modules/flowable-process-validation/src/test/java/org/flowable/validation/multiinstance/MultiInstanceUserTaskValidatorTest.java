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
package org.flowable.validation.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ValidationError;
import org.flowable.validation.validator.Problems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MultiInstanceUserTaskValidatorTest {

    protected ProcessValidator processValidator;

    @BeforeEach
    public void setupProcessValidator() {
        ProcessValidatorFactory processValidatorFactory = new ProcessValidatorFactory();
        this.processValidator = processValidatorFactory.createDefaultProcessValidator();
    }

    @Test
    public void testValidMultiInstanceUserTask() {
        BpmnModel bpmnModel = createBpmnModelWithMIUserTask("validProcess", "miTask",
                "userList", "user", "${user}", null, null, null);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).isEmpty();
    }

    @Test
    public void testMissingElementVariable() {
        BpmnModel bpmnModel = createBpmnModelWithMIUserTask("missingElementVar", "miTask",
                "userList", null, null, null, null, null);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).hasSize(1);
        assertThat(miErrors.get(0).getProblem()).isEqualTo(Problems.MISSING_ELEMENT_VARIABLE);
        assertThat(miErrors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testInvalidAssigneeExpression() {
        BpmnModel bpmnModel = createBpmnModelWithMIUserTask("invalidAssignee", "miTask",
                "userList", "user", "${otherVar}", null, null, null);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).hasSize(1);
        assertThat(miErrors.get(0).getProblem()).isEqualTo(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE);
        assertThat(miErrors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testInvalidCandidateUsersExpression() {
        UserTask userTask = createMIUserTask("miTask", "userList", "user");
        userTask.getCandidateUsers().add("${otherVar}");

        BpmnModel bpmnModel = createBpmnModel("invalidCandidateUsers", userTask);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).hasSize(1);
        assertThat(miErrors.get(0).getProblem()).isEqualTo(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(miErrors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testInvalidCandidateGroupsExpression() {
        UserTask userTask = createMIUserTask("miTask", "groupList", "group");
        userTask.getCandidateGroups().add("${otherGroup}");

        BpmnModel bpmnModel = createBpmnModel("invalidCandidateGroups", userTask);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).hasSize(1);
        assertThat(miErrors.get(0).getProblem()).isEqualTo(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(miErrors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testCollectionLoopCardinalityConflict() {
        UserTask userTask = createMIUserTask("miTask", "userList", "user");
        userTask.setAssignee("${user}");
        userTask.getLoopCharacteristics().setLoopCardinality("5");

        BpmnModel bpmnModel = createBpmnModel("collectionLoopConflict", userTask);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).hasSize(1);
        assertThat(miErrors.get(0).getProblem()).isEqualTo(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT);
        assertThat(miErrors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testNonMultiInstanceUserTaskNotAffected() {
        BpmnModel bpmnModel = new BpmnModel();
        Process process = new Process();
        process.setId("simpleProcess");
        process.setExecutable(true);
        bpmnModel.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask userTask = new UserTask();
        userTask.setId("simpleTask");
        userTask.setName("Simple Task");
        userTask.setAssignee("kermit");
        process.addFlowElement(userTask);

        EndEvent end = new EndEvent();
        end.setId("end");
        process.addFlowElement(end);

        SequenceFlow flow1 = new SequenceFlow();
        flow1.setId("flow1");
        flow1.setSourceRef("start");
        flow1.setTargetRef("simpleTask");
        process.addFlowElement(flow1);

        SequenceFlow flow2 = new SequenceFlow();
        flow2.setId("flow2");
        flow2.setSourceRef("simpleTask");
        flow2.setTargetRef("end");
        process.addFlowElement(flow2);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).isEmpty();
    }

    @Test
    public void testExistingValidationRulesNotAffected() {
        BpmnModel bpmnModel = createBpmnModelWithMIUserTask("validProcess", "miTask",
                "userList", "user", "${user}", null, null, null);

        List<ValidationError> allErrors = processValidator.validate(bpmnModel);
        for (ValidationError error : allErrors) {
            assertThat(error.getProblem()).isNotEqualTo(Problems.MULTI_INSTANCE_MISSING_COLLECTION);
            assertThat(error.getProblem()).isNotEqualTo(Problems.MULTI_INSTANCE_MISSING_COLLECTION_FUNCTION_PARAMETERS);
        }
    }

    @Test
    public void testAllErrorCodesAreDistinct() {
        assertThat(Problems.MISSING_ELEMENT_VARIABLE).isNotEqualTo(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE);
        assertThat(Problems.MISSING_ELEMENT_VARIABLE).isNotEqualTo(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(Problems.MISSING_ELEMENT_VARIABLE).isNotEqualTo(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(Problems.MISSING_ELEMENT_VARIABLE).isNotEqualTo(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT);
        assertThat(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE).isNotEqualTo(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE).isNotEqualTo(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE).isNotEqualTo(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT);
        assertThat(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE).isNotEqualTo(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE).isNotEqualTo(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT);
        assertThat(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE).isNotEqualTo(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT);
    }

    @Test
    public void testValidWithCandidateUsersReferencingElementVariable() {
        UserTask userTask = createMIUserTask("miTask", "userList", "user");
        userTask.getCandidateUsers().add("${user}");

        BpmnModel bpmnModel = createBpmnModel("validCandidateUsers", userTask);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).isEmpty();
    }

    @Test
    public void testValidWithCandidateGroupsReferencingElementVariable() {
        UserTask userTask = createMIUserTask("miTask", "groupList", "group");
        userTask.getCandidateGroups().add("${group}");

        BpmnModel bpmnModel = createBpmnModel("validCandidateGroups", userTask);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).isEmpty();
    }

    @Test
    public void testValidWithLoopCardinalityOnly() {
        UserTask userTask = new UserTask();
        userTask.setId("miTask");
        userTask.setName("Loop Cardinality Task");
        userTask.setAssignee("kermit");

        MultiInstanceLoopCharacteristics mi = new MultiInstanceLoopCharacteristics();
        mi.setSequential(false);
        mi.setLoopCardinality("5");
        userTask.setLoopCharacteristics(mi);

        BpmnModel bpmnModel = createBpmnModel("loopCardinalityOnly", userTask);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).isEmpty();
    }

    @Test
    public void testAssigneeUsingElementVariableProperty() {
        UserTask userTask = createMIUserTask("miTask", "userList", "user");
        userTask.setAssignee("${user.name}");

        BpmnModel bpmnModel = createBpmnModel("assigneeWithProperty", userTask);

        List<ValidationError> errors = processValidator.validate(bpmnModel);
        List<ValidationError> miErrors = filterMultiInstanceErrors(errors);
        assertThat(miErrors).isEmpty();
    }

    protected BpmnModel createBpmnModelWithMIUserTask(String processId, String taskId,
            String collection, String elementVariable, String assignee,
            List<String> candidateUsers, List<String> candidateGroups, String loopCardinality) {

        UserTask userTask = new UserTask();
        userTask.setId(taskId);
        userTask.setName("Multi Instance Task");

        if (assignee != null) {
            userTask.setAssignee(assignee);
        }
        if (candidateUsers != null) {
            userTask.setCandidateUsers(new ArrayList<>(candidateUsers));
        }
        if (candidateGroups != null) {
            userTask.setCandidateGroups(new ArrayList<>(candidateGroups));
        }

        MultiInstanceLoopCharacteristics mi = new MultiInstanceLoopCharacteristics();
        mi.setSequential(false);
        if (collection != null) {
            mi.setInputDataItem(collection);
        }
        if (elementVariable != null) {
            mi.setElementVariable(elementVariable);
        }
        if (loopCardinality != null) {
            mi.setLoopCardinality(loopCardinality);
        }
        userTask.setLoopCharacteristics(mi);

        return createBpmnModel(processId, userTask);
    }

    protected UserTask createMIUserTask(String taskId, String collection, String elementVariable) {
        UserTask userTask = new UserTask();
        userTask.setId(taskId);
        userTask.setName("Multi Instance Task");

        MultiInstanceLoopCharacteristics mi = new MultiInstanceLoopCharacteristics();
        mi.setSequential(false);
        mi.setInputDataItem(collection);
        mi.setElementVariable(elementVariable);
        userTask.setLoopCharacteristics(mi);

        return userTask;
    }

    protected BpmnModel createBpmnModel(String processId, UserTask userTask) {
        BpmnModel bpmnModel = new BpmnModel();
        Process process = new Process();
        process.setId(processId);
        process.setExecutable(true);
        bpmnModel.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        process.addFlowElement(userTask);

        EndEvent end = new EndEvent();
        end.setId("end");
        process.addFlowElement(end);

        SequenceFlow flow1 = new SequenceFlow();
        flow1.setId("flow1");
        flow1.setSourceRef("start");
        flow1.setTargetRef(userTask.getId());
        process.addFlowElement(flow1);

        SequenceFlow flow2 = new SequenceFlow();
        flow2.setId("flow2");
        flow2.setSourceRef(userTask.getId());
        flow2.setTargetRef("end");
        process.addFlowElement(flow2);

        return bpmnModel;
    }

    protected List<ValidationError> filterMultiInstanceErrors(List<ValidationError> errors) {
        List<ValidationError> miErrors = new ArrayList<>();
        for (ValidationError error : errors) {
            if (error.getProblem().equals(Problems.MISSING_ELEMENT_VARIABLE)
                    || error.getProblem().equals(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE)
                    || error.getProblem().equals(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE)
                    || error.getProblem().equals(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE)
                    || error.getProblem().equals(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT)) {
                miErrors.add(error);
            }
        }
        return miErrors;
    }
}

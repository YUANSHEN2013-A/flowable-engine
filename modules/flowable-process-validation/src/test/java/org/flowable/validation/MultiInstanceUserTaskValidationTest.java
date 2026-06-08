package org.flowable.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.common.engine.api.io.InputStreamProvider;
import org.flowable.validation.validator.Problems;
import org.junit.jupiter.api.Test;

public class MultiInstanceUserTaskValidationTest {

    private ProcessValidator processValidator = new ProcessValidatorFactory().createDefaultProcessValidator();

    protected BpmnModel readBpmnModel(String resource) {
        InputStreamProvider provider = () -> getClass().getClassLoader().getResourceAsStream(resource);
        return new BpmnXMLConverter().convertToBpmnModel(provider, true, false);
    }

    @Test
    public void testValidMultiInstance() {
        BpmnModel bpmnModel = readBpmnModel("org/flowable/validation/valid-multi-instance-user-task.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(bpmnModel);
        assertThat(errors).isEmpty();
    }

    @Test
    public void testMissingElementVariable() {
        BpmnModel bpmnModel = readBpmnModel("org/flowable/validation/missing-element-variable.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(bpmnModel);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.MISSING_ELEMENT_VARIABLE);
        assertThat(errors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testInvalidAssigneeExpression() {
        BpmnModel bpmnModel = readBpmnModel("org/flowable/validation/invalid-assignee-expression.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(bpmnModel);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE);
        assertThat(errors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testInvalidCandidateUsersExpression() {
        BpmnModel bpmnModel = readBpmnModel("org/flowable/validation/invalid-candidate-users-expression.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(bpmnModel);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(errors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testInvalidCandidateGroupsExpression() {
        BpmnModel bpmnModel = readBpmnModel("org/flowable/validation/invalid-candidate-groups-expression.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(bpmnModel);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(errors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testCollectionLoopcardinalityConflict() {
        BpmnModel bpmnModel = readBpmnModel("org/flowable/validation/collection-loopcardinality-conflict.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(bpmnModel);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT);
        assertThat(errors.get(0).getActivityId()).isEqualTo("miTask");
    }

    @Test
    public void testRegularUserTaskNotAffected() {
        BpmnModel bpmnModel = readBpmnModel("org/flowable/validation/regular-user-task.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(bpmnModel);
        assertThat(errors).isEmpty();
    }
}

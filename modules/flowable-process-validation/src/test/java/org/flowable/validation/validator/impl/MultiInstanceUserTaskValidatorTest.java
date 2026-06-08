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
package org.flowable.validation.validator.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ValidationError;
import org.flowable.validation.validator.Problems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MultiInstanceUserTaskValidatorTest {

    protected ProcessValidator processValidator;

    @BeforeEach
    void createProcessValidator() {
        processValidator = new ProcessValidatorFactory().createDefaultProcessValidator();
    }

    protected BpmnModel readXMLFile(String resource) {
        return new BpmnXMLConverter().convertToBpmnModel(
                () -> getClass().getClassLoader().getResourceAsStream(resource), true, false);
    }

    protected List<ValidationError> validate(String resource) {
        BpmnModel model = readXMLFile(resource);
        return processValidator.validate(model);
    }

    @Test
    public void testValidMultiInstanceUserTask() {
        List<ValidationError> errors = validate("valid-multi-instance-user-task.bpmn20.xml");
        assertThat(errors).isEmpty();
    }

    @Test
    public void testMissingElementVariable() {
        List<ValidationError> errors = validate("missing-element-variable.bpmn20.xml");
        assertThat(errors).hasSize(1);
        assertThat(errors)
                .extracting(ValidationError::getProblem, ValidationError::getActivityId)
                .containsExactly(tuple(Problems.MULTI_INSTANCE_USER_TASK_MISSING_ELEMENT_VARIABLE, "multiUserTask"));
    }

    @Test
    public void testInvalidAssigneeExpression() {
        List<ValidationError> errors = validate("invalid-assignee-expression.bpmn20.xml");
        assertThat(errors).hasSize(1);
        assertThat(errors)
                .extracting(ValidationError::getProblem, ValidationError::getActivityId)
                .containsExactly(tuple(Problems.MULTI_INSTANCE_USER_TASK_ASSIGNEE_NOT_USING_ELEMENT_VARIABLE, "multiUserTask"));
    }

    @Test
    public void testInvalidCandidateUsersExpression() {
        List<ValidationError> errors = validate("invalid-candidate-users-expression.bpmn20.xml");
        assertThat(errors).hasSize(1);
        assertThat(errors)
                .extracting(ValidationError::getProblem, ValidationError::getActivityId)
                .containsExactly(tuple(Problems.MULTI_INSTANCE_USER_TASK_CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE, "multiUserTask"));
    }

    @Test
    public void testInvalidCandidateGroupsExpression() {
        List<ValidationError> errors = validate("invalid-candidate-groups-expression.bpmn20.xml");
        assertThat(errors).hasSize(1);
        assertThat(errors)
                .extracting(ValidationError::getProblem, ValidationError::getActivityId)
                .containsExactly(tuple(Problems.MULTI_INSTANCE_USER_TASK_CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE, "multiUserTask"));
    }

    @Test
    public void testCollectionAndLoopCardinalityConflict() {
        List<ValidationError> errors = validate("collection-loopcardinality-conflict.bpmn20.xml");
        assertThat(errors).hasSize(1);
        assertThat(errors)
                .extracting(ValidationError::getProblem, ValidationError::getActivityId)
                .containsExactly(tuple(Problems.MULTI_INSTANCE_USER_TASK_COLLECTION_AND_LOOPCARDINALITY_CONFLICT, "multiUserTask"));
    }

    @Test
    public void testNonMultiInstanceUserTaskNotAffected() {
        BpmnModel model = readXMLFile("valid-multi-instance-user-task.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(model);
        assertThat(errors).isEmpty();
    }

    @Test
    public void testExistingValidationRulesNotAffected() {
        BpmnModel model = readXMLFile("valid-multi-instance-user-task.bpmn20.xml");
        List<ValidationError> errors = processValidator.validate(model);
        assertThat(errors).isEmpty();
    }
}
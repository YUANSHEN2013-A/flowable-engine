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
package org.flowable.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.validation.validator.Problems;
import org.junit.jupiter.api.Test;

/**
 * @author Flowable
 */
class MultiInstanceUserTaskValidatorTest {

    @Test
    void testValidMultiInstanceUserTask() {
        BpmnModel model = loadBpmnModel("valid-multi-instance-user-task.bpmn20.xml");
        ProcessValidator validator = new ProcessValidatorFactory().createDefaultProcessValidator();
        List<ValidationError> errors = validator.validate(model);
        assertThat(errors).isEmpty();
    }

    @Test
    void testMissingElementVariable() {
        BpmnModel model = loadBpmnModel("missing-element-variable.bpmn20.xml");
        ProcessValidator validator = new ProcessValidatorFactory().createDefaultProcessValidator();
        List<ValidationError> errors = validator.validate(model);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.MISSING_ELEMENT_VARIABLE);
        assertThat(errors.get(0).getActivityId()).isEqualTo("multiInstanceTask");
    }

    @Test
    void testInvalidAssigneeExpression() {
        BpmnModel model = loadBpmnModel("invalid-assignee-expression.bpmn20.xml");
        ProcessValidator validator = new ProcessValidatorFactory().createDefaultProcessValidator();
        List<ValidationError> errors = validator.validate(model);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE);
        assertThat(errors.get(0).getActivityId()).isEqualTo("multiInstanceTask");
    }

    @Test
    void testInvalidCandidateUsersExpression() {
        BpmnModel model = loadBpmnModel("invalid-candidate-users-expression.bpmn20.xml");
        ProcessValidator validator = new ProcessValidatorFactory().createDefaultProcessValidator();
        List<ValidationError> errors = validator.validate(model);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(errors.get(0).getActivityId()).isEqualTo("multiInstanceTask");
    }

    @Test
    void testInvalidCandidateGroupsExpression() {
        BpmnModel model = loadBpmnModel("invalid-candidate-groups-expression.bpmn20.xml");
        ProcessValidator validator = new ProcessValidatorFactory().createDefaultProcessValidator();
        List<ValidationError> errors = validator.validate(model);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE);
        assertThat(errors.get(0).getActivityId()).isEqualTo("multiInstanceTask");
    }

    @Test
    void testCollectionAndLoopCardinalityConflict() {
        BpmnModel model = loadBpmnModel("collection-loopcardinality-conflict.bpmn20.xml");
        ProcessValidator validator = new ProcessValidatorFactory().createDefaultProcessValidator();
        List<ValidationError> errors = validator.validate(model);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getProblem()).isEqualTo(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT);
        assertThat(errors.get(0).getActivityId()).isEqualTo("multiInstanceTask");
    }

    protected BpmnModel loadBpmnModel(String resourceName) {
        BpmnXMLConverter converter = new BpmnXMLConverter();
        return converter.convertToBpmnModel(new InputStreamProvider() {
            @Override
            public InputStream getInputStream() {
                return getClass().getClassLoader().getResourceAsStream("org/flowable/validation/" + resourceName);
            }
        }, false, false);
    }
}

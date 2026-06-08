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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ValidationError;
import org.flowable.validation.validator.Problems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultiInstanceUserTaskValidatorTest {

    private static final String RESOURCE_PATH = "org/flowable/validation/validator/impl/";
    private static final String MULTI_INSTANCE_USER_TASK_ID = "miUserTask";
    private static final String REGULAR_USER_TASK_ID = "regularUserTask";

    private ProcessValidator processValidator;

    @BeforeEach
    void setUp() {
        processValidator = new ProcessValidatorFactory().createDefaultProcessValidator();
    }

    @Test
    void validMultiInstanceUserTaskPassesValidation() throws Exception {
        List<ValidationError> errors = validateResource("valid-multi-instance-user-task.bpmn20.xml");

        assertThat(errors).isEmpty();
    }

    @Test
    void missingElementVariableTriggersDedicatedError() throws Exception {
        assertSingleError("missing-element-variable.bpmn20.xml", Problems.MISSING_ELEMENT_VARIABLE, MULTI_INSTANCE_USER_TASK_ID);
    }

    @Test
    void invalidAssigneeExpressionTriggersDedicatedError() throws Exception {
        assertSingleError("invalid-assignee-expression.bpmn20.xml", Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE, MULTI_INSTANCE_USER_TASK_ID);
    }

    @Test
    void invalidCandidateUsersExpressionTriggersDedicatedError() throws Exception {
        assertSingleError("invalid-candidate-users-expression.bpmn20.xml", Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE, MULTI_INSTANCE_USER_TASK_ID);
    }

    @Test
    void invalidCandidateGroupsExpressionTriggersDedicatedError() throws Exception {
        assertSingleError("invalid-candidate-groups-expression.bpmn20.xml", Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE, MULTI_INSTANCE_USER_TASK_ID);
    }

    @Test
    void collectionAndLoopCardinalityConflictTriggersDedicatedError() throws Exception {
        assertSingleError("collection-loopcardinality-conflict.bpmn20.xml", Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT, MULTI_INSTANCE_USER_TASK_ID);
    }

    @Test
    void nonMultiInstanceUserTaskIsNotAffected() throws Exception {
        List<ValidationError> errors = validateXml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="Examples">
              <process id="nonMultiInstanceProcess" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="flow1" sourceRef="start" targetRef="regularUserTask"/>
                <userTask id="regularUserTask"
                          flowable:assignee="${owner}"
                          flowable:candidateUsers="${candidateUsers}"
                          flowable:candidateGroups="${candidateGroups}"/>
                <sequenceFlow id="flow2" sourceRef="regularUserTask" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """);

        assertThat(errors).isEmpty();
    }

    @Test
    void existingValidationRulesAreNotAffected() throws Exception {
        List<ValidationError> errors = validateXml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="Examples">
              <process id="existingValidationProcess" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="flow1" sourceRef="start" targetRef="regularUserTask"/>
                <userTask id="regularUserTask">
                  <extensionElements>
                    <flowable:taskListener class="org.test.Listener"/>
                  </extensionElements>
                </userTask>
                <sequenceFlow id="flow2" sourceRef="regularUserTask" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """);

        assertThat(errors)
                .hasSize(1)
                .extracting(ValidationError::getProblem, ValidationError::getActivityId)
                .containsExactly(tuple(Problems.USER_TASK_LISTENER_MISSING_EVENT, REGULAR_USER_TASK_ID));
    }

    private void assertSingleError(String resourceName, String problem, String activityId) throws Exception {
        List<ValidationError> errors = validateResource(resourceName);

        assertThat(errors)
                .hasSize(1)
                .extracting(ValidationError::getProblem, ValidationError::getActivityId)
                .containsExactly(tuple(problem, activityId));
    }

    private List<ValidationError> validateResource(String resourceName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH + resourceName)) {
            assertThat(inputStream).isNotNull();
            return processValidator.validate(readModel(inputStream));
        }
    }

    private List<ValidationError> validateXml(String xml) throws Exception {
        return processValidator.validate(readModel(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }

    private BpmnModel readModel(InputStream inputStream) throws Exception {
        XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
        try (InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            XMLStreamReader xmlStreamReader = xmlInputFactory.createXMLStreamReader(inputStreamReader);
            return new BpmnXMLConverter().convertToBpmnModel(xmlStreamReader);
        }
    }

}

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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.flowable.validation.ProcessValidationContext;
import org.flowable.validation.validator.Problems;
import org.flowable.validation.validator.ProcessLevelValidator;

public class MultiInstanceUserTaskValidator extends ProcessLevelValidator {

    @Override
    protected void executeValidation(BpmnModel bpmnModel, Process process, ProcessValidationContext validationContext) {
        List<UserTask> userTasks = process.findFlowElementsOfType(UserTask.class);
        for (UserTask userTask : userTasks) {
            validateMultiInstanceUserTask(process, userTask, validationContext);
        }
    }

    protected void validateMultiInstanceUserTask(Process process, UserTask userTask, ProcessValidationContext validationContext) {
        MultiInstanceLoopCharacteristics loopCharacteristics = userTask.getLoopCharacteristics();
        if (loopCharacteristics == null) {
            return;
        }

        boolean collectionConfigured = isCollectionConfigured(loopCharacteristics);
        if (!collectionConfigured) {
            return;
        }

        if (StringUtils.isBlank(loopCharacteristics.getElementVariable())) {
            validationContext.addError(Problems.MISSING_ELEMENT_VARIABLE, process, userTask, loopCharacteristics,
                    "Element variable is required when collection is configured on a multi-instance user task");
            return;
        }

        if (StringUtils.isNotBlank(loopCharacteristics.getLoopCardinality())) {
            validationContext.addError(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT, process, userTask, loopCharacteristics,
                    "Collection and loopCardinality cannot be configured together on a multi-instance user task");
        }

        String elementVariable = loopCharacteristics.getElementVariable();

        if (isExpression(userTask.getAssignee()) && !usesElementVariable(userTask.getAssignee(), elementVariable)) {
            validationContext.addError(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                    "Assignee expression must reference the multi-instance element variable");
        }

        if (hasInvalidExpression(userTask.getCandidateUsers(), elementVariable)) {
            validationContext.addError(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                    "Candidate users expression must reference the multi-instance element variable");
        }

        if (hasInvalidExpression(userTask.getCandidateGroups(), elementVariable)) {
            validationContext.addError(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                    "Candidate groups expression must reference the multi-instance element variable");
        }
    }

    protected boolean isCollectionConfigured(MultiInstanceLoopCharacteristics loopCharacteristics) {
        return StringUtils.isNotBlank(loopCharacteristics.getInputDataItem()) || StringUtils.isNotBlank(loopCharacteristics.getCollectionString());
    }

    protected boolean hasInvalidExpression(List<String> expressions, String elementVariable) {
        for (String expression : expressions) {
            if (isExpression(expression) && !usesElementVariable(expression, elementVariable)) {
                return true;
            }
        }
        return false;
    }

    protected boolean isExpression(String value) {
        return StringUtils.isNotBlank(value)
                && (StringUtils.contains(value, "${") || StringUtils.contains(value, "#{"));
    }

    protected boolean usesElementVariable(String expression, String elementVariable) {
        return StringUtils.contains(expression, elementVariable);
    }

}
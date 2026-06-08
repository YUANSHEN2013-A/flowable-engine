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
            MultiInstanceLoopCharacteristics loopCharacteristics = userTask.getLoopCharacteristics();
            if (loopCharacteristics == null) {
                continue;
            }

            String collectionString = loopCharacteristics.getCollectionString();
            String elementVariable = loopCharacteristics.getElementVariable();
            String loopCardinality = loopCharacteristics.getLoopCardinality();

            if (collectionString != null && !collectionString.isEmpty()) {
                if (elementVariable == null || elementVariable.isEmpty()) {
                    validationContext.addError(
                            Problems.MULTI_INSTANCE_USER_TASK_MISSING_ELEMENT_VARIABLE,
                            process, userTask,
                            "multiInstanceUserTask 'elementVariable' is mandatory when 'collection' is configured on user task '" + userTask.getId() + "'");
                } else {
                    validateAssigneeExpression(userTask, elementVariable, process, validationContext);
                    validateCandidateUsersExpression(userTask, elementVariable, process, validationContext);
                    validateCandidateGroupsExpression(userTask, elementVariable, process, validationContext);
                }
            }

            if (collectionString != null && !collectionString.isEmpty()
                    && loopCardinality != null && !loopCardinality.isEmpty()) {
                validationContext.addError(
                        Problems.MULTI_INSTANCE_USER_TASK_COLLECTION_AND_LOOPCARDINALITY_CONFLICT,
                        process, userTask,
                        "multiInstanceUserTask 'collection' and 'loopCardinality' cannot both be configured on user task '" + userTask.getId() + "'");
            }
        }
    }

    protected void validateAssigneeExpression(UserTask userTask, String elementVariable, Process process,
            ProcessValidationContext validationContext) {
        String assignee = userTask.getAssignee();
        if (assignee != null && !assignee.isEmpty()) {
            if (!referencesElementVariable(assignee, elementVariable)) {
                validationContext.addError(
                        Problems.MULTI_INSTANCE_USER_TASK_ASSIGNEE_NOT_USING_ELEMENT_VARIABLE,
                        process, userTask,
                        "multiInstanceUserTask 'assignee' expression must reference the elementVariable '" + elementVariable + "' on user task '" + userTask.getId() + "'");
            }
        }
    }

    protected void validateCandidateUsersExpression(UserTask userTask, String elementVariable, Process process,
            ProcessValidationContext validationContext) {
        List<String> candidateUsers = userTask.getCandidateUsers();
        if (candidateUsers != null) {
            for (String candidateUser : candidateUsers) {
                if (candidateUser != null && !candidateUser.isEmpty()) {
                    if (!referencesElementVariable(candidateUser, elementVariable)) {
                        validationContext.addError(
                                Problems.MULTI_INSTANCE_USER_TASK_CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE,
                                process, userTask,
                                "multiInstanceUserTask 'candidateUsers' expression must reference the elementVariable '" + elementVariable + "' on user task '" + userTask.getId() + "'");
                        break;
                    }
                }
            }
        }
    }

    protected void validateCandidateGroupsExpression(UserTask userTask, String elementVariable, Process process,
            ProcessValidationContext validationContext) {
        List<String> candidateGroups = userTask.getCandidateGroups();
        if (candidateGroups != null) {
            for (String candidateGroup : candidateGroups) {
                if (candidateGroup != null && !candidateGroup.isEmpty()) {
                    if (!referencesElementVariable(candidateGroup, elementVariable)) {
                        validationContext.addError(
                                Problems.MULTI_INSTANCE_USER_TASK_CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE,
                                process, userTask,
                                "multiInstanceUserTask 'candidateGroups' expression must reference the elementVariable '" + elementVariable + "' on user task '" + userTask.getId() + "'");
                        break;
                    }
                }
            }
        }
    }

    protected boolean referencesElementVariable(String expression, String elementVariable) {
        if (expression == null || elementVariable == null) {
            return false;
        }
        String refPattern = "${" + elementVariable;
        return expression.contains(refPattern);
    }
}
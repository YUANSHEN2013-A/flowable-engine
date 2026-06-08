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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.flowable.validation.ProcessValidationContext;
import org.flowable.validation.validator.Problems;
import org.flowable.validation.validator.ProcessLevelValidator;

/**
 * @author Flowable
 */
public class MultiInstanceUserTaskValidator extends ProcessLevelValidator {

    @Override
    protected void executeValidation(BpmnModel bpmnModel, Process process, ProcessValidationContext validationContext) {
        List<UserTask> userTasks = process.findFlowElementsOfType(UserTask.class);
        for (UserTask userTask : userTasks) {
            if (userTask.hasMultiInstanceLoopCharacteristics()) {
                validateMultiInstanceUserTask(userTask, process, validationContext);
            }
        }
    }

    protected void validateMultiInstanceUserTask(UserTask userTask, Process process, ProcessValidationContext validationContext) {
        MultiInstanceLoopCharacteristics loopCharacteristics = userTask.getLoopCharacteristics();
        
        // Rule 5: collection and loopCardinality should not be configured together
        if (hasValue(loopCharacteristics.getCollectionString()) && hasValue(loopCharacteristics.getLoopCardinality())) {
            addError(validationContext, Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT, process, userTask,
                    "Collection and loop cardinality cannot be configured together");
        }
        
        // Only check the following rules if collection is configured
        if (hasValue(loopCharacteristics.getCollectionString())) {
            // Rule 1: collection configured must have elementVariable
            if (!hasValue(loopCharacteristics.getElementVariable())) {
                addError(validationContext, Problems.MISSING_ELEMENT_VARIABLE, process, userTask,
                        "Element variable is required when collection is configured");
                return; // No need to check further if element variable is missing
            }
            
            String elementVariable = loopCharacteristics.getElementVariable();
            
            // Rule 2: assignee expression must reference elementVariable
            if (hasValue(userTask.getAssignee())) {
                if (!expressionUsesVariable(userTask.getAssignee(), elementVariable)) {
                    addError(validationContext, Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                            "Assignee expression must reference the element variable: " + elementVariable);
                }
            }
            
            // Rule 3: candidateUsers expressions must reference elementVariable
            if (userTask.getCandidateUsers() != null && !userTask.getCandidateUsers().isEmpty()) {
                boolean allValid = true;
                for (String candidateUser : userTask.getCandidateUsers()) {
                    if (!expressionUsesVariable(candidateUser, elementVariable)) {
                        allValid = false;
                        break;
                    }
                }
                if (!allValid) {
                    addError(validationContext, Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                            "Candidate users expressions must reference the element variable: " + elementVariable);
                }
            }
            
            // Rule 4: candidateGroups expressions must reference elementVariable
            if (userTask.getCandidateGroups() != null && !userTask.getCandidateGroups().isEmpty()) {
                boolean allValid = true;
                for (String candidateGroup : userTask.getCandidateGroups()) {
                    if (!expressionUsesVariable(candidateGroup, elementVariable)) {
                        allValid = false;
                        break;
                    }
                }
                if (!allValid) {
                    addError(validationContext, Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                            "Candidate groups expressions must reference the element variable: " + elementVariable);
                }
            }
        }
    }

    protected boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }

    protected boolean expressionUsesVariable(String expression, String variableName) {
        if (!hasValue(expression) || !hasValue(variableName)) {
            return true;
        }
        
        // Check for variable references in different expression formats
        // Support ${variable}, #{variable}, ${variable.property}, ${variable.method()}, etc.
        String regex = "\\$\\{.*?" + Pattern.quote(variableName) + ".*?\\}|#\\{.*?" + Pattern.quote(variableName) + ".*?\\}";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(expression);
        
        if (matcher.find()) {
            return true;
        }
        
        // Also check for simple variable usage in UEL expressions without ${} or #{}
        // (though Flowable typically uses ${} or #{})
        return expression.equals(variableName) || 
               expression.startsWith(variableName + ".") || 
               expression.contains(" " + variableName + " ");
    }
}

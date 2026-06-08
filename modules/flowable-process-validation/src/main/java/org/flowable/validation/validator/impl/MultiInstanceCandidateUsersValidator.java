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

public class MultiInstanceCandidateUsersValidator extends ProcessLevelValidator {

    @Override
    protected void executeValidation(BpmnModel bpmnModel, Process process, ProcessValidationContext validationContext) {
        List<UserTask> userTasks = process.findFlowElementsOfType(UserTask.class);
        for (UserTask userTask : userTasks) {
            MultiInstanceLoopCharacteristics mi = userTask.getLoopCharacteristics();
            if (mi == null) {
                continue;
            }
            String elementVariable = mi.getElementVariable();
            if (StringUtils.isNotEmpty(elementVariable) && userTask.getCandidateUsers() != null) {
                for (String candidateUser : userTask.getCandidateUsers()) {
                    if (StringUtils.isNotEmpty(candidateUser) && !expressionReferencesVariable(candidateUser, elementVariable)) {
                        validationContext.addError(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                                "Candidate users expression must reference the elementVariable '" + elementVariable + "'");
                    }
                }
            }
        }
    }

    protected boolean expressionReferencesVariable(String expression, String variableName) {
        String normalized = expression.replace("${", "").replace("#{", "").replace("}", "").trim();
        return normalized.equals(variableName) || normalized.startsWith(variableName + ".") || normalized.contains(variableName);
    }
}

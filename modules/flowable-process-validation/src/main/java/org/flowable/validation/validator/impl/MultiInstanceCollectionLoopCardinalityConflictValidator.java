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

public class MultiInstanceCollectionLoopCardinalityConflictValidator extends ProcessLevelValidator {

    @Override
    protected void executeValidation(BpmnModel bpmnModel, Process process, ProcessValidationContext validationContext) {
        List<UserTask> userTasks = process.findFlowElementsOfType(UserTask.class);
        for (UserTask userTask : userTasks) {
            MultiInstanceLoopCharacteristics mi = userTask.getLoopCharacteristics();
            if (mi == null) {
                continue;
            }
            boolean hasCollection = StringUtils.isNotEmpty(mi.getInputDataItem()) || StringUtils.isNotEmpty(mi.getCollectionString());
            boolean hasLoopCardinality = StringUtils.isNotEmpty(mi.getLoopCardinality());
            if (hasCollection && hasLoopCardinality) {
                validationContext.addError(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT, process, userTask,
                        "Both 'collection' and 'loopCardinality' are configured on a multi-instance user task, which is not allowed");
            }
        }
    }
}

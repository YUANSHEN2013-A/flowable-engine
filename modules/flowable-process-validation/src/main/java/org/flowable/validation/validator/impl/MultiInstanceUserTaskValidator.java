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
            MultiInstanceLoopCharacteristics loopCharacteristics = userTask.getLoopCharacteristics();
            if (loopCharacteristics != null) {
                boolean hasCollection = StringUtils.isNotEmpty(loopCharacteristics.getInputDataItem())
                        || StringUtils.isNotEmpty(loopCharacteristics.getCollectionString());
                boolean hasLoopCardinality = StringUtils.isNotEmpty(loopCharacteristics.getLoopCardinality());
                String elementVariable = loopCharacteristics.getElementVariable();

                if (hasCollection && hasLoopCardinality) {
                    validationContext.addError(Problems.COLLECTION_AND_LOOPCARDINALITY_CONFLICT, process, userTask,
                            "MultiInstance UserTask cannot have both collection and loopCardinality configured.");
                }

                if (hasCollection && StringUtils.isEmpty(elementVariable)) {
                    validationContext.addError(Problems.MISSING_ELEMENT_VARIABLE, process, userTask,
                            "MultiInstance UserTask with collection must have elementVariable configured.");
                }

                if (StringUtils.isNotEmpty(elementVariable)) {
                    String assignee = userTask.getAssignee();
                    if (StringUtils.isNotEmpty(assignee) && !assignee.contains(elementVariable)) {
                        validationContext.addError(Problems.ASSIGNEE_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                                "MultiInstance UserTask assignee must use elementVariable.");
                    }

                    List<String> candidateUsers = userTask.getCandidateUsers();
                    if (candidateUsers != null && !candidateUsers.isEmpty()) {
                        for (String candidateUser : candidateUsers) {
                            if (!candidateUser.contains(elementVariable)) {
                                validationContext.addError(Problems.CANDIDATE_USERS_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                                        "MultiInstance UserTask candidateUsers must use elementVariable.");
                                break;
                            }
                        }
                    }

                    List<String> candidateGroups = userTask.getCandidateGroups();
                    if (candidateGroups != null && !candidateGroups.isEmpty()) {
                        for (String candidateGroup : candidateGroups) {
                            if (!candidateGroup.contains(elementVariable)) {
                                validationContext.addError(Problems.CANDIDATE_GROUPS_NOT_USING_ELEMENT_VARIABLE, process, userTask,
                                        "MultiInstance UserTask candidateGroups must use elementVariable.");
                                break;
                            }
                        }
                    }
                }
            }
        }
    }
}

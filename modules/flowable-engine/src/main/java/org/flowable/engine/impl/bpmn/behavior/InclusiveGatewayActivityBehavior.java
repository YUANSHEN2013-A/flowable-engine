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
package org.flowable.engine.impl.bpmn.behavior;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.delegate.InactiveActivityBehavior;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityManager;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.ExecutionGraphUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the Inclusive Gateway/OR gateway/inclusive data-based gateway as defined in the BPMN specification.
 *
 * @author Tijs Rademakers
 * @author Tom Van Buskirk
 * @author Joram Barrez
 */
public class InclusiveGatewayActivityBehavior extends GatewayActivityBehavior implements InactiveActivityBehavior {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(InclusiveGatewayActivityBehavior.class.getName());

    @Override
    public void execute(DelegateExecution execution) {
        // The join in the inclusive gateway works as follows:
        // When an execution enters it, it is inactivated.
        // All the inactivated executions stay in the inclusive gateway
        // until ALL executions that CAN reach the inclusive gateway have reached it.
        //
        // This check is repeated on execution changes until the inactivated
        // executions leave the gateway.

        execution.inactivate();
        executeInclusiveGatewayLogic((ExecutionEntity) execution, false);
    }

    @Override
    public void executeInactive(ExecutionEntity executionEntity) {
        executeInclusiveGatewayLogic(executionEntity, true);
    }

    protected void executeInclusiveGatewayLogic(ExecutionEntity execution, boolean inactiveCheck) {
        CommandContext commandContext = Context.getCommandContext();
        ExecutionEntityManager executionEntityManager = CommandContextUtil.getExecutionEntityManager(commandContext);

        lockFirstParentScope(execution);

        DelegateExecution multiInstanceExecution = null;
        if (hasMultiInstanceParent((FlowNode) execution.getCurrentFlowElement())) {
            multiInstanceExecution = findMultiInstanceParentExecution(execution);
        }

        Collection<ExecutionEntity> allExecutions = executionEntityManager.findChildExecutionsByProcessInstanceId(execution.getProcessInstanceId());
        Iterator<ExecutionEntity> executionIterator = allExecutions.iterator();
        boolean oneExecutionCanReachGatewayInstance = false;
        while (!oneExecutionCanReachGatewayInstance && executionIterator.hasNext()) {
            ExecutionEntity executionEntity = executionIterator.next();
            if (!executionEntity.getActivityId().equals(execution.getCurrentActivityId())) {
                if (ExecutionGraphUtil.isReachable(execution.getProcessDefinitionId(), executionEntity.getActivityId(), execution.getCurrentActivityId())) {
                    if (isChildOfSameScopeExecution(executionEntity, execution, multiInstanceExecution)) {
                        oneExecutionCanReachGatewayInstance = true;
                        break;
                    }
                }
            } else if (executionEntity.isActive() && (executionEntity.getId().equals(execution.getId()) || isAsynchronousActivity(executionEntity))) {
                if (isChildOfSameScopeExecution(executionEntity, execution, multiInstanceExecution)) {
                    oneExecutionCanReachGatewayInstance = true;
                    break;
                }
            }
        }

        if (!inactiveCheck || !oneExecutionCanReachGatewayInstance) {
            CommandContextUtil.getActivityInstanceEntityManager(commandContext).recordActivityEnd(execution, null);
        }

        if (!oneExecutionCanReachGatewayInstance) {

            LOGGER.debug("Inclusive gateway cannot be reached by any execution and is activated");

            Collection<ExecutionEntity> executionsInGateway = executionEntityManager
                .findInactiveExecutionsByActivityIdAndProcessInstanceId(execution.getCurrentActivityId(), execution.getProcessInstanceId());
            for (ExecutionEntity executionEntityInGateway : executionsInGateway) {
                if (!executionEntityInGateway.getId().equals(execution.getId()) && isInSameScopeAs(executionEntityInGateway, execution, multiInstanceExecution)) {

                    if (!Objects.equals(executionEntityInGateway.getActivityId(), execution.getActivityId())) {
                        CommandContextUtil.getActivityInstanceEntityManager(commandContext).recordActivityEnd(executionEntityInGateway, null);
                    }

                    executionEntityManager.deleteExecutionAndRelatedData(executionEntityInGateway, null, false);
                }
            }

            CommandContextUtil.getAgenda(commandContext).planTakeOutgoingSequenceFlowsOperation(execution, true);
        }
    }

    protected boolean isAsynchronousActivity(ExecutionEntity executionEntity) {
        return executionEntity.getCurrentFlowElement() instanceof FlowNode && ((FlowNode) executionEntity.getCurrentFlowElement()).isAsynchronous();
    }

    protected boolean hasMultiInstanceParent(FlowNode flowNode) {
        boolean hasMultiInstanceParent = false;
        if (flowNode.getSubProcess() != null) {
            if (flowNode.getSubProcess().getLoopCharacteristics() != null) {
                hasMultiInstanceParent = true;
            } else {
                boolean hasNestedMultiInstanceParent = hasMultiInstanceParent(flowNode.getSubProcess());
                if (hasNestedMultiInstanceParent) {
                    hasMultiInstanceParent = true;
                }
            }
        }
        return hasMultiInstanceParent;
    }

    protected DelegateExecution findMultiInstanceParentExecution(DelegateExecution execution) {
        DelegateExecution multiInstanceExecution = null;
        DelegateExecution parentExecution = execution.getParent();
        if (parentExecution != null && parentExecution.getCurrentFlowElement() != null) {
            FlowElement flowElement = parentExecution.getCurrentFlowElement();
            if (flowElement instanceof Activity) {
                if (((Activity) flowElement).getLoopCharacteristics() != null) {
                    multiInstanceExecution = parentExecution;
                }
            }

            if (multiInstanceExecution == null) {
                DelegateExecution potentialMultiInstanceExecution = findMultiInstanceParentExecution(parentExecution);
                if (potentialMultiInstanceExecution != null) {
                    multiInstanceExecution = potentialMultiInstanceExecution;
                }
            }
        }
        return multiInstanceExecution;
    }

    protected boolean isChildOfSameScopeExecution(DelegateExecution executionEntity, DelegateExecution execution, DelegateExecution multiInstanceExecution) {
        if (multiInstanceExecution != null) {
            return isChildOfMultiInstanceExecution(executionEntity, multiInstanceExecution)
                    && isChildOfMultiInstanceExecution(execution, multiInstanceExecution);
        }
        return isInSameScopeAs(executionEntity, execution, null);
    }

    protected boolean isInSameScopeAs(DelegateExecution executionEntity, DelegateExecution execution, DelegateExecution multiInstanceExecution) {
        if (multiInstanceExecution != null) {
            return isChildOfMultiInstanceExecution(executionEntity, multiInstanceExecution)
                    && isChildOfMultiInstanceExecution(execution, multiInstanceExecution);
        }
        return executionEntity.getParentId().equals(execution.getParentId());
    }

    protected boolean isChildOfMultiInstanceExecution(DelegateExecution executionEntity, DelegateExecution multiInstanceExecution) {
        boolean isChild = false;
        DelegateExecution parentExecution = executionEntity.getParent();
        if (parentExecution != null) {
            if (parentExecution.getId().equals(multiInstanceExecution.getId())) {
                isChild = true;
            } else {
                boolean isNestedChild = isChildOfMultiInstanceExecution(parentExecution, multiInstanceExecution);
                if (isNestedChild) {
                    isChild = true;
                }
            }
        }
        return isChild;
    }
}

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

import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;

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

        Collection<ExecutionEntity> allExecutions = executionEntityManager.findChildExecutionsByProcessInstanceId(execution.getProcessInstanceId());
        Iterator<ExecutionEntity> executionIterator = allExecutions.iterator();
        boolean oneExecutionCanReachGatewayInstance = false;
        while (!oneExecutionCanReachGatewayInstance && executionIterator.hasNext()) {
            ExecutionEntity executionEntity = executionIterator.next();
            if (!executionEntity.getActivityId().equals(execution.getCurrentActivityId())) {
                if (ExecutionGraphUtil.isReachable(execution.getProcessDefinitionId(), executionEntity.getActivityId(), execution.getCurrentActivityId())) {
                    // Check if they are in the same execution hierarchy - i.e., they share a common scope ancestor
                    if (isInSameExecutionHierarchy(executionEntity, execution)) {
                        oneExecutionCanReachGatewayInstance = true;
                        break;
                    }
                }
            } else if (executionEntity.isActive() && (executionEntity.getId().equals(execution.getId()) || isAsynchronousActivity(executionEntity))) {
                // Special case: the execution has reached the inc gw, but the operation hasn't been executed yet for that execution
                oneExecutionCanReachGatewayInstance = true;
                break;
            }
        }

        // Is needed to set the endTime for all historic activity joins
        if (!inactiveCheck || !oneExecutionCanReachGatewayInstance) {
            CommandContextUtil.getActivityInstanceEntityManager(commandContext).recordActivityEnd(execution, null);
        }

        // If no execution can reach the gateway, the gateway activates and executes fork behavior
        if (!oneExecutionCanReachGatewayInstance) {

            LOGGER.debug("Inclusive gateway cannot be reached by any execution and is activated");

            // Kill all executions here (except the incoming)
            Collection<ExecutionEntity> executionsInGateway = executionEntityManager
                .findInactiveExecutionsByActivityIdAndProcessInstanceId(execution.getCurrentActivityId(), execution.getProcessInstanceId());
            for (ExecutionEntity executionEntityInGateway : executionsInGateway) {
                if (!executionEntityInGateway.getId().equals(execution.getId()) && isInSameExecutionHierarchy(executionEntityInGateway, execution)) {

                    if (!Objects.equals(executionEntityInGateway.getActivityId(), execution.getActivityId())) {
                        CommandContextUtil.getActivityInstanceEntityManager(commandContext).recordActivityEnd(executionEntityInGateway, null);
                    }

                    executionEntityManager.deleteExecutionAndRelatedData(executionEntityInGateway, null, false);
                }
            }

            // Leave
            CommandContextUtil.getAgenda(commandContext).planTakeOutgoingSequenceFlowsOperation(execution, true);
        }
    }
    
    /**
     * Check if two executions are in the same execution hierarchy.
     * This means they share a common ancestor that is either a scope execution or a multi-instance root.
     */
    protected boolean isInSameExecutionHierarchy(ExecutionEntity execution1, ExecutionEntity execution2) {
        if (execution1.getId().equals(execution2.getId())) {
            return true;
        }
        
        // Check if they have the same parent directly first for backward compatibility
        if (execution1.getParentId() != null && execution1.getParentId().equals(execution2.getParentId())) {
            return true;
        }
        
        // Build the ancestor chains for both executions
        java.util.Set<String> execution1Ancestors = new java.util.HashSet<>();
        ExecutionEntity current = execution1;
        while (current != null) {
            execution1Ancestors.add(current.getId());
            current = current.getParent();
        }
        
        // Check if execution2 or any of its ancestors are in execution1's ancestor chain
        current = execution2;
        while (current != null) {
            if (execution1Ancestors.contains(current.getId())) {
                return true;
            }
            current = current.getParent();
        }
        
        return false;
    }

    protected boolean isAsynchronousActivity(ExecutionEntity executionEntity) {
        return executionEntity.getCurrentFlowElement() instanceof FlowNode && ((FlowNode) executionEntity.getCurrentFlowElement()).isAsynchronous();
    }
}

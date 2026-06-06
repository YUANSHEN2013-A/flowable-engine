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
package org.flowable.engine.impl.agenda;

import java.util.ArrayList;
import java.util.List;

import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.CompensateEventDefinition;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.Gateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.BusinessError;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEventDispatcher;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.common.engine.impl.logging.LoggingSessionConstants;
import org.flowable.common.engine.impl.util.CollectionUtil;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.delegate.event.impl.FlowableEventBuilder;
import org.flowable.engine.impl.bpmn.behavior.BoundaryEventRegistryEventActivityBehavior;
import org.flowable.engine.impl.bpmn.helper.ErrorPropagation;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.delegate.ActivityBehavior;
import org.flowable.engine.impl.delegate.ActivityWithMigrationContextBehavior;
import org.flowable.engine.impl.jobexecutor.AsyncContinuationJobHandler;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityManager;
import org.flowable.engine.impl.util.BpmnLoggingSessionUtil;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.JobUtil;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;
import org.flowable.engine.impl.util.condition.ConditionUtil;
import org.flowable.engine.interceptor.MigrationContext;
import org.flowable.engine.logging.LogMDC;
import org.flowable.job.api.Job;
import org.flowable.job.service.JobService;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operation that takes the current {@link FlowElement} set on the {@link ExecutionEntity} and executes the associated {@link ActivityBehavior}. In the case of async, schedules a {@link Job}.
 * 
 * Also makes sure the {@link ExecutionListener} instances are called.
 * 
 * @author Joram Barrez
 * @author Tijs Rademakers
 */
public class ContinueProcessOperation extends AbstractOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContinueProcessOperation.class);

    protected boolean forceSynchronousOperation;
    protected boolean inCompensation;
    protected MigrationContext migrationContext;

    public ContinueProcessOperation(CommandContext commandContext, ExecutionEntity execution,
            boolean forceSynchronousOperation, boolean inCompensation, MigrationContext migrationContext) {

        super(commandContext, execution);
        this.forceSynchronousOperation = forceSynchronousOperation;
        this.inCompensation = inCompensation;
        this.migrationContext = migrationContext;
    }

    public ContinueProcessOperation(CommandContext commandContext, ExecutionEntity execution) {
        this(commandContext, execution, false, false, null);
    }

    @Override
    public void run() {
        FlowElement currentFlowElement = getCurrentFlowElement(execution);
        if (currentFlowElement instanceof FlowNode) {
            continueThroughFlowNode((FlowNode) currentFlowElement);
        } else if (currentFlowElement instanceof SequenceFlow) {
            continueThroughSequenceFlow((SequenceFlow) currentFlowElement);
        } else {
            throw new FlowableException("Programmatic error: no current flow element found or invalid type: " + currentFlowElement + ". For " + execution + ". Halting.");
        }
    }

    protected void executeProcessStartExecutionListeners() {
        org.flowable.bpmn.model.Process process = ProcessDefinitionUtil.getProcess(execution.getProcessDefinitionId());
        executeExecutionListeners(process, execution.getParent(), ExecutionListener.EVENTNAME_START);
    }

    protected void continueThroughFlowNode(FlowNode flowNode) {

        if (!(flowNode instanceof Gateway)) {
            ConditionUtil.clearExecutionForSequenceFlowEvaluation(execution);
        }

        execution.setActive(true);

        if (flowNode.getIncomingFlows() != null
                && flowNode.getIncomingFlows().size() == 0
                && flowNode.getSubProcess() == null) {

            executeProcessStartExecutionListeners();
        }

        if (!forceSynchronousOperation && flowNode instanceof SubProcess) {
            createChildExecutionForSubProcess((SubProcess) flowNode);
        }

        if (flowNode instanceof Activity && ((Activity) flowNode).hasMultiInstanceLoopCharacteristics()) {
            executeMultiInstanceSynchronous(flowNode);

        } else if (forceSynchronousOperation || !flowNode.isAsynchronous()) {
            executeSynchronous(flowNode);

        } else {
            executeAsynchronous(flowNode);
        }
    }

    protected void createChildExecutionForSubProcess(SubProcess subProcess) {
        ExecutionEntity parentScopeExecution = findFirstParentScopeExecution(execution);

        ExecutionEntity subProcessExecution = CommandContextUtil.getExecutionEntityManager(commandContext).createChildExecution(parentScopeExecution);
        subProcessExecution.setCurrentFlowElement(subProcess);
        subProcessExecution.setScope(true);

        CommandContextUtil.getExecutionEntityManager(commandContext).deleteRelatedDataForExecution(execution, null, false);
        CommandContextUtil.getExecutionEntityManager(commandContext).delete(execution);
        execution = subProcessExecution;
    }

    protected void executeSynchronous(FlowNode flowNode) {
        CommandContextUtil.getActivityInstanceEntityManager(commandContext).recordActivityStart(execution);

        if (CollectionUtil.isNotEmpty(flowNode.getExecutionListeners())) {
            try {
                executeExecutionListeners(flowNode, ExecutionListener.EVENTNAME_START);
            } catch (BusinessError businessError) {
                ErrorPropagation.propagateError(businessError, execution);
                return;
            }
        }

        List<ExecutionEntity> boundaryEventExecutions = null;
        List<BoundaryEvent> boundaryEvents = null;
        if (!inCompensation && flowNode instanceof Activity) {
            boundaryEvents = ((Activity) flowNode).getBoundaryEvents();
            if (CollectionUtil.isNotEmpty(boundaryEvents)) {
                boundaryEventExecutions = createBoundaryEvents(boundaryEvents, execution);
            }
        }

        ActivityBehavior activityBehavior = (ActivityBehavior) flowNode.getBehavior();

        if (activityBehavior != null) {
            executeActivityBehavior(activityBehavior, flowNode);
            executeBoundaryEvents(boundaryEventExecutions);
        } else {
            executeBoundaryEvents(boundaryEventExecutions);
            LOGGER.debug("No activityBehavior on activity '{}' with execution {}", flowNode.getId(), execution.getId());
            CommandContextUtil.getAgenda().planTakeOutgoingSequenceFlowsOperation(execution, true);
        }
    }

    protected void executeAsynchronous(FlowNode flowNode) {
        ProcessEngineConfigurationImpl processEngineConfiguration = CommandContextUtil.getProcessEngineConfiguration(commandContext);
        JobService jobService = processEngineConfiguration.getJobServiceConfiguration().getJobService();

        JobEntity job = JobUtil.createJob(execution, flowNode, AsyncContinuationJobHandler.TYPE, processEngineConfiguration);

        jobService.createAsyncJob(job, flowNode.isExclusive());
        jobService.scheduleAsyncJob(job);

        if (processEngineConfiguration.isLoggingSessionEnabled()) {
            BpmnLoggingSessionUtil.addAsyncActivityLoggingData("Created async job for " + flowNode.getId() + ", with job id " + job.getId(),
                            LoggingSessionConstants.TYPE_SERVICE_TASK_ASYNC_JOB, job, flowNode, execution);
        }
    }

    protected void executeMultiInstanceSynchronous(FlowNode flowNode) {

        if (!hasMultiInstanceRootExecution(execution, flowNode)) {
            execution = createMultiInstanceRootExecution(execution);
        }

        if (CollectionUtil.isNotEmpty(flowNode.getExecutionListeners())) {
            try {
                executeExecutionListeners(flowNode, ExecutionListener.EVENTNAME_START);
            } catch (BusinessError businessError) {
                ErrorPropagation.propagateError(businessError, execution);
               return;
            }
        }

        ActivityBehavior activityBehavior = (ActivityBehavior) flowNode.getBehavior();

        if (activityBehavior != null) {
            executeActivityBehavior(activityBehavior, flowNode);

            if (execution.isMultiInstanceRoot() && !execution.isDeleted() && !execution.isEnded()) {
                List<ExecutionEntity> boundaryEventExecutions = null;
                List<BoundaryEvent> boundaryEvents = null;
                if (!inCompensation && flowNode instanceof Activity) {
                    boundaryEvents = ((Activity) flowNode).getBoundaryEvents();
                    if (CollectionUtil.isNotEmpty(boundaryEvents)) {
                        boundaryEventExecutions = createBoundaryEvents(boundaryEvents, execution);
                    }
                }

                executeBoundaryEvents(boundaryEventExecutions);
            }

        } else {
            throw new FlowableException("Expected an activity behavior in flow node " + flowNode.getId() + " for " + execution);
        }
    }

    protected boolean hasMultiInstanceRootExecution(ExecutionEntity execution, FlowNode flowNode) {
        ExecutionEntity currentExecution = execution.getParent();
        while (currentExecution != null) {
            if (currentExecution.isMultiInstanceRoot() && flowNode.getId().equals(currentExecution.getActivityId())) {
                return true;
            }
            currentExecution = currentExecution.getParent();
        }
        return false;
    }

    protected ExecutionEntity createMultiInstanceRootExecution(ExecutionEntity execution) {
        ExecutionEntity parentExecution = execution.getParent();
        FlowElement flowElement = execution.getCurrentFlowElement();

        ExecutionEntityManager executionEntityManager = CommandContextUtil.getExecutionEntityManager();
        executionEntityManager.deleteRelatedDataForExecution(execution, null, false);
        executionEntityManager.delete(execution);

        ExecutionEntity multiInstanceRootExecution = executionEntityManager.createChildExecution(parentExecution);
        multiInstanceRootExecution.setCurrentFlowElement(flowElement);
        multiInstanceRootExecution.setMultiInstanceRoot(true);
        multiInstanceRootExecution.setActive(false);
        return multiInstanceRootExecution;
    }

    protected void executeActivityBehavior(ActivityBehavior activityBehavior, FlowNode flowNode) {
        LOGGER.debug("Executing activityBehavior {} on activity '{}' with execution {}", activityBehavior.getClass(), flowNode.getId(), execution.getId());

        ProcessEngineConfigurationImpl processEngineConfiguration = CommandContextUtil.getProcessEngineConfiguration();
        FlowableEventDispatcher eventDispatcher = null;
        if (processEngineConfiguration != null) {
            eventDispatcher = processEngineConfiguration.getEventDispatcher();
        }
        if (eventDispatcher != null && eventDispatcher.isEnabled()) {

            if (flowNode instanceof Activity && ((Activity) flowNode).hasMultiInstanceLoopCharacteristics()) {
                processEngineConfiguration.getEventDispatcher().dispatchEvent(
                        FlowableEventBuilder.createMultiInstanceActivityEvent(FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_STARTED, flowNode.getId(),
                                flowNode.getName(), execution.getId(), execution.getProcessInstanceId(), execution.getProcessDefinitionId(), flowNode), processEngineConfiguration.getEngineCfgKey());
            }
            else {
                processEngineConfiguration.getEventDispatcher().dispatchEvent(
                        FlowableEventBuilder.createActivityEvent(FlowableEngineEventType.ACTIVITY_STARTED, flowNode.getId(), flowNode.getName(), execution.getId(),
                                execution.getProcessInstanceId(), execution.getProcessDefinitionId(), flowNode), processEngineConfiguration.getEngineCfgKey());
            }
        }

        if (processEngineConfiguration.isLoggingSessionEnabled()) {
            BpmnLoggingSessionUtil.addExecuteActivityBehaviorLoggingData(LoggingSessionConstants.TYPE_ACTIVITY_BEHAVIOR_EXECUTE,
                            activityBehavior, flowNode, execution);
        }

        try {
            if (migrationContext != null && activityBehavior instanceof ActivityWithMigrationContextBehavior activityWithMigrationContextBehavior) {
                activityWithMigrationContextBehavior.execute(execution, migrationContext);
            } else {
                activityBehavior.execute(execution);
            }

        } catch (RuntimeException e) {
            if (LogMDC.isMDCEnabled()) {
                LogMDC.putMDCExecution(execution);
            }
            throw e;
        }
    }

    protected void continueThroughSequenceFlow(SequenceFlow sequenceFlow) {
        if (CollectionUtil.isNotEmpty(sequenceFlow.getExecutionListeners())) {
            try {
                executeExecutionListeners(sequenceFlow, ExecutionListener.EVENTNAME_START);
                executeExecutionListeners(sequenceFlow, ExecutionListener.EVENTNAME_TAKE);
                executeExecutionListeners(sequenceFlow, ExecutionListener.EVENTNAME_END);
            } catch (BusinessError businessError) {
                ErrorPropagation.propagateError(businessError, execution);
                return;
            }
        }

        ProcessEngineConfigurationImpl processEngineConfiguration = CommandContextUtil.getProcessEngineConfiguration();
        FlowableEventDispatcher eventDispatcher = null;
        if (processEngineConfiguration != null) {
            eventDispatcher = processEngineConfiguration.getEventDispatcher();
        }
        if (eventDispatcher != null && eventDispatcher.isEnabled()) {
            FlowElement sourceFlowElement = sequenceFlow.getSourceFlowElement();
            FlowElement targetFlowElement = sequenceFlow.getTargetFlowElement();
            processEngineConfiguration.getEventDispatcher().dispatchEvent(
                    FlowableEventBuilder.createSequenceFlowTakenEvent(
                            execution,
                            FlowableEngineEventType.SEQUENCEFLOW_TAKEN,
                            sequenceFlow.getId(),
                            sourceFlowElement != null ? sourceFlowElement.getId() : null,
                            sourceFlowElement != null ? sourceFlowElement.getName() : null,
                            sourceFlowElement != null ? sourceFlowElement.getClass().getName() : null,
                            sourceFlowElement != null ? ((FlowNode) sourceFlowElement).getBehavior() : null,
                            targetFlowElement != null ? targetFlowElement.getId() : null,
                            targetFlowElement != null ? targetFlowElement.getName() : null,
                            targetFlowElement != null ? targetFlowElement.getClass().getName() : null,
                            targetFlowElement != null ? ((FlowNode) targetFlowElement).getBehavior() : null), processEngineConfiguration.getEngineCfgKey());
        }

        CommandContextUtil.getActivityInstanceEntityManager(commandContext).recordSequenceFlowTaken(execution);

        FlowElement targetFlowElement = sequenceFlow.getTargetFlowElement();
        execution.setCurrentFlowElement(targetFlowElement);

        LOGGER.debug("Sequence flow '{}' encountered. Continuing process by following it using execution {}", sequenceFlow.getId(), execution.getId());

        execution.setActive(targetFlowElement instanceof FlowNode);
        agenda.planContinueProcessOperation(execution);
    }

    protected List<ExecutionEntity> createBoundaryEvents(List<BoundaryEvent> boundaryEvents, ExecutionEntity execution) {

        List<ExecutionEntity> boundaryEventExecutions = new ArrayList<>(boundaryEvents.size());

        for (BoundaryEvent boundaryEvent : boundaryEvents) {

            if (!(boundaryEvent.getBehavior() instanceof BoundaryEventRegistryEventActivityBehavior)) {
                if (CollectionUtil.isEmpty(boundaryEvent.getEventDefinitions())
                        || (boundaryEvent.getEventDefinitions().get(0) instanceof CompensateEventDefinition)) {
                    continue;
                }
            }

            ExecutionEntity childExecutionEntity = CommandContextUtil.getExecutionEntityManager(commandContext).createChildExecution(execution);
            childExecutionEntity.setParentId(execution.getId());
            childExecutionEntity.setCurrentFlowElement(boundaryEvent);
            childExecutionEntity.setScope(false);
            boundaryEventExecutions.add(childExecutionEntity);

            CommandContextUtil.getActivityInstanceEntityManager(commandContext).recordActivityStart(childExecutionEntity);

            ProcessEngineConfigurationImpl processEngineConfiguration = CommandContextUtil.getProcessEngineConfiguration(commandContext);
            if (processEngineConfiguration.isLoggingSessionEnabled()) {
                BpmnLoggingSessionUtil.addLoggingData(BpmnLoggingSessionUtil.getBoundaryCreateEventType(boundaryEvent),
                                "Creating boundary event (" + BpmnLoggingSessionUtil.getBoundaryEventType(boundaryEvent) +
                                ") for execution id " + childExecutionEntity.getId(), childExecutionEntity);
            }
        }

        return boundaryEventExecutions;
    }

    protected void executeBoundaryEvents(List<ExecutionEntity> boundaryEventExecutions) {
        if (!CollectionUtil.isEmpty(boundaryEventExecutions)) {

            for (ExecutionEntity boundaryEventExecution : boundaryEventExecutions) {
                BoundaryEvent boundaryEvent = (BoundaryEvent) boundaryEventExecution.getCurrentFlowElement();
                ActivityBehavior boundaryEventBehavior = ((ActivityBehavior) boundaryEvent.getBehavior());
                LOGGER.debug("Executing boundary event activityBehavior {} with execution {}", boundaryEventBehavior.getClass(), boundaryEventExecution.getId());
                boundaryEventBehavior.execute(boundaryEventExecution);
            }
        }
    }
}

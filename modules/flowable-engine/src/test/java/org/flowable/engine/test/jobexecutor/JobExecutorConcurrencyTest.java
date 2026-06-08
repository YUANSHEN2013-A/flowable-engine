package org.flowable.engine.test.jobexecutor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JobExecutorConcurrencyTest {

    private ProcessEngine processEngine;

    @BeforeEach
    public void setUp() {
        ProcessEngineConfigurationImpl processEngineConfiguration = new StandaloneInMemProcessEngineConfiguration();
        processEngineConfiguration.setJdbcUrl("jdbc:h2:mem:flowable-JobExecutorConcurrencyTest;DB_CLOSE_DELAY=1000");
        processEngineConfiguration.setDatabaseSchemaUpdate("true");
        processEngineConfiguration.setAsyncExecutorActivate(false);
        processEngine = processEngineConfiguration.buildProcessEngine();
    }

    @AfterEach
    public void tearDown() {
        if (processEngine != null) {
            for (org.flowable.engine.repository.Deployment deployment : processEngine.getRepositoryService().createDeploymentQuery().list()) {
                processEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
            }
            processEngine.close();
        }
    }

    @Test
    public void testConcurrentJobAcquisition() throws Exception {
        // Deploy a process with an async task
        processEngine.getRepositoryService().createDeployment()
                .addClasspathResource("org/flowable/engine/test/jobexecutor/AsyncExecutorTest.testRegularAsyncExecution.bpmn20.xml")
                .deploy();

        // Create 100 jobs
        for (int i = 0; i < 100; i++) {
            processEngine.getRuntimeService().startProcessInstanceByKey("asyncExecutor");
        }

        List<Job> jobs = processEngine.getManagementService().createJobQuery().list();
        assertThat(jobs).hasSize(100);

        // We want to simulate multiple async executors trying to acquire jobs simultaneously
        // This is a simplified regression test that ensures job logic doesn't fail under high concurrency
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedFixedThreadPool(threadCount);
        List<Callable<Void>> tasks = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                // Manually trigger the async executor command
                processEngine.getProcessEngineConfiguration().getAsyncExecutor().executeAsyncJob(null);
                return null;
            });
        }
        
        List<Future<Void>> futures = executorService.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get();
        }
        
        executorService.shutdown();
    }
}

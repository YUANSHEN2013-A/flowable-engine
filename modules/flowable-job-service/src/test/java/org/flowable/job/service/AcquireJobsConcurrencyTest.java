package org.flowable.job.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.flowable.job.service.impl.cmd.ExecuteAsyncJobCmd;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.flowable.job.service.impl.persistence.entity.JobEntityImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;

public class AcquireJobsConcurrencyTest {

    @Test
    public void testExecuteAsyncJobCmdVerifiesLockOwner() {
        JobServiceConfiguration jobServiceConfiguration = Mockito.mock(JobServiceConfiguration.class);
        AsyncExecutor asyncExecutor = Mockito.mock(AsyncExecutor.class);
        Mockito.when(jobServiceConfiguration.getAsyncExecutor()).thenReturn(asyncExecutor);
        Mockito.when(asyncExecutor.getLockOwner()).thenReturn("executor-1");
        
        JobEntityImpl job = new JobEntityImpl();
        job.setId("job-1");
        job.setLockOwner("executor-2"); // Different owner!
        job.setLockExpirationTime(new Date(System.currentTimeMillis() + 10000));
        
        org.flowable.job.service.impl.persistence.entity.JobInfoEntityManager jobEntityManager = Mockito.mock(org.flowable.job.service.impl.persistence.entity.JobInfoEntityManager.class);
        Mockito.when(jobEntityManager.findById("job-1")).thenReturn(job);
        
        ExecuteAsyncJobCmd cmd = new ExecuteAsyncJobCmd("job-1", jobEntityManager, jobServiceConfiguration);
        
        Object result = cmd.execute(null);
        
        // Since lock owner is different, it should return null and NOT execute the job
        assertThat(result).isNull();
    }
}

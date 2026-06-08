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
package org.flowable.job.service.impl.cmd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.flowable.common.engine.impl.Page;
import org.flowable.common.engine.impl.runtime.Clock;
import org.flowable.job.service.JobServiceConfiguration;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.flowable.job.service.impl.persistence.entity.JobEntityImpl;
import org.flowable.job.service.impl.persistence.entity.JobInfoEntityManager;
import org.junit.jupiter.api.Test;

class AcquireJobsCmdTest {

    @Test
    void concurrentExecutorsAcquireAndExecuteJobOnlyOnce() throws Exception {
        Date currentTime = new Date(1_717_171_717_000L);
        Date expectedLockExpirationTime = new Date(currentTime.getTime() + 30_000L);
        InMemoryJobStore store = new InMemoryJobStore(1);
        AtomicInteger selectionCalls = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2);
        JobInfoEntityManager<JobEntity> manager = createManager(store, page -> {
            List<JobEntity> jobs = store.selectUnlockedJobs(page.getMaxResults());
            if (selectionCalls.incrementAndGet() <= 2) {
                barrier.await(5, TimeUnit.SECONDS);
            }
            return jobs;
        }, null);

        AsyncExecutor firstExecutor = createAsyncExecutor("executor-1", 1, 30_000, currentTime);
        AsyncExecutor secondExecutor = createAsyncExecutor("executor-2", 1, 30_000, currentTime);

        AcquireJobsCmd firstCommand = new AcquireJobsCmd(firstExecutor, 1, manager);
        AcquireJobsCmd secondCommand = new AcquireJobsCmd(secondExecutor, 1, manager);

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<List<? extends JobEntity>> firstFuture = submit(executorService, () -> cast(firstCommand.execute(null)));
            Future<List<? extends JobEntity>> secondFuture = submit(executorService, () -> cast(secondCommand.execute(null)));

            List<? extends JobEntity> firstAcquiredJobs = firstFuture.get(5, TimeUnit.SECONDS);
            List<? extends JobEntity> secondAcquiredJobs = secondFuture.get(5, TimeUnit.SECONDS);

            int totalAcquiredJobs = firstAcquiredJobs.size() + secondAcquiredJobs.size();
            assertThat(totalAcquiredJobs).isEqualTo(1);
            assertThat(firstAcquiredJobs.size()).isIn(0, 1);
            assertThat(secondAcquiredJobs.size()).isIn(0, 1);

            InMemoryJobStore.StoredJob storedJob = store.get("job-1");
            assertThat(storedJob.lockOwner).isIn("executor-1", "executor-2");
            assertThat(storedJob.lockExpirationTime).isEqualTo(expectedLockExpirationTime);
            assertThat(storedJob.revision).isEqualTo(2);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void acquisitionRetriesWhenContentionConsumesInitialSelection() {
        Date lockExpirationTime = new Date(1_717_171_747_000L);
        InMemoryJobStore store = new InMemoryJobStore(3);
        AtomicBoolean stealFirstSelection = new AtomicBoolean(true);
        JobInfoEntityManager<JobEntity> manager = createManager(store, store::selectUnlockedJobs, () -> {
            if (stealFirstSelection.compareAndSet(true, false)) {
                store.forceAcquire(List.of("job-1", "job-2"), "other-executor", new Date(lockExpirationTime.getTime() - 1_000L));
            }
        });

        List<JobEntity> acquiredJobs = manager.findJobsToExecuteAndLock(null, new Page(0, 2), "executor-1", lockExpirationTime);

        assertThat(acquiredJobs).extracting(JobEntity::getId).containsExactly("job-3");
        InMemoryJobStore.StoredJob remainingUnlockedJob = store.get("job-3");
        assertThat(remainingUnlockedJob.lockOwner).isEqualTo("executor-1");
        assertThat(remainingUnlockedJob.lockExpirationTime).isEqualTo(lockExpirationTime);
        assertThat(store.selectInvocationCount.get()).isEqualTo(2);
    }

    @Test
    void throughputRemainsCloseToLegacyBulkLockPath() {
        int totalJobs = 20_000;
        int batchSize = 8;
        int warmupRuns = 2;
        int measuredRuns = 5;
        List<Long> updatedDurations = new ArrayList<>();
        List<Long> legacyDurations = new ArrayList<>();

        for (int i = 0; i < warmupRuns + measuredRuns; i++) {
            long updatedDuration = measureUpdatedAcquisition(totalJobs, batchSize);
            long legacyDuration = measureLegacyAcquisition(totalJobs, batchSize);
            if (i >= warmupRuns) {
                updatedDurations.add(updatedDuration);
                legacyDurations.add(legacyDuration);
            }
        }

        long updatedMedian = median(updatedDurations);
        long legacyMedian = median(legacyDurations);
        double throughputRatio = (double) legacyMedian / updatedMedian;

        assertThat(throughputRatio).isGreaterThanOrEqualTo(0.80d);
    }

    private long measureUpdatedAcquisition(int totalJobs, int batchSize) {
        InMemoryJobStore store = new InMemoryJobStore(totalJobs);
        JobInfoEntityManager<JobEntity> manager = createManager(store, store::selectUnlockedJobs, null);
        Date lockExpirationTime = new Date(1_717_171_777_000L);

        long start = System.nanoTime();
        while (true) {
            List<JobEntity> acquiredJobs = manager.findJobsToExecuteAndLock(null, new Page(0, batchSize), "executor-1", lockExpirationTime);
            if (acquiredJobs.isEmpty()) {
                return System.nanoTime() - start;
            }
        }
    }

    private long measureLegacyAcquisition(int totalJobs, int batchSize) {
        InMemoryJobStore store = new InMemoryJobStore(totalJobs);
        Date lockExpirationTime = new Date(1_717_171_777_000L);

        long start = System.nanoTime();
        while (true) {
            List<JobEntity> acquiredJobs = store.selectUnlockedJobs(batchSize);
            if (acquiredJobs.isEmpty()) {
                return System.nanoTime() - start;
            }
            store.bulkLock(acquiredJobs, "executor-1", lockExpirationTime);
        }
    }

    private long median(List<Long> values) {
        List<Long> sortedValues = values.stream().sorted().collect(Collectors.toList());
        return sortedValues.get(sortedValues.size() / 2);
    }

    private Future<List<? extends JobEntity>> submit(ExecutorService executorService, Callable<List<? extends JobEntity>> callable) {
        return executorService.submit(callable);
    }

    @SuppressWarnings("unchecked")
    private List<? extends JobEntity> cast(List<? extends org.flowable.job.service.impl.persistence.entity.JobInfoEntity> jobs) {
        return (List<? extends JobEntity>) jobs;
    }

    @SuppressWarnings("unchecked")
    private JobInfoEntityManager<JobEntity> createManager(InMemoryJobStore store, JobSelection jobSelection, Runnable beforeLockAttempt) {
        JobInfoEntityManager<JobEntity> manager = mock(JobInfoEntityManager.class, CALLS_REAL_METHODS);
        when(manager.findJobsToExecute(any(), any(Page.class))).thenAnswer(invocation -> jobSelection.select(invocation.getArgument(1)));
        when(manager.lockJobIfNeeded(anyString(), anyInt(), anyString(), any(Date.class))).thenAnswer(invocation -> {
            if (beforeLockAttempt != null) {
                beforeLockAttempt.run();
            }
            return store.lockJobIfNeeded(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2),
                invocation.getArgument(3)
            );
        });
        return manager;
    }

    private AsyncExecutor createAsyncExecutor(String lockOwner, int maxAsyncJobsDuePerAcquisition, int asyncJobLockTimeInMillis, Date currentTime) {
        Clock clock = mock(Clock.class);
        when(clock.getCurrentTime()).thenReturn(currentTime);

        JobServiceConfiguration jobServiceConfiguration = mock(JobServiceConfiguration.class);
        when(jobServiceConfiguration.getClock()).thenReturn(clock);
        when(jobServiceConfiguration.getEnabledJobCategories()).thenReturn(null);

        AsyncExecutor asyncExecutor = mock(AsyncExecutor.class);
        when(asyncExecutor.getLockOwner()).thenReturn(lockOwner);
        when(asyncExecutor.getMaxAsyncJobsDuePerAcquisition()).thenReturn(maxAsyncJobsDuePerAcquisition);
        when(asyncExecutor.getAsyncJobLockTimeInMillis()).thenReturn(asyncJobLockTimeInMillis);
        when(asyncExecutor.getJobServiceConfiguration()).thenReturn(jobServiceConfiguration);
        return asyncExecutor;
    }

    @FunctionalInterface
    private interface JobSelection {

        List<JobEntity> select(Page page) throws Exception;
    }

    private static class InMemoryJobStore {

        private final Map<String, StoredJob> jobs = new LinkedHashMap<>();
        private final AtomicInteger selectInvocationCount = new AtomicInteger();

        private InMemoryJobStore(int totalJobs) {
            for (int i = 1; i <= totalJobs; i++) {
                String jobId = "job-" + i;
                jobs.put(jobId, new StoredJob(jobId, 1));
            }
        }

        private synchronized List<JobEntity> selectUnlockedJobs(int maxResults) {
            selectInvocationCount.incrementAndGet();
            return jobs.values().stream()
                .filter(StoredJob::isUnlocked)
                .limit(maxResults)
                .map(StoredJob::toEntity)
                .collect(Collectors.toList());
        }

        private synchronized boolean lockJobIfNeeded(String jobId, int revision, String lockOwner, Date lockExpirationTime) {
            StoredJob storedJob = jobs.get(jobId);
            if (storedJob == null || !storedJob.isUnlocked() || storedJob.revision != revision) {
                return false;
            }

            storedJob.lockOwner = lockOwner;
            storedJob.lockExpirationTime = lockExpirationTime;
            storedJob.revision = revision + 1;
            return true;
        }

        private synchronized void bulkLock(Collection<JobEntity> jobEntities, String lockOwner, Date lockExpirationTime) {
            for (JobEntity jobEntity : jobEntities) {
                StoredJob storedJob = jobs.get(jobEntity.getId());
                if (storedJob != null && storedJob.isUnlocked()) {
                    storedJob.lockOwner = lockOwner;
                    storedJob.lockExpirationTime = lockExpirationTime;
                }
            }
        }

        private synchronized void forceAcquire(Collection<String> jobIds, String lockOwner, Date lockExpirationTime) {
            for (String jobId : jobIds) {
                StoredJob storedJob = jobs.get(jobId);
                if (storedJob != null) {
                    storedJob.lockOwner = lockOwner;
                    storedJob.lockExpirationTime = lockExpirationTime;
                    storedJob.revision++;
                }
            }
        }

        private synchronized StoredJob get(String jobId) {
            StoredJob storedJob = jobs.get(jobId);
            return storedJob == null ? null : storedJob.copy();
        }

        private static class StoredJob {

            private final String id;
            private int revision;
            private String lockOwner;
            private Date lockExpirationTime;

            private StoredJob(String id, int revision) {
                this.id = id;
                this.revision = revision;
            }

            private boolean isUnlocked() {
                return lockOwner == null && lockExpirationTime == null;
            }

            private JobEntity toEntity() {
                JobEntityImpl entity = new JobEntityImpl();
                entity.setId(id);
                entity.setRevision(revision);
                entity.setLockOwner(lockOwner);
                entity.setLockExpirationTime(lockExpirationTime);
                return entity;
            }

            private StoredJob copy() {
                StoredJob copy = new StoredJob(id, revision);
                copy.lockOwner = lockOwner;
                copy.lockExpirationTime = lockExpirationTime;
                return copy;
            }
        }
    }
}

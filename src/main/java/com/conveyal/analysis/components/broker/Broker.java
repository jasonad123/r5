package com.conveyal.analysis.components.broker;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.RegionalAnalysisStatus;
import com.conveyal.analysis.components.WorkerLauncher;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent;
import com.conveyal.analysis.components.eventbus.WorkerEvent;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.results.MultiOriginAssembler;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import gnu.trove.TCollections;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.TObjectLongMap;
import gnu.trove.map.hash.TObjectLongHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.CANCELED;
import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.COMPLETED;
import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.STARTED;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Action.REQUESTED;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Role.REGIONAL;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Role.SINGLE_POINT;

/**
 * This class distributes the tasks making up regional jobs to workers.
 * <p>
 * It should aim to draw tasks fairly from all organizations, and fairly from all jobs within each
 * organization, while attempting to respect the transport network affinity of each worker, giving
 * the worker tasks that require the same network it has been using recently.
 * <p>
 * Previously workers long-polled for work, holding lots of connections open. Now they short-poll
 * and sleep for a while if there's no work. This is simpler and allows us to work withing much more
 * standard HTTP frameworks.
 * <p>
 * The fact that workers continuously re-poll for work every 10-30 seconds serves as a signal to the
 * broker that they are still alive and waiting. This also allows the broker to maintain a catalog
 * of active workers.
 * <p>
 * Because (at least currently) two organizations never share the same graph, we can get by with
 * pulling tasks cyclically or randomly from all the jobs, and actively shape the number of workers
 * with affinity for each graph by forcing some of them to accept tasks on graphs other than the one
 * they have declared affinity for.
 * <p>
 * This could be thought of as "affinity homeostasis". We  will constantly keep track of the ideal
 * proportion of workers by graph (based on active jobs), and the true proportion of consumers by
 * graph (based on incoming polling), then we can decide when a worker's graph affinity should be
 * ignored and what it should be forced to.
 * <p>
 * It may also be helpful to mark jobs every time they are skipped in the LRU queue. Each time a job
 * is serviced, it is taken out of the queue and put at its end. Jobs that have not been serviced
 * float to the top.
 * <p>
 * Most methods on this class are synchronized, because they can be called from many HTTP handler
 * threads at once.
 *
 * TODO evaluate whether synchronizing all methods to make this threadsafe is a performance issue.
 */
public class Broker {

    private static final Logger LOG = LoggerFactory.getLogger(Broker.class);

    public interface Config {
        // TODO Really these first two should be WorkerLauncher / Compute config
        boolean offline ();
        int maxWorkers ();
        String resultsBucket ();
        String bundleBucket ();
        String polygonsBucket ();
        String tauiResultsBucket ();
        boolean testTaskRedelivery ();
    }

    private Config config;
    private final boolean offlineMode;

    // Component Dependencies
    private final BiConsumer<String, File> moveIntoResultsStorage;
    private final BiConsumer<String, File> moveIntoBundleStorage;
    private final EventBus eventBus;
    private final WorkerLauncher workerLauncher;

    private final ListMultimap<WorkerCategory, Job> jobs =
            MultimapBuilder.hashKeys().arrayListValues().build();

    /** The most tasks to deliver to a worker at a time. */
    public final int MAX_TASKS_PER_WORKER = 16;

    /**
     * Used when auto-starting spot instances. Set to a smaller value to increase the number of
     * workers requested automatically
     */
    public final int TARGET_TASKS_PER_WORKER = 800;

    /**
     * We want to request spot instances to "boost" regional analyses after a few regional task
     * results are received for a given workerCategory. Do so after receiving results for an
     * arbitrary task toward the beginning of the job
     */
    public final int AUTO_START_SPOT_INSTANCES_AT_TASK = 42;

    /** The maximum number of spot instances allowable in an automatic request */
    public final int MAX_WORKERS_PER_CATEGORY = 250;

    /**
     * How long to give workers to start up (in ms) before assuming that they have started (and
     * starting more on a given graph if they haven't.
     */
    public static final long WORKER_STARTUP_TIME = 60 * 60 * 1000;


    /** Keeps track of all the workers that have contacted this broker recently asking for work. */
    private WorkerCatalog workerCatalog = new WorkerCatalog();

    /**
     * keep track of which graphs we have launched workers on and how long ago we launched them, so
     * that we don't re-request workers which have been requested.
     */
    public TObjectLongMap<WorkerCategory> recentlyRequestedWorkers =
            TCollections.synchronizedMap(new TObjectLongHashMap<>());

    public Broker (Config config, FileStorage fileStorage, EventBus eventBus, WorkerLauncher workerLauncher) {
        moveIntoBundleStorage = fileStorage.createMoveIntoStorage(config.bundleBucket());
        moveIntoResultsStorage = fileStorage.createMoveIntoStorage(config.resultsBucket());
        offlineMode = config.offline();
        this.config = config;
        this.eventBus = eventBus;
        this.workerLauncher = workerLauncher;
    }

    /**
     * Enqueue a set of tasks for a regional analysis.
     * Only a single task is passed in, which the broker will expand into all the individual tasks for a regional job.
     * We pass in the group and user only to tag any newly created workers. This should probably be done in the caller.
     */
    public synchronized void enqueueTasksForRegionalJob (RegionalAnalysis regionalAnalysis) {
        // Store the full scenario in the FileStore
        storeScenarioJSON(regionalAnalysis.request.scenario, regionalAnalysis.request.graphId);
        // Make a copy of the regional task inside the RegionalAnalysis, replacing the scenario with a scenario ID.
        RegionalTask templateTask = regionalAnalysis.request.clone();
        // First replace the inline scenario with a scenario ID, storing the scenario for retrieval by workers.
        templateTask.scenarioId = templateTask.scenario.id;
        // Null out the scenario in the template task, avoiding repeated serialization to the workers as massive JSON.
        templateTask.scenario = null;
        // Set the jobId to the unique regional analysis ID.
        templateTask.jobId = regionalAnalysis._id;

        WorkerTags workerTags = WorkerTags.fromRegionalAnalysis(regionalAnalysis);

        LOG.info("Enqueuing tasks for job {} using template task.", templateTask.jobId);
        if (findJob(templateTask.jobId) != null) {
            LOG.error("Someone tried to enqueue job {} but it already exists.", templateTask.jobId);
            throw new RuntimeException("Enqueued duplicate job " + templateTask.jobId);
        }

        MultiOriginAssembler assembler = new MultiOriginAssembler(regionalAnalysis, templateTask, moveIntoResultsStorage);
        Job job = new Job(templateTask, assembler, workerTags);
        jobs.put(job.workerCategory, job);

        if (config.testTaskRedelivery()) {
            // This is a fake job for testing, don't confuse the worker startup code below with null graph ID.
            return;
        }

        if (offlineMode && workerCatalog.noWorkersAvailableForGraph(job.workerCategory.graphId)) {
            createOnDemandWorkerInCategory(job.workerCategory, workerTags);
        } else if (!offlineMode && workerCatalog.noWorkersAvailableInCategory(job.workerCategory)) {
            createOnDemandWorkerInCategory(job.workerCategory, workerTags);
        } else {
            // Workers exist in this category, clear out any record that we're waiting for one to start up.
            recentlyRequestedWorkers.remove(job.workerCategory);
        }

        eventBus.send(new RegionalAnalysisEvent(templateTask.jobId, STARTED).forUser(workerTags.user, workerTags.group));
    }

    /**
     * TODO should be done in regional analysis controller?
     * @param scenario
     * @param bundleId
     */
    private void storeScenarioJSON (Scenario scenario, String bundleId) {
        String fileName = String.format("%s_%s.json", bundleId, scenario.id);
        try {
            File localScenario = FileUtils.createScratchFile("json");
            JsonUtil.objectMapper.writeValue(localScenario, scenario);
            moveIntoBundleStorage.accept(fileName, localScenario);
        } catch (IOException e) {
            LOG.error("Error storing scenario for retrieval by workers.", e);
        }
    }

    /**
     * Create on-demand worker for a given job.
     */
    public void createOnDemandWorkerInCategory(WorkerCategory category, WorkerTags workerTags){
        createWorkersInCategory(category, workerTags, 1, 0);
    }

    /**
     * Create on-demand/spot workers for a given job, after certain checks
     * @param nOnDemand EC2 on-demand instances to request
     * @param nSpot EC2 spot instances to request
     */
    public void createWorkersInCategory (WorkerCategory category, WorkerTags workerTags, int nOnDemand, int nSpot) {
        if (offlineMode) {
            LOG.info("Work offline enabled, not creating workers for {}", category);
            return;
        }

        if (nOnDemand < 0 || nSpot < 0){
            LOG.info("Negative number of workers requested, not starting any");
            return;
        }

        if (workerCatalog.totalWorkerCount() + nOnDemand + nSpot >= config.maxWorkers()) {
            String message = String.format(
                    "Maximum of %d workers already started, not starting more;" +
                    "jobs will not complete on %s",
                    config.maxWorkers(),
                    category
            );
            throw AnalysisServerException.forbidden(message);
        }

        // If workers have already been started up, don't repeat the operation.
        if (recentlyRequestedWorkers.containsKey(category)
                && recentlyRequestedWorkers.get(category) >= System.currentTimeMillis() - WORKER_STARTUP_TIME) {
            LOG.info("Workers still starting on {}, not starting more", category);
            return;
        }

        workerLauncher.launch(category, workerTags, nOnDemand, nSpot);

        // Record the fact that we've requested an on-demand worker so we don't do it repeatedly.
        if (nOnDemand > 0) {
            recentlyRequestedWorkers.put(category, System.currentTimeMillis());
        }
        if (nSpot > 0) {
            eventBus.send(new WorkerEvent(REGIONAL, category, REQUESTED, nSpot).forUser(workerTags.user, workerTags.group));
        }
        if (nOnDemand > 0) {
            eventBus.send(new WorkerEvent(SINGLE_POINT, category, REQUESTED, nOnDemand).forUser(workerTags.user, workerTags.group));
        }
        LOG.info("Requested {} on-demand and {} spot workers on {}", nOnDemand, nSpot, category);
    }

    /**
     * Attempt to find some tasks that match what a worker is requesting.
     * Always returns a list, which may be empty if there is nothing to deliver.
     */
    public synchronized List<RegionalTask> getSomeWork (WorkerCategory workerCategory) {
        Job job;
        if (offlineMode) {
            // Working in offline mode; get tasks from the first job that has any tasks to deliver.
            job = jobs.values().stream()
                    .filter(Job::hasTasksToDeliver).findFirst().orElse(null);
        } else {
            // This worker has a preferred network, get tasks from a job on that network.
            job = jobs.get(workerCategory).stream()
                    .filter(Job::hasTasksToDeliver).findFirst().orElse(null);
        }
        if (job == null) {
            // No matching job was found.
            return Collections.EMPTY_LIST;
        }
        // Return up to N tasks that are waiting to be processed.
        return job.generateSomeTasksToDeliver(MAX_TASKS_PER_WORKER);
    }

    /**
     * Take a normal (non-priority) task out of a job queue, marking it as completed so it will not
     * be re-delivered. The result of the computation is supplied. This could potentially be merged
     * with handleRegionalWorkResult, but they have different synchronization requirements.
     * TODO separate marking complete from returning the work product, since they have different
     *      synchronization requirements. This would also allow returning errors as JSON and the
     *      grid result separately.
     *
     * @return whether the task was found and removed.
     */
    public synchronized void markTaskCompleted (String jobId, int taskId) {
        Job job = findJob(jobId);
        if (job == null) {
            LOG.error("Could not find a job with ID {} and therefore could not mark the task as completed.", jobId);
            return;
        }
        if (!job.markTaskCompleted(taskId)) {
            LOG.error("Failed to mark task {} completed on job {}.", taskId, jobId);
        }
        // Once the last task is marked as completed, the job is finished.
        // Purge it from the list to free memory.
        if (job.isComplete()) {
            job.verifyComplete();
            jobs.remove(job.workerCategory, job);
            // This method is called after the regional work results are handled, finishing and closing the local file.
            // So we can harmlessly remove the MultiOriginAssembler now that the job is removed.
            try {
                job.assembler.terminate();
            } catch (Exception e) {
                LOG.error(
                        "Could not terminate grid result assembler, this may waste disk space. Reason: {}",
                        e.toString()
                );
            }
            eventBus.send(new RegionalAnalysisEvent(job.jobId, COMPLETED).forUser(job.workerTags.user, job.workerTags.group));
        }
    }

    /**
     * Simple method for querying all current job statuses.
     * @return List of JobStatuses
     */
    public synchronized Collection<JobStatus> getAllJobStatuses () {
        TObjectIntMap<String> workersPerJob = workerCatalog.activeWorkersPerJob();
        Collection<JobStatus> jobStatuses = new ArrayList<>();
        for (Job job : jobs.values()) {
            JobStatus jobStatus = new JobStatus(job);
            jobStatus.activeWorkers = workersPerJob.get(job.jobId);
            jobStatuses.add(jobStatus);
        }
        return jobStatuses;
    }

    /** Find the job for the given jobId, returning null if that job does not exist. */
    public synchronized Job findJob (String jobId) {
        return jobs.values().stream()
                .filter(job -> job.jobId.equals(jobId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Delete the job with the given ID.
     */
    public synchronized boolean deleteJob (String jobId) {
        // Remove the job from the broker so we stop distributing its tasks to workers.
        Job job = findJob(jobId);
        if (job == null) return false;
        boolean success = jobs.remove(job.workerCategory, job);
        // Shut down the object used for assembling results, removing its associated temporary disk file.
        try {
            job.assembler.terminate();
        } catch (Exception e) {
            LOG.error(
                "Could not terminate grid result assembler, this may waste disk space. Reason: {}",
                e.toString()
            );
            success = false;
        }
        eventBus.send(new RegionalAnalysisEvent(job.jobId, CANCELED).forUser(job.workerTags.user, job.workerTags.group));
        // Note updateByUserIfPermitted in caller, which deletes regional analysis from Persistence
        return success;
    }

    /**
     * Given a worker commit ID and transport network, return the IP or DNS name of a worker that has that software
     * and network already loaded. If none exist, return null and try to start one.
     */
    public synchronized String getWorkerAddress(WorkerCategory workerCategory) {
        if (offlineMode) {
            return "localhost";
        }
        // First try to get a worker that's already loaded the right network.
        // This value will be null if no workers exist in this category - caller should attempt to create some.
        return workerCatalog.getSinglePointWorkerAddressForCategory(workerCategory);
    }


    /**
     * Get a collection of all the workers that have recently reported to this broker.
     * The returned objects are designed to be serializable so they can be returned over an HTTP API.
     */
    public Collection<WorkerObservation> getWorkerObservations () {
        return workerCatalog.getAllWorkerObservations();
    }

    public synchronized void unregisterSinglePointWorker (WorkerCategory category) {
        workerCatalog.tryToReassignSinglePointWork(category);
    }

    /**
     * Record information that a worker sent about itself.
     */
    public void recordWorkerObservation(WorkerStatus workerStatus) {
        workerCatalog.catalog(workerStatus);
    }

    /**
     * Slots a single regional work result received from a worker into the appropriate position in
     * the appropriate file. Also considers requesting extra spot instances after a few results have
     * been received. The checks in place should prevent an unduly large number of workers from
     * proliferating, assuming jobs for a given worker category (transport network + R5 version) are
     * completed sequentially.
     *
     * @param workResult an object representing accessibility results for a single origin point,
     *                   sent by a worker.
     */
    public void handleRegionalWorkResult(RegionalWorkResult workResult) {
        // Retrieving the job and assembler from their maps is not threadsafe, so we do so in a
        // synchronized block here. Once the job is retrieved, it can be used to
        // requestExtraWorkers below without synchronization, because that method only uses final
        // fields of the job.
        Job job;
        synchronized (this) {
            job = findJob(workResult.jobId);
        }
        if (job == null) {
            LOG.error("Received result for unrecognized job ID {}, discarding.", workResult.jobId);
        } else {
            // FIXME this is building up to 5 grids and uploading them to S3, this should not be done synchronously in
            //       an HTTP handler.
            job.assembler.handleMessage(workResult);
            // When results for the task with the magic number are received, consider boosting the job by starting EC2
            // spot instances
            if (workResult.taskId == AUTO_START_SPOT_INSTANCES_AT_TASK) {
                requestExtraWorkersIfAppropriate(job);
            }
        }

        markTaskCompleted(workResult.jobId, workResult.taskId);
    }

    private void requestExtraWorkersIfAppropriate(Job job) {
        WorkerCategory workerCategory = job.workerCategory;
        int categoryWorkersAlreadyRunning = workerCatalog.countWorkersInCategory(workerCategory);
        if (categoryWorkersAlreadyRunning < MAX_WORKERS_PER_CATEGORY) {
            // Start a number of workers that scales with the number of total tasks, up to a fixed number.
            // TODO more refined determination of number of workers to start (e.g. using tasks per minute)
            int targetWorkerTotal = Math.min(MAX_WORKERS_PER_CATEGORY, job.templateTask.nTasksTotal / TARGET_TASKS_PER_WORKER);
            // Guardrail until freeform pointsets are tested more thoroughly
            if (job.templateTask.originPointSet != null) targetWorkerTotal = Math.min(targetWorkerTotal, 5);
            int nSpot =  targetWorkerTotal - categoryWorkersAlreadyRunning;
            createWorkersInCategory(job.workerCategory, job.workerTags, 0, nSpot);
        }
    }

    /**
     * Returns a simple status object intended to inform the UI of job progress.
     */
    public RegionalAnalysisStatus getJobStatus (String jobId) {
        Job job = findJob(jobId);
        return job == null ? null : new RegionalAnalysisStatus(job.assembler);
    }

    public File getPartialRegionalAnalysisResults (String jobId) {
        Job job = findJob(jobId);
        return job == null ? null : job.assembler.getGridBufferFile();
    }

    public synchronized boolean anyJobsActive () {
        for (Job job : jobs.values()) {
            if (!job.isComplete()) return true;
        }
        return false;
    }

    public synchronized void logJobStatus() {
        for (Job job : jobs.values()) {
            LOG.info(job.toString());
        }
    }
}

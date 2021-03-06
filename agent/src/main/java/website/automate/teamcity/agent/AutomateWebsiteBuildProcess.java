package website.automate.teamcity.agent;

import static java.lang.String.format;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import website.automate.manager.api.client.JobManagementRemoteService;
import website.automate.manager.api.client.model.Authentication;
import website.automate.manager.api.client.model.Job;
import website.automate.manager.api.client.model.TestResults;
import website.automate.manager.api.client.model.Job.JobProfile;
import website.automate.manager.api.client.model.Job.JobStatus;
import website.automate.manager.api.client.model.Job.TakeScreenshots;
import website.automate.manager.api.client.support.Constants;
import website.automate.teamcity.agent.support.BuildProcessConfig;
import website.automate.teamcity.agent.support.ExecutionInterruptionException;
import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.BuildFinishedStatus;
import jetbrains.buildServer.agent.BuildProcess;
import jetbrains.buildServer.agent.BuildProgressLogger;

public class AutomateWebsiteBuildProcess implements BuildProcess {

    private static final long DEFAULT_JOB_STATUS_CHECK_INTERVAL_IN_SEC = 30;

    private static final long DEFAULT_EXECUTION_TIMEOUT_IN_SEC = 300;

    private JobManagementRemoteService jobManagementRemoteService = JobManagementRemoteService
            .getInstance();

    private long jobStatusCheckIntervalInSec = DEFAULT_JOB_STATUS_CHECK_INTERVAL_IN_SEC;

    private long executionTimeoutInSec = DEFAULT_EXECUTION_TIMEOUT_IN_SEC;

    private BuildProcessConfig config;

    private BuildFinishedStatus status;
    
    private BuildProgressLogger logger;
    
    public AutomateWebsiteBuildProcess(BuildProcessConfig config, BuildProgressLogger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void start() throws RunBuildException {
        List<String> scenarioIds = config.getScenarioIds();
        Authentication principal = Authentication.of(config.getUsername(), config.getPassword());

        if (scenarioIds == null || scenarioIds.isEmpty()) {
            logger.message("Skipping execution - no scenarios were selected.");
            status = BuildFinishedStatus.FINISHED_SUCCESS;
            return;
        }
        logger.message(format("Creating jobs for selected scenarios %s ...",
                scenarioIds));
        List<Job> createdJobs = jobManagementRemoteService.createJobs(
                createJobs(scenarioIds), principal);
        long jobsCreatedMillis = System.currentTimeMillis();

        Collection<String> createdJobIds = asJobIds(createdJobs);
        List<Job> updatedJobs;

        do {
            if (System.currentTimeMillis() - jobsCreatedMillis >= executionTimeoutInSec * 1000) {
                break;
            }
            try {
                Thread.sleep(jobStatusCheckIntervalInSec * 1000);
            } catch (InterruptedException e) {
                throw new ExecutionInterruptionException(
                        "Unexpected plugin execution thread interrupt occured.",
                        e);
            }

            logger.message("Checking job statuses ...");
            updatedJobs = jobManagementRemoteService.getJobsByIdsAndPrincipal(
                    createdJobIds, principal, JobProfile.BRIEF);

        } while (!areCompleted(updatedJobs));

        logger.message("Jobs execution completed.");
        updatedJobs = jobManagementRemoteService.getJobsByIdsAndPrincipal(
                createdJobIds, principal, JobProfile.COMPLETE);

        status = logJobStatuses(updatedJobs);
    }

    private BuildFinishedStatus logJobStatuses(Collection<Job> jobs) {
        BuildFinishedStatus result = BuildFinishedStatus.FINISHED_SUCCESS;

        for (Job job : jobs) {
            String jobTitle = job.getTitle();
            TestResults testResults = job.getTestResults();
            String jobUrl = getJobUrl(job.getId());
            if (testResults != null) {
                if (!testResults.isFailed()) {
                    logger.message(format("%s job execution succeeded (%s).",
                            jobTitle, jobUrl));
                } else {
                    if (result != BuildFinishedStatus.FINISHED_FAILED) {
                        result = BuildFinishedStatus.FINISHED_WITH_PROBLEMS;
                    }

                    logger.warning(format("%s job execution failed (%s).",
                            jobTitle, jobUrl));
                }
            } else {
                result = BuildFinishedStatus.FINISHED_FAILED;
                logger.warning(format(
                        "Unexpected error occured during execution of '%s' or execution took to long (%s).",
                        jobTitle, jobUrl));
            }
        }
        return result;
    }
    
    private String getJobUrl(String jobId) {
        return Constants.getAppBaseUrl() + "/job/" + jobId;
    }

    private boolean areCompleted(Collection<Job> jobs) {
        for (Job job : jobs) {
            JobStatus jobStatus = job.getStatus();
            if (jobStatus == JobStatus.SCHEDULED
                    || jobStatus == JobStatus.RUNNING) {
                return false;
            }
        }
        return true;
    }

    private Collection<String> asJobIds(Collection<Job> jobs) {
        List<String> jobIds = new ArrayList<String>();
        for (Job job : jobs) {
            jobIds.add(job.getId());
        }
        return jobIds;
    }

    private Collection<Job> createJobs(Collection<String> scenarioIds) {
        List<Job> jobs = new ArrayList<Job>();
        for (String scenarioId : scenarioIds) {
            jobs.add(createJob(scenarioId));
        }
        return jobs;
    }

    private Job createJob(String scenarioId) {
        Job job = new Job();
        job.setScenarioId(scenarioId);
        job.setTakeScreenshots(TakeScreenshots.ON_FAILURE);
        return job;
    }

    @Override
    public boolean isInterrupted() {
        return false;
    }

    @Override
    public boolean isFinished() {
        return status != null;
    }

    @Override
    public void interrupt() {
    }

    @Override
    public BuildFinishedStatus waitFor() throws RunBuildException {
        return status;
    }
}

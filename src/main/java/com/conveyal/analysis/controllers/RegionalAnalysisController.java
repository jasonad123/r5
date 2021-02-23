package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.SelectingGridReducer;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.broker.JobStatus;
import com.conveyal.analysis.models.AnalysisRequest;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.Project;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.models.RegionalAnalysis.Result;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.Bucket;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.google.common.primitives.Ints;
import com.mongodb.QueryBuilder;
import gnu.trove.list.array.TIntArrayList;
import org.json.simple.JSONObject;
import org.mongojack.DBProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Spark HTTP handler methods that allow launching new regional analyses, as well as deleting them and fetching
 * information about them.
 */
public class RegionalAnalysisController implements HttpController {
    private static final int MAX_FREEFORM_OD_PAIRS = 16_000_000;
    private static final int MAX_FREEFORM_DESTINATIONS = 4_000_000;

    private static final Logger LOG = LoggerFactory.getLogger(RegionalAnalysisController.class);

    private final Broker broker;
    private final Bucket resultsBucket;
    private final PointSetCache pointSetCache;

    public RegionalAnalysisController (Broker broker, Bucket resultsBucket, PointSetCache pointSetCache) {
        this.broker = broker;
        this.resultsBucket = resultsBucket;
        this.pointSetCache = pointSetCache;
    }

    private Collection<RegionalAnalysis> getRegionalAnalysesForRegion(String regionId, String accessGroup) {
        return Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start().and(
                        QueryBuilder.start("regionId").is(regionId).get(),
                        QueryBuilder.start("deleted").is(false).get()
                ).get(),
                DBProjection.exclude("request.scenario.modifications"),
                accessGroup
        );
    }

    private Collection<RegionalAnalysis> getRegionalAnalysesForRegion(Request req, Response res) {
        return getRegionalAnalysesForRegion(req.params("regionId"), req.attribute("accessGroup"));
    }

    // Note: this includes the modifications object which can be very large
    private RegionalAnalysis getRegionalAnalysis(Request req, Response res) {
        return Persistence.regionalAnalyses.findByIdIfPermitted(req.params("_id"), req.attribute("accessGroup"));
    }

    /**
     * Looks up all regional analyses for a region and checks the broker for jobs associated with them. If a JobStatus
     * exists it adds it to the list of running analyses.
     * @return JobStatues with associated regional analysis embedded
     */
    private Collection<JobStatus> getRunningAnalyses(Request req, Response res) {
        Collection<RegionalAnalysis> allAnalysesInRegion = getRegionalAnalysesForRegion(req.params("regionId"), req.attribute("accessGroup"));
        List<JobStatus> runningStatusesForRegion = new ArrayList<>();
        Collection<JobStatus> allJobStatuses = broker.getAllJobStatuses();
        for (RegionalAnalysis ra : allAnalysesInRegion) {
            JobStatus jobStatus = allJobStatuses.stream().filter(j -> j.jobId.equals(ra._id)).findFirst().orElse(null);
            if (jobStatus != null) {
                jobStatus.regionalAnalysis = ra;
                runningStatusesForRegion.add(jobStatus);
            }
        }

        return runningStatusesForRegion;
    }

    private RegionalAnalysis deleteRegionalAnalysis (Request req, Response res) {
        String accessGroup = req.attribute("accessGroup");
        String email = req.attribute("email");

        RegionalAnalysis analysis = Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start().and(
                        QueryBuilder.start("_id").is(req.params("_id")).get(),
                        QueryBuilder.start("deleted").is(false).get()
                ).get(),
                DBProjection.exclude("request.scenario.modifications"),
                accessGroup
        ).iterator().next();
        analysis.deleted = true;
        Persistence.regionalAnalyses.updateByUserIfPermitted(analysis, email, accessGroup);

        // clear it from the broker
        if (!analysis.complete) {
            String jobId = analysis._id;
            if (broker.deleteJob(jobId)) {
                LOG.info("Deleted job {} from broker.", jobId);
            } else {
                LOG.error("Deleting job {} from broker failed.", jobId);
            }
        }
        return analysis;
    }

    private int getIntQueryParameter (Request req, String parameterName, int defaultValue) {
        String paramValue = req.queryParams(parameterName);
        if (paramValue == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(paramValue);
        } catch (Exception ex) {
            String message = String.format(
                "Query parameter '%s' must be an integer, cannot parse '%s'.",
                parameterName,
                paramValue
            );
            throw new IllegalArgumentException(message, ex);
        }
    }

    /**
     * This used to extract a particular percentile of a regional analysis as a grid file.
     * Now it just gets the single percentile that exists for any one analysis, either from the local buffer file
     * for an analysis still in progress, or from S3 for a completed analysis.
     */
    private Object getRegionalResults (Request req, Response res) throws IOException {

        // Get some path parameters out of the URL.
        // The UUID of the regional analysis for which we want the output data
        final String regionalAnalysisId = req.params("_id");
        // The response file format: PNG, TIFF, or GRID
        final String fileFormatExtension = req.params("format");

        RegionalAnalysis analysis = Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start("_id").is(req.params("_id")).get(),
                DBProjection.exclude("request.scenario.modifications"),
                req.attribute("accessGroup")
        ).iterator().next();

        if (analysis == null || analysis.deleted) {
            throw AnalysisServerException.notFound("The specified regional analysis is unknown or has been deleted.");
        }

        // Which channel to extract from results with multiple values per origin (for different travel time cutoffs)
        // and multiple output files per analysis (for different percentiles of travel time and/or different
        // destination pointsets). These initial values are for older regional analysis results with only a single
        // cutoff, and no percentile or destination gridId in the file name.
        // For newer analyses that have multiple cutoffs, percentiles, or destination pointsets, these initial values
        // are coming from deprecated fields, are not meaningful and will be overwritten below from query parameters.
        int percentile = analysis.travelTimePercentile;
        int cutoffMinutes = analysis.cutoffMinutes;
        int cutoffIndex = 0;
        String destinationPointSetId = analysis.grid;

        // Handle newer regional analyses with multiple cutoffs in an array.
        // If a query parameter is supplied, range check it, otherwise use the middle value in the list.
        // The cutoff variable holds the actual cutoff in minutes, not the position in the array of cutoffs.
        if (analysis.cutoffsMinutes != null) {
            int nCutoffs = analysis.cutoffsMinutes.length;
            checkState(nCutoffs > 0, "Regional analysis has no cutoffs.");
            cutoffMinutes = getIntQueryParameter(req, "cutoff", analysis.cutoffsMinutes[nCutoffs / 2]);
            cutoffIndex = new TIntArrayList(analysis.cutoffsMinutes).indexOf(cutoffMinutes);
            checkState(cutoffIndex >= 0,
                    "Travel time cutoff for this regional analysis must be taken from this list: (%s)",
                    Ints.join(", ", analysis.cutoffsMinutes)
            );
        }

        // Handle newer regional analyses with multiple percentiles in an array.
        // If a query parameter is supplied, range check it, otherwise use the middle value in the list.
        // The percentile variable holds the actual percentile (25, 50, 95) not the position in the array.
        if (analysis.travelTimePercentiles != null) {
            int nPercentiles = analysis.travelTimePercentiles.length;
            checkState(nPercentiles > 0, "Regional analysis has no percentiles.");
            percentile = getIntQueryParameter(req, "percentile", analysis.travelTimePercentiles[nPercentiles / 2]);
            checkArgument(new TIntArrayList(analysis.travelTimePercentiles).contains(percentile),
                    "Percentile for this regional analysis must be taken from this list: (%s)",
                    Ints.join(", ", analysis.travelTimePercentiles));
        }

        // Handle even newer regional analyses with multiple destination pointsets per analysis.
        if (analysis.destinationPointSetIds != null) {
            int nGrids = analysis.destinationPointSetIds.length;
            checkState(nGrids > 0, "Regional analysis has no grids.");
            destinationPointSetId = req.queryParams("destinationPointSetId");
            if (destinationPointSetId == null) {
                destinationPointSetId = analysis.destinationPointSetIds[0];
            }
            checkArgument(Arrays.asList(analysis.destinationPointSetIds).contains(destinationPointSetId),
                    "Destination gridId must be one of: %s",
                    String.join(",", analysis.destinationPointSetIds));
        }

        // It seems like you would check regionalAnalysis.complete to choose between redirecting to s3 and fetching
        // the partially completed local file. But this field is never set to true - it's on a UI model object that
        // isn't readily accessible to the internal Job-tracking mechanism of the back end. Instead, just try to fetch
        // the partially completed results file, which includes an O(1) check whether the job is still being processed.
        File partialRegionalAnalysisResultFile = broker.getPartialRegionalAnalysisResults(regionalAnalysisId);

        if (partialRegionalAnalysisResultFile != null) {
            // FIXME we need to do the equivalent of the SelectingGridReducer here.
            // The job is still being processed. There is a probably harmless race condition if the job happens to be
            // completed at the very moment we're in this block, because the file will be deleted at that moment.
            LOG.info("Analysis {} is not complete, attempting to return the partial results grid.", regionalAnalysisId);
            if (!"GRID".equalsIgnoreCase(fileFormatExtension)) {
                throw AnalysisServerException.badRequest(
                        "For partially completed regional analyses, we can only return grid files, not images.");
            }
            try {
                res.header("content-type", "application/octet-stream");
                // This will cause Spark Framework to gzip the data automatically if requested by the client.
                res.header("Content-Encoding", "gzip");
                // Spark has default serializers for InputStream and Bytes, and calls toString() on everything else.
                return new FileInputStream(partialRegionalAnalysisResultFile);
            } catch (FileNotFoundException e) {
                // The job must have finished and the file was deleted upon upload to S3. This should be very rare.
                throw AnalysisServerException.unknown(
                        "Could not find partial result grid for incomplete regional analysis on server.");
            }
        } else {
            // The analysis has already completed, results should be stored and retrieved from S3 via redirects.
            LOG.info("Returning {} minute accessibility to pointset {} (percentile {}) for regional analysis {}.",
                    cutoffMinutes, destinationPointSetId, percentile, regionalAnalysisId);
            FileStorageFormat format = FileStorageFormat.valueOf(fileFormatExtension.toUpperCase());
            if (!FileStorageFormat.GRID.equals(format) && !FileStorageFormat.PNG.equals(format) && !FileStorageFormat.TIFF.equals(format)) {
                throw AnalysisServerException.badRequest("Format \"" + format + "\" is invalid. Request format must be \"grid\", \"png\", or \"tiff\".");
            }

            // Analysis grids now have the percentile and cutoff in their S3 key, because there can be many of each.
            // We do this even for results generated by older workers, so they will be re-extracted with the new name.
            // These grids are reasonably small, we may be able to just send all cutoffs to the UI instead of selecting.
            String singleCutoffKey =
                    String.format("%s_%s_P%d_C%d.%s", regionalAnalysisId, destinationPointSetId, percentile, cutoffMinutes, fileFormatExtension);

            // A lot of overhead here - UI contacts backend, backend calls S3, backend responds to UI, UI contacts S3.
            if (!resultsBucket.exists(singleCutoffKey)) {
                // An accessibility grid for this particular cutoff has apparently never been extracted from the
                // regional results file before. Extract one and save it for future reuse. Older regional analyses
                // may not have arrays allowing multiple cutoffs, percentiles, or destination pointsets. The
                // filenames of such regional accessibility results will not have a percentile or pointset ID.
                String multiCutoffKey;
                if (analysis.travelTimePercentiles == null) {
                    // Oldest form of results, single-percentile, single grid.
                    multiCutoffKey = regionalAnalysisId + ".access";
                } else {
                    if (analysis.destinationPointSetIds == null) {
                        // Newer form of regional results: multi-percentile, single grid.
                        multiCutoffKey = String.format("%s_P%d.access", regionalAnalysisId, percentile);
                    } else {
                        // Newest form of regional results: multi-percentile, multi-grid.
                        multiCutoffKey = String.format("%s_%s_P%d.access", regionalAnalysisId, destinationPointSetId, percentile);
                    }
                }
                LOG.info("Single-cutoff grid {} not found on S3, deriving it from {}.", singleCutoffKey, multiCutoffKey);

                InputStream multiCutoffInputStream = new FileInputStream(resultsBucket.getFile(multiCutoffKey));
                Grid grid = new SelectingGridReducer(cutoffIndex).compute(multiCutoffInputStream);

                File localFile = FileUtils.createScratchFile(format.toString());
                FileOutputStream fos = new FileOutputStream(localFile);

                switch (format) {
                    case GRID:
                        grid.write(new GZIPOutputStream(fos));
                        break;
                    case PNG:
                        grid.writePng(fos);
                        break;
                    case TIFF:
                        grid.writeGeotiff(fos);
                        break;
                }

                resultsBucket.moveIntoStorage(singleCutoffKey, localFile);
            }

            JSONObject json = new JSONObject();
            json.put("url", resultsBucket.getURL(singleCutoffKey));
            return json.toJSONString();
        }
    }

    private String getCsvResults (Request req, Response res) {
        final String regionalAnalysisId = req.params("_id");
        final Result resultType = Result.valueOf(req.params("resultType").toUpperCase());

        RegionalAnalysis analysis = Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start("_id").is(regionalAnalysisId).get(),
                DBProjection.exclude("request.scenario.modifications"),
                req.attribute("accessGroup")
        ).iterator().next();

        if (analysis == null || analysis.deleted) {
            throw AnalysisServerException.notFound("The specified analysis is unknown, incomplete, or deleted.");
        }

        if (resultType == Result.ACCESS && !analysis.request.recordAccessibility) {
            throw AnalysisServerException.notFound("Accessibility results were not recorded for this analysis");
        }

        if (resultType == Result.TIMES && !analysis.request.recordTimes) {
            throw AnalysisServerException.notFound("Travel time results were not recorded for this analysis");
        }

        if (resultType == Result.PATHS && !analysis.request.includePathResults) {
            throw AnalysisServerException.notFound("Path results were not recorded for this analysis");
        }

        res.type("text");
        return resultsBucket.getURL(analysis.getCsvStoragePath(resultType));
    }

    /**
     * Deserialize a description of a new regional analysis (an AnalysisRequest object) POSTed as JSON over the HTTP API.
     * Derive an internal RegionalAnalysis object, which is enqueued in the broker and also returned to the caller
     * in the body of the HTTP response.
     */
    private RegionalAnalysis createRegionalAnalysis (Request req, Response res) throws IOException {
        final String accessGroup = req.attribute("accessGroup");
        final String email = req.attribute("email");
        AnalysisRequest analysisRequest = JsonUtil.objectMapper.readValue(req.body(), AnalysisRequest.class);

        // Create an internal RegionalTask and RegionalAnalysis from the AnalysisRequest sent by the client.
        Project project = Persistence.projects.findByIdIfPermitted(analysisRequest.projectId, accessGroup);
        // Populate common settings here, and RegionalTask specific ones below.
        RegionalTask task = (RegionalTask) analysisRequest.populateTask(new RegionalTask(), project);

        // RegionalTask specific settings here:
        task.oneToOne = analysisRequest.oneToOne;
        task.recordAccessibility = analysisRequest.recordAccessibility;
        task.recordTimes = analysisRequest.recordTimes;
        // For now, we support calculating paths in regional analyses only for freeform origins.
        if (analysisRequest.recordPaths) {
            checkState(
                    analysisRequest.originPointSetId != null,
                    "Recording paths requires a originPointSetId to be set to a FreeForm PointSet."
            );
            task.includePathResults = true;
        }

        // TODO these checks should be done on task creation


        // Making a Taui site implies writing static travel time and path files per origin, but not accessibility.
        if (analysisRequest.makeTauiSite) {
            task.makeTauiSite = true;
            task.recordAccessibility = false;
        } else {
            checkState (
                    task.recordTimes || task.includePathResults || task.recordAccessibility,
                    "A regional analysis should always create at least one grid or CSV file."
            );
            if (task.recordAccessibility) {
                checkState(
                        task.originPointSet != null && task.destinationPointSetKeys.length * task.percentiles.length != 0,
                        "Regional analysis cannot record accessibility without an origin or destination point set."
                );
            }
        }

        // Set the origin point set if one is specified.
        if (analysisRequest.originPointSetId != null) {
            OpportunityDataset originPointSet = Persistence.opportunityDatasets
                    .findByIdIfPermitted(analysisRequest.originPointSetId, accessGroup);
            task.originPointSetKey = originPointSet.storageLocation();
            task.originPointSet = pointSetCache.get(task.originPointSetKey);
            task.nTasksTotal = originPointSet.totalPoints;
        } else {
            task.nTasksTotal = task.width * task.height;
        }

        // Do a preflight validation of the cutoffs and percentiles arrays for all regional tasks.
        task.validateCutoffsMinutes();
        task.validatePercentiles();

        // Set the destination PointSets, which are required for all non-Taui regional requests.
        if (!analysisRequest.makeTauiSite) {
            checkState(analysisRequest.destinationPointSetIds != null && analysisRequest.destinationPointSetIds.length > 0,
                "At least one destination pointset ID must be supplied.");
            int nPointSets = analysisRequest.destinationPointSetIds.length;
            int opportunityDatasetZoom = 0;
            task.destinationPointSetKeys = new String[nPointSets];
            List<OpportunityDataset> opportunityDatasets = new ArrayList<>();
            for (int i = 0; i < nPointSets; i++) {
                String destinationPointSetId = analysisRequest.destinationPointSetIds[i];
                OpportunityDataset opportunityDataset = Persistence.opportunityDatasets.findByIdIfPermitted(
                        destinationPointSetId,
                        accessGroup
                );
                checkNotNull(opportunityDataset, "Opportunity dataset could not be found in database.");
                opportunityDatasets.add(opportunityDataset);
                if (i == 0) opportunityDatasetZoom = opportunityDataset.getWebMercatorExtents().zoom;
                else {
                    checkArgument(
                            opportunityDataset.getWebMercatorExtents().zoom == opportunityDatasetZoom,
                            "If multiple grids are specified as destinations, they must have identical resolutions (web mercator zoom levels)."
                    );
                }

                task.destinationPointSetKeys[i] = opportunityDataset.storageLocation();

                if (opportunityDataset.format.equals(FileStorageFormat.FREEFORM)) {
                    checkArgument(
                            nPointSets == 1,
                            "If a freeform destination PointSet is specified, it must be the only one."
                    );

                    if ((task.recordTimes || task.includePathResults) && !task.oneToOne) {
                        if (task.nTasksTotal * opportunityDataset.totalPoints > MAX_FREEFORM_OD_PAIRS ||
                                opportunityDataset.totalPoints > MAX_FREEFORM_DESTINATIONS
                        ) {
                            throw new AnalysisServerException(String.format(
                                    "Freeform requests limited to %d destinations and %d origin-destination pairs.",
                                    MAX_FREEFORM_DESTINATIONS, MAX_FREEFORM_OD_PAIRS
                            ));
                        }
                    }
                }
            }

            // For backward compatibility with old workers, communicate any single pointSet via the deprecated field.
            if (nPointSets == 1) {
                task.grid = task.destinationPointSetKeys[0];
            }

            // Load and validate all destination point sets
            task.loadAndValidateDestinationPointSets(pointSetCache);
        }

        // TODO remove duplicate fields from RegionalAnalysis that are already in the nested task.
        // The RegionalAnalysis should just be a minimal wrapper around the template task, adding the origin point set.
        // The RegionalAnalysis object contains a reference to the worker task itself.
        // In fact, there are three separate classes all containing almost the same info:
        // AnalysisRequest (from UI to backend), RegionalTask (template sent to worker), RegionalAnalysis (in Mongo).
        // And for regional analyses, two instances of the worker task: the one with the scenario, and the templateTask.
        RegionalAnalysis regionalAnalysis = new RegionalAnalysis();
        regionalAnalysis.request = task;
        regionalAnalysis.height = task.height;
        regionalAnalysis.north = task.north;
        regionalAnalysis.west = task.west;
        regionalAnalysis.width = task.width;

        regionalAnalysis.accessGroup = accessGroup;
        regionalAnalysis.bundleId = project.bundleId;
        regionalAnalysis.createdBy = email;
        regionalAnalysis.destinationPointSetIds = analysisRequest.destinationPointSetIds;
        regionalAnalysis.name = analysisRequest.name;
        regionalAnalysis.projectId = analysisRequest.projectId;
        regionalAnalysis.regionId = project.regionId;
        regionalAnalysis.variant = analysisRequest.variantIndex;
        regionalAnalysis.workerVersion = analysisRequest.workerVersion;
        regionalAnalysis.zoom = task.zoom;

        // Store the full array of multiple cutoffs which will be understood by newer workers and backends,
        // rather then the older single cutoff value.
        regionalAnalysis.cutoffsMinutes = analysisRequest.cutoffsMinutes;
        if (analysisRequest.cutoffsMinutes.length == 1) {
            // Ensure older workers expecting a single cutoff will still function.
            regionalAnalysis.cutoffMinutes = analysisRequest.cutoffsMinutes[0];
        } else {
            // Store invalid value in deprecated field (-1 was already used) to make it clear it should not be used.
            regionalAnalysis.cutoffMinutes = -2;
        }

        // Same process as for cutoffsMinutes, but for percentiles.
        regionalAnalysis.travelTimePercentiles = analysisRequest.percentiles;
        if (analysisRequest.percentiles.length == 1) {
            regionalAnalysis.travelTimePercentile = analysisRequest.percentiles[0];
        } else {
            regionalAnalysis.travelTimePercentile = -2;
        }

        // Persist this newly created RegionalAnalysis to Mongo, which assigns it an id and creation/update time stamps.
        regionalAnalysis = Persistence.regionalAnalyses.create(regionalAnalysis);
        // Paths require an `_id`, create and store after initial persistence generates an `_id`.
        if (analysisRequest.recordTimes) regionalAnalysis.addCsvStoragePath(Result.TIMES);
        if (analysisRequest.recordPaths) regionalAnalysis.addCsvStoragePath(Result.PATHS);
        if (analysisRequest.recordAccessibility) regionalAnalysis.addCsvStoragePath(Result.ACCESS);
        Persistence.regionalAnalyses.modifiyWithoutUpdatingLock(regionalAnalysis);

        // Register the regional job with the broker, which will distribute individual tasks to workers and track progress.
        broker.enqueueTasksForRegionalJob(regionalAnalysis);

        return regionalAnalysis;
    }

    private RegionalAnalysis updateRegionalAnalysis(Request request, Response response) throws IOException {
        final String accessGroup = request.attribute("accessGroup");
        final String email = request.attribute("email");
        RegionalAnalysis regionalAnalysis = JsonUtil.objectMapper.readValue(request.body(), RegionalAnalysis.class);
        return Persistence.regionalAnalyses.updateByUserIfPermitted(regionalAnalysis, email, accessGroup);
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/region", () -> {
            sparkService.get("/:regionId/regional", this::getRegionalAnalysesForRegion, toJson);
            sparkService.get("/:regionId/regional/running", this::getRunningAnalyses, toJson);
        });
        sparkService.path("/api/regional", () -> {
            // For grids, no transformer is supplied: render raw bytes or input stream rather than transforming to JSON.
            sparkService.get("/:_id", this::getRegionalAnalysis);
            sparkService.get("/:_id/grid/:format", this::getRegionalResults);
            sparkService.get("/:_id/csv/:resultType", this::getCsvResults);
            sparkService.delete("/:_id", this::deleteRegionalAnalysis, toJson);
            sparkService.post("", this::createRegionalAnalysis, toJson);
            sparkService.put("/:_id", this::updateRegionalAnalysis, toJson);
        });
    }

}

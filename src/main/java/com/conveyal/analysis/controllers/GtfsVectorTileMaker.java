package com.conveyal.analysis.controllers;

import com.conveyal.analysis.util.MapTile;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.util.GeometryUtil;
import com.conveyal.r5.common.GeometryUtils;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtEncoder;
import com.wdtinc.mapbox_vector_tile.adapt.jts.UserDataKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerParams;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * This class maintains a spatial index of data inside GTFS feeds and produces vector tiles of it for use as a
 * preview display. It is used by GtfsController, which defines the associated HTTP endpoints.
 *
 * For testing, find tile numbers with https://www.maptiler.com/google-maps-coordinates-tile-bounds-projection/
 * Examine and analyze individual tiles with https://observablehq.com/@henrythasler/mapbox-vector-tile-dissector
 *
 * The tile format specification is at https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 * To summarize:
 * Extension should be .mvt and MIME content type application/vnd.mapbox-vector-tile
 * A Vector Tile represents data based on a square extent within a projection.
 * Vector Tiles may be used to represent data with any projection and tile extent scheme.
 * The reference projection is Web Mercator; the reference tile scheme is Google's.
 * The tile should not contain information about its bounds and projection.
 * The decoder already knows the bounds and projection of a Vector Tile it requested when decoding it.
 * A Vector Tile should contain at least one layer, and a layer should contain at least one feature.
 * Feature coordinates are integers relative to the top left of the tile.
 * The extent of a tile is the number of integer units across its width and height, typically 4096.
 *
 * TODO handle cancellation of HTTP requests (Mapbox client cancels requests when zooming/panning)
 */
public class GtfsVectorTileMaker {

    private static final Logger LOG = LoggerFactory.getLogger(GtfsVectorTileMaker.class);

    private final GTFSCache gtfsCache;

    /**
     * Complex street geometries will be simplified, but the geometry will not deviate from the original by more
     * than this many tile units. These are minuscule at 1/4096 of the tile width or height.
     */
    private static final int LINE_SIMPLIFY_TOLERANCE = 5;

    /** How long after it was last accessed to keep a GTFS spatial index in memory. */
    private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    /** The maximum number of feeds for which we keep GTFS spatial indexes in memory at once. */
    private static final int MAX_SPATIAL_INDEXES = 4;

    /** A cache of spatial indexes of the trip pattern shapes, keyed on the BundleScopedFeedId. */
    private final LoadingCache<String, STRtree> shapeIndexCache = Caffeine.newBuilder()
            .maximumSize(MAX_SPATIAL_INDEXES)
            .expireAfterAccess(EXPIRE_AFTER_ACCESS)
            .removalListener(this::logCacheEviction)
            .build(this::buildShapesIndex);

    /** A cache of spatial indexes of the transit stops, keyed on the BundleScopedFeedId. */
    private final LoadingCache<String, STRtree> stopIndexCache = Caffeine.newBuilder()
            .maximumSize(MAX_SPATIAL_INDEXES)
            .expireAfterAccess(EXPIRE_AFTER_ACCESS)
            .removalListener(this::logCacheEviction)
            .build(this::buildStopsIndex);

    /** Constructor taking a GTFSCache component so we can look up feeds by ID as needed. */
    public GtfsVectorTileMaker (GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
    }

    /** CacheLoader implementation making spatial indexes of stop pattern shapes for a single feed. */
    private STRtree buildShapesIndex (String bundleScopedId) {
        final long startTimeMs = System.currentTimeMillis();
        GTFSFeed feed = gtfsCache.get(bundleScopedId);
        STRtree shapesIndex = new STRtree();
        // This is huge, we can instead map from envelopes to tripIds, but re-fetching those trips is slow
        LOG.info("{}: indexing {} patterns", bundleScopedId, feed.patterns.size());
        for (Pattern pattern : feed.patterns.values()) {
            Route route = feed.routes.get(pattern.route_id);
            String exemplarTripId = pattern.associatedTrips.get(0);
            LineString wgsGeometry = feed.getTripGeometry(exemplarTripId);
            if (wgsGeometry == null) {
                // Not sure why some of these are null.
                continue;
            }
            Map<String, Object> userData = new HashMap<>();
            userData.put("id", pattern.pattern_id);
            userData.put("name", pattern.name);
            userData.put("routeId", route.route_id);
            userData.put("routeName", route.route_long_name);
            userData.put("routeColor", Objects.requireNonNullElse(route.route_color, "000000"));
            userData.put("routeType", route.route_type);
            wgsGeometry.setUserData(userData);
            shapesIndex.insert(wgsGeometry.getEnvelopeInternal(), wgsGeometry);
        }
        shapesIndex.build();
        LOG.info("Created vector tile spatial index for patterns in feed {} ({} ms)", bundleScopedId, System.currentTimeMillis() - startTimeMs);
        return shapesIndex;
    }

    /** CacheLoader implementation making spatial indexes of transit stops for a single feed. */
    private STRtree buildStopsIndex (String bundleScopedId) {
        final long startTimeMs = System.currentTimeMillis();
        GTFSFeed feed = gtfsCache.get(bundleScopedId);
        STRtree stopsIndex = new STRtree();
        LOG.info("{}: indexing {} stops", bundleScopedId, feed.stops.size());
        for (Stop stop : feed.stops.values()) {
            // This is inefficient, TODO specialized spatial index to bin points into mercator tiles (like hashgrid).
            Envelope stopEnvelope = new Envelope(stop.stop_lon, stop.stop_lon, stop.stop_lat, stop.stop_lat);
            stopsIndex.insert(stopEnvelope, stop);
        }
        stopsIndex.build();
        LOG.info("Creating vector tile spatial index for stops in feed {} ({} ms)", bundleScopedId, System.currentTimeMillis() - startTimeMs);
        return stopsIndex;
    }

    /** RemovalListener triggered when a spatial index is evicted from the cache. */
    private void logCacheEviction (String feedId, STRtree value, RemovalCause cause) {
        LOG.info("Vector tile spatial index removed. Feed {}, cause {}.", feedId, cause);
    }

    /** Produce a single Mapbox vector tile for the given unique GTFS feed ID and tile coordinates. */
    public byte[] getTile (String bundleScopedFeedId, int zTile, int xTile, int yTile) {

        // It might make sense to unify these two values into a single compound object.
        STRtree shapesIndex = shapeIndexCache.get(bundleScopedFeedId);
        STRtree stopsIndex = stopIndexCache.get(bundleScopedFeedId);

        final long startTimeMs = System.currentTimeMillis();
        final int tileExtent = 4096; // Standard is 4096, smaller can in theory make tiles more compact
        Envelope wgsEnvelope = MapTile.wgsEnvelope(zTile, xTile, yTile);
        Collection<Geometry> patternGeoms = new ArrayList<>(64);
        for (LineString wgsGeometry : (List<LineString>) shapesIndex.query(wgsEnvelope)) {
            Geometry tileGeometry = clipScaleAndSimplify(wgsGeometry, wgsEnvelope, tileExtent);
            if (tileGeometry != null) {
                patternGeoms.add(tileGeometry);
            }
        }
        JtsLayer patternLayer = new JtsLayer("conveyal:gtfs:patternShapes", patternGeoms, tileExtent);

        Collection<Geometry> stopGeoms = new ArrayList<>(64);
        for (Stop stop : ((List<Stop>) stopsIndex.query(wgsEnvelope))) {
            Coordinate wgsStopCoord = new Coordinate(stop.stop_lon, stop.stop_lat);
            if (!wgsEnvelope.contains(wgsStopCoord)) {
                continue;
            }

            Map<String, Object> properties = new HashMap<>();
            properties.put("feedId", stop.feed_id);
            properties.put("id", stop.stop_id);
            properties.put("name", stop.stop_name);
            properties.put("lat", stop.stop_lat);
            properties.put("lon", stop.stop_lon);

            // Factor out this duplicate code from clipScaleAndSimplify
            Coordinate tileCoord = wgsStopCoord.copy();
            tileCoord.x = ((tileCoord.x - wgsEnvelope.getMinX()) * tileExtent) / wgsEnvelope.getWidth();
            tileCoord.y = ((wgsEnvelope.getMaxY() - tileCoord.y) * tileExtent) / wgsEnvelope.getHeight();

            Point point =  GeometryUtils.geometryFactory.createPoint(tileCoord);
            point.setUserData(properties);
            stopGeoms.add(point);
        }
        JtsLayer stopsLayer = new JtsLayer("conveyal:gtfs:stops", stopGeoms, tileExtent);

        // Combine these two layers in a tile
        JtsMvt mvt = new JtsMvt(patternLayer, stopsLayer);
        MvtLayerParams mvtLayerParams = new MvtLayerParams(256, tileExtent);
        byte[] pbfMessage = MvtEncoder.encode(mvt, mvtLayerParams, new UserDataKeyValueMapConverter());
        // LOG.info("GTFS vector tile for feed {} ({} {} {}) took {} ms size {} bytes", bundleScopedFeedId, zTile, xTile, yTile, System.currentTimeMillis() - startTimeMs, pbfMessage.length);
        return pbfMessage;
    }

    /**
     * Utility method reusable in other classes that produce vector tiles. Convert from WGS84 to integer intra-tile
     * coordinates, eliminating points outside the envelope and reducing number of points to keep tile size down.
     *
     * We don't want to include all points of huge geometries when only a small piece of them passes through the tile.
     * This kind of clipping is considered a "standard" but is not technically part of the vector tile specification.
     * See: https://docs.mapbox.com/data/tilesets/guides/vector-tiles-standards/#clipping
     *
     * To handle cases where a geometry passes outside the tile and then back in (e.g. U-shaped sections of routes) we
     * break the geometry into separate pieces where it passes outside the tile.
     *
     * However this is done, we have to make sure the segments end far enough outside the tile that they don't leave
     * artifacts inside the tile, accounting for line width, endcaps etc. so we apply a margin or buffer when deciding
     * which points are outside the tile.
     *
     * We used to use a simpler method where only line segments fully outside the tile were skipped, and originally the
     * "pen" was not even lifted if a line exited the tile and re-entered. However this does not work well with shapes
     * that have very widely spaced points, as it creates huge invisible sections outside the tile clipping area and
     * appears to trigger some coordinate overflow or other problem in the serialization or rendering. It also required
     * manual detection and handling of cases where all points were outside the clip area but the line passed through.
     *
     * Therefore we apply JTS clipping to every feature and sometimes return MultiLineString GeometryCollections
     * which will yield several separate sequences of pen movements in the resulting vector tile. JtsMvt and MvtEncoder
     * don't seem to understand general GeometryCollections, but do interpret MultiLineStrings as expected.
     *
     * @return a Geometry representing the input wgsGeometry in tile units, clipped to the wgsEnvelope with a margin.
     *         The Geometry should always be a LineString or MultiLineString, or null if the geometry has no points
     *         inside the tile.
     */
    public static Geometry clipScaleAndSimplify (LineString wgsGeometry, Envelope wgsEnvelope, int tileExtent) {
        // Add a 5% margin to the envelope make sure any artifacts are outside it.
        // This takes care of the fact that lines have endcaps and widths.
        // Unfortunately this is copying the envelope and adding a margin each time this method is called, so once
        // for each individual geometry within the tile. We can't just add the margin to the envelope before
        // passing it in because we need the true envelope when projecting all the coordinates into tile units.
        // We may want to refactor to process a whole collection of geometries at once to avoid this problem.
        Geometry clippedWgsGeometry;
        {
            final double bufferProportion = 0.05;
            Envelope wgsEnvWithMargin = wgsEnvelope.copy(); // Protective copy before adding margin in place.
            wgsEnvWithMargin.expandBy(
                    wgsEnvelope.getWidth() * bufferProportion,
                    wgsEnvelope.getHeight() * bufferProportion
            );
            clippedWgsGeometry = GeometryUtil.geometryFactory.toGeometry(wgsEnvWithMargin).intersection(wgsGeometry);
        }
        // Iterate over clipped geometry in case clipping broke the LineString into a MultiLineString
        // or reduced it to nothing (zero elements). Self-intersecting geometries can yield a larger number of elements.
        // For example, DC metro GTFS has notches at stops, which produce a lot of 5-10 element MultiLineStrings.
        List<LineString> outputLineStrings = new ArrayList<>(clippedWgsGeometry.getNumGeometries());
        for (int g = 0; g < clippedWgsGeometry.getNumGeometries(); g += 1) {
            LineString wgsSubGeom = (LineString) clippedWgsGeometry.getGeometryN(g);
            CoordinateSequence wgsCoordinates = wgsSubGeom.getCoordinateSequence();
            if (wgsCoordinates.size() < 2) {
                continue;
            }
            List<Coordinate> tileCoordinates = new ArrayList<>(wgsCoordinates.size());
            for (int c = 0; c < wgsCoordinates.size(); c += 1) {
                // Protective copy before projecting Coordinate.
                Coordinate coord = wgsCoordinates.getCoordinateCopy(c);
                // JtsAdapter.createTileGeom clips and uses full JTS math transform and is much too slow.
                // The following seems sufficient - tile edges should be parallel to lines of latitude and longitude.
                coord.x = ((coord.x - wgsEnvelope.getMinX()) * tileExtent) / wgsEnvelope.getWidth();
                coord.y = ((wgsEnvelope.getMaxY() - coord.y) * tileExtent) / wgsEnvelope.getHeight();
                tileCoordinates.add(coord);
            }
            LineString tileLineString = GeometryUtils.geometryFactory.createLineString(
                    tileCoordinates.toArray(new Coordinate[0])
            );
            DouglasPeuckerSimplifier simplifier = new DouglasPeuckerSimplifier(tileLineString);
            simplifier.setDistanceTolerance(LINE_SIMPLIFY_TOLERANCE);
            Geometry simplifiedTileGeometry = simplifier.getResultGeometry();
            outputLineStrings.add((LineString) simplifiedTileGeometry);
        }
        // Clipping will frequently leave zero elements.
        if (outputLineStrings.isEmpty()) {
            return null;
        }
        Geometry output;
        if (outputLineStrings.size() == 1) {
            output = outputLineStrings.get(0);
        } else {
            output = GeometryUtils.geometryFactory.createMultiLineString(
                outputLineStrings.toArray(new LineString[outputLineStrings.size()])
            );
        }
        // Copy attributes for display from input WGS geometry to output tile geometry.
        output.setUserData(wgsGeometry.getUserData());
        return output;
    }
}
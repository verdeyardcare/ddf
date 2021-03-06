/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package ddf.catalog.source.opensearch.impl;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.cxf.jaxrs.client.WebClient;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.opengis.filter.expression.PropertyName;
import org.opengis.filter.sort.SortBy;
import org.opengis.filter.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ddf.catalog.data.Result;
import ddf.catalog.impl.filter.SpatialDistanceFilter;
import ddf.catalog.impl.filter.SpatialFilter;
import ddf.catalog.impl.filter.TemporalFilter;
import ddf.catalog.operation.Query;
import ddf.catalog.operation.QueryRequest;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.catalog.source.opensearch.OpenSearchParser;
import ddf.security.Subject;
import ddf.security.assertion.SecurityAssertion;

public class OpenSearchParserImpl implements OpenSearchParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenSearchParserImpl.class);

    private static final String START_INDEX = "start";

    // geospatial constants
    private static final double LAT_DEGREE_M = 111325;

    private static final Integer MAX_LAT = 90;

    private static final Integer MIN_LAT = -90;

    private static final Integer MAX_LON = 180;

    private static final Integer MIN_LON = -180;

    private static final Integer MAX_ROTATION = 360;

    private static final Integer MAX_BBOX_POINTS = 4;

    private static final String ORDER_ASCENDING = "asc";

    private static final String ORDER_DESCENDING = "desc";

    private static final String SORT_DELIMITER = ":";

    private static final String SORT_RELEVANCE = "relevance";

    private static final String SORT_TEMPORAL = "date";

    // OpenSearch defined parameters
    public static final String SEARCH_TERMS = "q";

    // temporal
    static final String TIME_START = "dtstart";

    static final String TIME_END = "dtend";

    static final String TIME_NAME = "dateName";

    // geospatial
    static final String GEO_LAT = "lat";

    static final String GEO_LON = "lon";

    static final String GEO_RADIUS = "radius";

    static final String GEO_POLY = "polygon";

    static final String GEO_BBOX = "bbox";

    // general options
    static final String SRC = "src";

    static final String MAX_RESULTS = "mr";

    static final String COUNT = "count";

    static final String MAX_TIMEOUT = "mt";

    static final String USER_DN = "dn";

    static final String SORT = "sort";

    static final String FILTER = "filter";

    static final Integer DEFAULT_TOTAL_MAX = 1000;

    @Override
    public void populateSearchOptions(WebClient client, QueryRequest queryRequest, Subject subject,
            List<String> parameters) {
        String maxTotalSize = null;
        String maxPerPage = null;
        String routeTo = "";
        String timeout = null;
        String start = "1";
        String dn = null;
        String filterStr = "";
        String sortStr = null;

        if (queryRequest != null) {
            Query query = queryRequest.getQuery();

            if (query != null) {
                maxPerPage = String.valueOf(query.getPageSize());
                if (query.getPageSize() > DEFAULT_TOTAL_MAX) {
                    maxTotalSize = maxPerPage;
                } else if (query.getPageSize() <= 0) {
                    maxTotalSize = String.valueOf(DEFAULT_TOTAL_MAX);
                }

                start = Integer.toString(query.getStartIndex());

                timeout = Long.toString(query.getTimeoutMillis());

                sortStr = translateToOpenSearchSort(query.getSortBy());

                if (subject != null && subject.getPrincipals() != null && !subject.getPrincipals()
                        .isEmpty()) {
                    List principals = subject.getPrincipals()
                            .asList();
                    for (Object principal : principals) {
                        if (principal instanceof SecurityAssertion) {
                            SecurityAssertion assertion = (SecurityAssertion) principal;
                            Principal assertionPrincipal = assertion.getPrincipal();
                            if (assertionPrincipal != null) {
                                dn = assertionPrincipal.getName();
                            }
                        }
                    }
                }
            }
        }

        checkAndReplace(client, start, START_INDEX, parameters);
        checkAndReplace(client, maxPerPage, COUNT, parameters);
        checkAndReplace(client, maxTotalSize, MAX_RESULTS, parameters);
        checkAndReplace(client, routeTo, SRC, parameters);
        checkAndReplace(client, timeout, MAX_TIMEOUT, parameters);
        checkAndReplace(client, dn, USER_DN, parameters);
        checkAndReplace(client, filterStr, FILTER, parameters);
        checkAndReplace(client, sortStr, SORT, parameters);
    }

    @Override
    public void populateContextual(WebClient client, Map<String, String> searchPhraseMap,
            List<String> parameters) {
        if (searchPhraseMap != null) {
            String queryStr = searchPhraseMap.get(SEARCH_TERMS);
            if (queryStr != null) {
                try {
                    queryStr = URLEncoder.encode(queryStr, "UTF-8");
                } catch (UnsupportedEncodingException uee) {
                    LOGGER.debug("Could not encode contextual string", uee);
                }
            }
            checkAndReplace(client, queryStr, SEARCH_TERMS, parameters);
        }
    }

    @Override
    public void populateTemporal(WebClient client, TemporalFilter temporal,
            List<String> parameters) {
        DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
        String start = "";
        String end = "";
        String name = "";
        if (temporal != null) {
            long startLng = (temporal.getStartDate() != null) ?
                    temporal.getStartDate()
                            .getTime() :
                    0;
            start = fmt.print(startLng);
            long endLng = (temporal.getEndDate() != null) ?
                    temporal.getEndDate()
                            .getTime() :
                    System.currentTimeMillis();
            end = fmt.print(endLng);
        }
        checkAndReplace(client, start, TIME_START, parameters);
        checkAndReplace(client, end, TIME_END, parameters);
        checkAndReplace(client, name, TIME_NAME, parameters);
    }

    @Override
    public void populateGeospatial(WebClient client, SpatialDistanceFilter spatial,
            boolean shouldConvertToBBox, List<String> parameters) throws UnsupportedQueryException {
        String lat = "";
        String lon = "";
        String radiusStr = "";
        StringBuilder bbox = new StringBuilder();
        StringBuilder poly = new StringBuilder();

        if (spatial != null) {
            String wktStr = spatial.getGeometryWkt();
            double radius = spatial.getDistanceInMeters();

            if (wktStr.contains("POINT")) {
                String[] latLon = createLatLonAryFromWKT(wktStr);
                lon = latLon[0];
                lat = latLon[1];
                radiusStr = Double.toString(radius);
                if (shouldConvertToBBox) {
                    double[] bboxCoords = createBBoxFromPointRadius(Double.parseDouble(lon),
                            Double.parseDouble(lat),
                            radius);
                    for (int i = 0; i < MAX_BBOX_POINTS; i++) {
                        if (i > 0) {
                            bbox.append(",");
                        }
                        bbox.append(bboxCoords[i]);
                    }
                    lon = "";
                    lat = "";
                    radiusStr = "";
                }
            } else {
                LOGGER.debug("WKT ({}) not supported for POINT-RADIUS search, use POINT.", wktStr);
            }
        }

        checkAndReplace(client, lat, GEO_LAT, parameters);
        checkAndReplace(client, lon, GEO_LON, parameters);
        checkAndReplace(client, radiusStr, GEO_RADIUS, parameters);
        checkAndReplace(client, poly.toString(), GEO_POLY, parameters);
        checkAndReplace(client, bbox.toString(), GEO_BBOX, parameters);
    }

    @Override
    public void populateGeospatial(WebClient client, SpatialFilter spatial,
            boolean shouldConvertToBBox, List<String> parameters) throws UnsupportedQueryException {
        String lat = "";
        String lon = "";
        String radiusStr = "";
        StringBuilder bbox = new StringBuilder();
        StringBuilder poly = new StringBuilder();

        if (spatial != null) {
            String wktStr = spatial.getGeometryWkt();
            if (wktStr.contains("POLYGON")) {
                String[] polyAry = createPolyAryFromWKT(wktStr);
                if (shouldConvertToBBox) {
                    double[] bboxCoords = createBBoxFromPolygon(polyAry);
                    for (int i = 0; i < MAX_BBOX_POINTS; i++) {
                        if (i > 0) {
                            bbox.append(",");
                        }
                        bbox.append(bboxCoords[i]);
                    }
                } else {
                    for (int i = 0; i < polyAry.length - 1; i += 2) {
                        if (i != 0) {
                            poly.append(",");
                        }
                        poly.append(polyAry[i + 1]);
                        poly.append(",");
                        poly.append(polyAry[i]);
                    }
                }
            } else {
                LOGGER.debug("WKT ({}) not supported for SPATIAL search, use POLYGON.", wktStr);
            }
        }

        checkAndReplace(client, lat, GEO_LAT, parameters);
        checkAndReplace(client, lon, GEO_LON, parameters);
        checkAndReplace(client, radiusStr, GEO_RADIUS, parameters);
        checkAndReplace(client, poly.toString(), GEO_POLY, parameters);
        checkAndReplace(client, bbox.toString(), GEO_BBOX, parameters);
    }

    /**
     * Parses a WKT polygon string and returns a string array containing the lon and lat.
     *
     * @param wkt WKT String in the form of POLYGON((Lon Lat, Lon Lat...))
     * @return Lon on even # and Lat on odd #
     */
    private String[] createPolyAryFromWKT(String wkt) {
        String lonLat = wkt.substring(wkt.indexOf("((") + 2, wkt.indexOf("))"));
        return lonLat.split(" |,\\p{Space}?");
    }

    /**
     * Parses a WKT Point string and returns a string array containing the lon and lat.
     *
     * @param wkt WKT String in the form of POINT( Lon Lat)
     * @return Lon at position 0, Lat at position 1
     */
    private String[] createLatLonAryFromWKT(String wkt) {
        String lonLat = wkt.substring(wkt.indexOf('(') + 1, wkt.indexOf(')'));
        return lonLat.split(" ");
    }

    /**
     * Checks the input and replaces the items inside of the url.
     *
     * @param client     The URL to do the replacement on. <b>NOTE:</b> replacement is done directly on
     *                   this object.
     * @param inputStr   Item to put into the URL.
     * @param definition Area inside of the URL to be replaced by.
     */
    private void checkAndReplace(WebClient client, String inputStr, String definition,
            List<String> parameters) {
        if (hasParameter(definition, parameters) && StringUtils.isNotEmpty(inputStr)) {
            client.replaceQueryParam(definition, inputStr);
        }
    }

    private boolean hasParameter(String parameter, List<String> parameters) {
        for (String param : parameters) {
            if (param != null && param.equalsIgnoreCase(parameter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes in a point radius search and converts it to a (rough approximation) bounding box.
     *
     * @param lon    latitude in decimal degrees (WGS-84)
     * @param lat    longitude in decimal degrees (WGS-84)
     * @param radius radius, in meters
     * @return Array of bounding box coordinates in the following order: West South East North. Also
     * described as minX, minY, maxX, maxY (where longitude is the X-axis, and latitude is
     * the Y-axis).
     */
    private double[] createBBoxFromPointRadius(double lon, double lat, double radius) {
        double minX;
        double minY;
        double maxX;
        double maxY;

        double lonDifference = radius / (LAT_DEGREE_M * Math.cos(lat));
        double latDifference = radius / LAT_DEGREE_M;
        minX = lon - lonDifference;
        if (minX < MIN_LON) {
            minX += MAX_ROTATION;
        }
        maxX = lon + lonDifference;
        if (maxX > MAX_LON) {
            maxX -= MAX_ROTATION;
        }
        minY = lat - latDifference;
        if (minY < MIN_LAT) {
            minY = Math.abs(minY + MAX_LAT) - MAX_LAT;
        }
        maxY = lat + latDifference;
        if (maxY > MAX_LAT) {
            maxY = MAX_LAT - (maxY - MAX_LAT);
        }

        return new double[] {minX, minY, maxX, maxY};
    }

    /**
     * Takes in an array of coordinates and converts it to a (rough approximation) bounding box.
     * <p/>
     * Note: Searches being performed where the polygon goes through the international date line may
     * return a bad bounding box.
     *
     * @param polyAry array of coordinates (lon,lat,lon,lat,lon,lat..etc)
     * @return Array of bounding box coordinates in the following order: West South East North. Also
     * described as minX, minY, maxX, maxY (where longitude is the X-axis, and latitude is
     * the Y-axis).
     */
    private double[] createBBoxFromPolygon(String[] polyAry) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        double curX, curY;
        for (int i = 0; i < polyAry.length - 1; i += 2) {
            LOGGER.debug("polyToBBox: lon - {} lat - {}", polyAry[i], polyAry[i + 1]);
            curX = Double.parseDouble(polyAry[i]);
            curY = Double.parseDouble(polyAry[i + 1]);
            if (curX < minX) {
                minX = curX;
            }
            if (curX > maxX) {
                maxX = curX;
            }
            if (curY < minY) {
                minY = curY;
            }
            if (curY > maxY) {
                maxY = curY;
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    private String translateToOpenSearchSort(SortBy ddfSort) {
        String openSearchSortStr = null;
        String orderType;

        if (ddfSort == null || ddfSort.getSortOrder() == null) {
            return null;
        }

        if (ddfSort.getSortOrder()
                .equals(SortOrder.ASCENDING)) {
            orderType = ORDER_ASCENDING;
        } else {
            orderType = ORDER_DESCENDING;
        }

        PropertyName sortByField = ddfSort.getPropertyName();

        if (Result.RELEVANCE.equals(sortByField.getPropertyName())) {
            // asc relevance not supported by spec
            openSearchSortStr = SORT_RELEVANCE + SORT_DELIMITER + ORDER_DESCENDING;
        } else if (Result.TEMPORAL.equals(sortByField.getPropertyName())) {
            openSearchSortStr = SORT_TEMPORAL + SORT_DELIMITER + orderType;
        } else {
            LOGGER.debug(
                    "Couldn't determine sort policy, not adding sorting in request to federated site.");
        }

        return openSearchSortStr;
    }

}

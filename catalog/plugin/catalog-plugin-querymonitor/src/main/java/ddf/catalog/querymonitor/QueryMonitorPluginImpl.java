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
package ddf.catalog.querymonitor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ddf.catalog.operation.QueryRequest;
import ddf.catalog.operation.QueryResponse;
import ddf.catalog.plugin.PluginExecutionException;
import ddf.catalog.plugin.StopProcessingException;
import ddf.catalog.source.Source;

public class QueryMonitorPluginImpl implements QueryMonitorPlugin {

    private boolean removeSearchAfterComplete = true;

    private ConcurrentHashMap<UUID, ActiveSearch> activeSearches = new ConcurrentHashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryMonitorPluginImpl.class);

    public static final String SEARCH_ID = "SEARCH_ID";

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<UUID, ActiveSearch> getActiveSearches() {
        return activeSearches;
    }

    /**
     * {@inheritDoc}
     */
    public void setRemoveSearchAfterComplete(boolean b) {
        removeSearchAfterComplete = b;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addActiveSearch(ActiveSearch as) {
        if (as == null) {
            LOGGER.warn("Cannot add null ActiveSearch to map.");
            return false;
        }
        activeSearches.put(as.getUniqueID(), as);
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeActiveSearch(UUID uniqueID) {
        if (uniqueID == null) {
            LOGGER.warn("Can't remove active search with null ID.");
            return false;
        }
        return (activeSearches.remove(uniqueID) != null);
    }

    /**
     * Method that is implemented for {@link PreFederatedQueryPlugin}. Uses the given {@link Source}
     * and {@link QueryRequest} information to create a new {@link ActiveSearch} to add to the {@link ActiveSearch} {@link Map}.
     *
     * @param source {@link Source} that corresponds to source the search is querying
     * @param input  {@link QueryRequest} that corresponds to request generated when a user queried the source
     * @return {@link QueryRequest} that was given as a parameter with updated property information corresponding
     * to the {@link ActiveSearch}'s {@link UUID}
     */

    @Override
    public QueryRequest process(Source source, QueryRequest input)
            throws PluginExecutionException, StopProcessingException {
        if (source == null) {
            LOGGER.warn("Source given was null.");
        }
        if (input == null) {
            LOGGER.error("QueryRequest in process was null. Cannot add active search to map.");
        } else {
            ActiveSearch tempAS = new ActiveSearch(source, input);
            UUID uniqueID = tempAS.getUniqueID();
            input.getProperties()
                    .put(SEARCH_ID, uniqueID);
            addActiveSearch(tempAS);
        }

        return input;
    }

    /**
     * Method that is implemented for {@link PostFederatedQueryPlugin}. Uses the given {@link QueryResponse} information
     * to remove the {@link ActiveSearch} from the {@link ActiveSearch} {@link Map}.
     *
     * @param input {@link QueryResponse} that corresponds to response from the source that was queried
     *              by the user's original {@link QueryRequest}
     * @return {@link QueryResponse} that was given as a parameter
     */
    @Override
    public QueryResponse process(QueryResponse input)
            throws PluginExecutionException, StopProcessingException {

        if (!removeSearchAfterComplete) {
            LOGGER.debug(
                    "Not removing active search from map due to catalog:removeSearchAfterComplete false. To enable removing searches as searches finish, use command catalog:removesearchaftercomplete true.");
            return input;
        }
        if (input == null) {
            LOGGER.warn(
                    "Cannot remove ActiveSearch from the ActiveSearch Map. QueryResponse received in QueryMonitorPluginImpl was null.");
            return input;
        }
        removeActiveSearch((UUID) input.getRequest()
                .getPropertyValue(SEARCH_ID));
        return input;
    }
}

/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.catalog.ui.metacard.workspace.transformer.impl;

import ddf.catalog.CatalogFramework;
import ddf.catalog.data.Metacard;
import ddf.catalog.operation.impl.CreateRequestImpl;
import ddf.catalog.operation.impl.DeleteRequestImpl;
import ddf.catalog.operation.impl.UpdateRequestImpl;
import ddf.catalog.source.IngestException;
import ddf.catalog.source.SourceUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.codice.ddf.catalog.ui.metacard.workspace.ListMetacardImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.ListMetacardTypeImpl;

public class AssociatedListMetacardsHandler {

  private final CatalogFramework catalogFramework;

  private static final Set<String> LIST_ATTRIBUTE_NAMES = ListMetacardTypeImpl.LIST_ATTRIBUTE_NAMES;

  public AssociatedListMetacardsHandler(CatalogFramework catalogFramework) {
    this.catalogFramework = catalogFramework;
  }

  public void create(List<String> existingListIds, List<Metacard> updatedListMetacards)
      throws IngestException, SourceUnavailableException {
    List<Metacard> createMetacards =
        updatedListMetacards
            .stream()
            .filter(Objects::nonNull)
            .filter(list -> !existingListIds.contains(list.getId()))
            .map(ListMetacardImpl::new)
            .collect(Collectors.toList());
    if (!createMetacards.isEmpty()) {
      catalogFramework.create(new CreateRequestImpl(createMetacards));
    }
  }

  public void delete(List<String> existingListIds, List<String> updatedListIds)
      throws IngestException, SourceUnavailableException {
    String[] deleteIds =
        existingListIds
            .stream()
            .filter(Objects::nonNull)
            .filter(listId -> !updatedListIds.contains(listId))
            .toArray(String[]::new);
    if (deleteIds.length > 0) {
      catalogFramework.delete(new DeleteRequestImpl(deleteIds));
    }
  }

  public void update(
      List<String> existingListIds,
      List<ListMetacardImpl> existingListMetacards,
      List<Metacard> updatedListMetacards)
      throws IngestException, SourceUnavailableException {
    Map<String, ListMetacardImpl> existingListMap =
        existingListMetacards
            .stream()
            .collect(Collectors.toMap(ListMetacardImpl::getId, list -> list));
    List<Metacard> updateMetacards =
        updatedListMetacards
            .stream()
            .filter(list -> existingListIds.contains(list.getId()))
            .filter(list -> hasChanges(existingListMap.get(list.getId()), list))
            .map(ListMetacardImpl::new)
            .collect(Collectors.toList());
    if (!updateMetacards.isEmpty()) {
      String[] updateMetacardIds =
          updateMetacards.stream().map(Metacard::getId).toArray(String[]::new);
      catalogFramework.update(new UpdateRequestImpl(updateMetacardIds, updateMetacards));
    }
  }

  private boolean hasChanges(Metacard existing, Metacard updated) {
    Optional<String> difference =
        LIST_ATTRIBUTE_NAMES
            .stream()
            .filter(
                attributeName ->
                    !Objects.equals(
                        existing.getAttribute(attributeName), updated.getAttribute(attributeName)))
            .findFirst();
    return difference.isPresent();
  }
}

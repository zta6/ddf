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
package org.codice.ddf.catalog.ui.metacard;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.codice.ddf.catalog.ui.metacard.workspace.ListMetacardTypeImpl.LIST_TAG;
import static org.codice.gsonsupport.GsonTypeAdapters.MAP_STRING_TO_OBJECT_TYPE;
import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.post;
import static spark.Spark.put;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ddf.catalog.CatalogFramework;
import ddf.catalog.Constants;
import ddf.catalog.content.data.impl.ContentItemImpl;
import ddf.catalog.content.operation.CreateStorageRequest;
import ddf.catalog.content.operation.impl.CreateStorageRequestImpl;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.Result;
import ddf.catalog.operation.CreateResponse;
import ddf.catalog.operation.impl.DeleteRequestImpl;
import ddf.catalog.source.IngestException;
import ddf.catalog.source.SourceUnavailableException;
import ddf.mime.MimeTypeMapper;
import ddf.mime.MimeTypeResolutionException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.codice.ddf.attachment.AttachmentInfo;
import org.codice.ddf.catalog.ui.metacard.impl.StorableResourceImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.ListMetacardImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceMetacardImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.transformer.WorkspaceTransformer;
import org.codice.ddf.catalog.ui.query.monitor.api.WorkspaceService;
import org.codice.ddf.catalog.ui.splitter.Splitter;
import org.codice.ddf.catalog.ui.splitter.SplitterLocator;
import org.codice.ddf.catalog.ui.splitter.StopSplitterExecutionException;
import org.codice.ddf.catalog.ui.splitter.StorableResource;
import org.codice.ddf.catalog.ui.util.EndpointUtil;
import org.codice.ddf.platform.util.TemporaryFileBackedOutputStream;
import org.codice.ddf.platform.util.uuidgenerator.UuidGenerator;
import org.codice.ddf.rest.service.CatalogService;
import org.codice.gsonsupport.GsonTypeAdapters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Response;
import spark.servlet.SparkApplication;

public class ListApplication implements SparkApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(ListApplication.class);

  private static final Logger INGEST_LOGGER = LoggerFactory.getLogger(Constants.INGEST_LOGGER_NAME);

  private static final String LIST_TYPE_HEADER = "List-Type";

  private static final Gson GSON =
      new GsonBuilder()
          .disableHtmlEscaping()
          .serializeNulls()
          .registerTypeAdapterFactory(GsonTypeAdapters.LongDoubleTypeAdapter.FACTORY)
          .registerTypeAdapter(Date.class, new GsonTypeAdapters.DateLongFormatTypeAdapter())
          .create();

  private final MimeTypeMapper mimeTypeMapper;

  private final CatalogFramework catalogFramework;

  private final WorkspaceService workspaceService;

  private final EndpointUtil endpointUtil;

  private final WorkspaceTransformer transformer;

  private final UuidGenerator uuidGenerator;

  private final SplitterLocator splitterLocator;

  private CatalogService catalogService;

  public ListApplication(
      MimeTypeMapper mimeTypeMapper,
      CatalogFramework catalogFramework,
      UuidGenerator uuidGenerator,
      SplitterLocator splitterLocator,
      WorkspaceService workspaceService,
      EndpointUtil endpointUtil,
      WorkspaceTransformer workspaceTransformer,
      CatalogService catalogService) {
    this.mimeTypeMapper = mimeTypeMapper;
    this.catalogFramework = catalogFramework;
    this.uuidGenerator = uuidGenerator;
    this.splitterLocator = splitterLocator;
    this.workspaceService = workspaceService;
    this.endpointUtil = endpointUtil;
    this.transformer = workspaceTransformer;
    this.catalogService = catalogService;
  }

  @Override
  public void init() {
    post(
        "/list/import",
        (request, response) -> {
          MultipartConfigElement multipartConfigElement = new MultipartConfigElement("");
          request.raw().setAttribute("org.eclipse.jetty.multipartConfig", multipartConfigElement);

          String listType = request.headers(LIST_TYPE_HEADER);

          if (StringUtils.isBlank(listType)) {
            String exceptionMessage = String.format("The header %s must be set.", LIST_TYPE_HEADER);
            LOGGER.info(exceptionMessage);
            createBadRequestResponse(exceptionMessage, response);
            return null;
          }

          List<Part> parts = new ArrayList<>(request.raw().getParts());

          Map.Entry<AttachmentInfo, Metacard> attachmentInfo =
              catalogService.parseParts(parts, null);

          if (attachmentInfo == null) {
            String exceptionMessage = "Unable to parse the attachments.";
            LOGGER.debug(exceptionMessage);
            createBadRequestResponse(exceptionMessage, response);
            return null;
          }

          try (TemporaryFileBackedOutputStream temporaryFileBackedOutputStream =
              new TemporaryFileBackedOutputStream()) {

            IOUtils.copy(attachmentInfo.getKey().getStream(), temporaryFileBackedOutputStream);

            for (Splitter splitter : lookupSplitters(attachmentInfo.getKey().getContentType())) {
              try {
                if (attemptToSplitAndStore(
                    response, listType, attachmentInfo, temporaryFileBackedOutputStream, splitter))
                  break;
              } catch (StopSplitterExecutionException e) {
                LOGGER.debug("Failed to split file.", e);
                createBadRequestResponse(
                    "Attached files do not contain the correct mimetypes", response);
                return null;
              }
            }
          }

          IOUtils.closeQuietly(attachmentInfo.getKey().getStream());

          return "";
        });

    get(
        "/workspaces/:id/lists",
        (req, res) -> {
          String workspaceId = req.params(":id");
          WorkspaceMetacardImpl workspace = workspaceService.getWorkspaceMetacard(workspaceId);

          List<String> listIds = workspace.getContent();

          return endpointUtil
              .getMetacardsWithTagById(listIds, LIST_TAG)
              .values()
              .stream()
              .map(Result::getMetacard)
              .map(transformer::transform)
              .collect(Collectors.toList());
        },
        endpointUtil::getJson);

    post(
        "/lists",
        APPLICATION_JSON,
        (req, res) -> {
          Map<String, Object> body =
              GSON.fromJson(endpointUtil.safeGetBody(req), MAP_STRING_TO_OBJECT_TYPE);

          Metacard list = new ListMetacardImpl(transformer.transform(body));
          Metacard stored = endpointUtil.saveMetacard(list);

          res.status(201);
          return transformer.transform(stored);
        },
        endpointUtil::getJson);

    put(
        "/lists/:id",
        (req, res) -> {
          String listId = req.params("id");
          Map<String, Object> body =
              GSON.fromJson(endpointUtil.safeGetBody(req), MAP_STRING_TO_OBJECT_TYPE);

          Metacard list = new ListMetacardImpl(transformer.transform(body));
          Metacard updated = endpointUtil.updateMetacard(listId, list);

          return transformer.transform(updated);
        },
        endpointUtil::getJson);

    get(
        "/lists/:id",
        (req, res) -> {
          String id = req.params("id");
          Metacard metacard = endpointUtil.getMetacardById(id);

          Set<String> metacardTags = metacard.getTags();

          if (metacardTags == null || !metacardTags.contains(LIST_TAG)) {
            res.status(400);
            return ImmutableMap.of("message", "Requested ID is not a list metacard.");
          } else {
            return transformer.transform(metacard);
          }
        },
        endpointUtil::getJson);

    delete(
        "/lists/:id",
        APPLICATION_JSON,
        (req, res) -> {
          String listId = req.params(":id");

          WorkspaceMetacardImpl workspace = workspaceService.getWorkspaceFromListId(listId);
          workspace.removeQueryAssociation(listId);
          endpointUtil.saveMetacard(workspace);

          catalogFramework.delete(new DeleteRequestImpl(listId));
          return ImmutableMap.of("message", "Successfully deleted.");
        },
        endpointUtil::getJson);
  }

  private boolean attemptToSplitAndStore(
      Response response,
      String listType,
      Map.Entry<AttachmentInfo, Metacard> attachmentInfo,
      TemporaryFileBackedOutputStream temporaryFileBackedOutputStream,
      Splitter splitter)
      throws IOException, StopSplitterExecutionException {
    List<String> ids = new LinkedList<>();
    List<String> errorMessages = new LinkedList<>();

    boolean isSplitSuccessful = false;

    try (InputStream temporaryInputStream =
        temporaryFileBackedOutputStream.asByteSource().openStream()) {

      AttachmentInfo temporaryAttachmentInfo =
          new AttachmentInfoImpl(
              temporaryInputStream,
              attachmentInfo.getKey().getFilename(),
              attachmentInfo.getKey().getContentType());

      try (Stream<StorableResource> stream =
          createStream(temporaryAttachmentInfo, splitter, listType)) {
        isSplitSuccessful = true;
        stream
            .sequential()
            .map(storableResource -> appendMessageIfError(errorMessages, storableResource))
            .filter(storableResource -> !storableResource.isError())
            .forEach(
                storableResource ->
                    storeAndClose(
                        ids::add, errorMessages::add, storableResource, attachmentInfo.getValue()));
      } catch (IOException e) {
        LOGGER.debug("Failed to split the incoming data. Trying the next splitter.", e);
      }

      if (isSplitSuccessful) {
        /** TODO: DDF-3800 - Display these error messages in the UI. */
        errorMessages.forEach(s -> LOGGER.debug("Unable to ingest split item: {}", s));

        response.header("Added-IDs", String.join(",", ids));
        return true;
      }
    }
    return false;
  }

  private void storeAndClose(
      Consumer<String> idConsumer,
      Consumer<String> errorMessageConsumer,
      StorableResource storableResource,
      Metacard metacard) {

    try {
      store(getAttachmentInfo(storableResource), idConsumer, errorMessageConsumer, metacard);
    } catch (IOException e) {
      LOGGER.debug("Unable to create AttachmentInfo: ", e);
    } finally {
      try {
        storableResource.close();
      } catch (Exception e) {
        LOGGER.trace("Unable to close resource. Will continue.", e);
      }
    }
  }

  private Stream<StorableResource> createStream(
      AttachmentInfo attachmentInfo, Splitter splitter, String listType)
      throws IOException, StopSplitterExecutionException {
    return splitter.split(
        createStorableResource(attachmentInfo),
        Collections.singletonMap(LIST_TYPE_HEADER, listType));
  }

  private StorableResource createStorableResource(AttachmentInfo attachmentInfo)
      throws IOException {
    return new StorableResourceImpl(
        attachmentInfo.getStream(), attachmentInfo.getContentType(), attachmentInfo.getFilename());
  }

  private StorableResource appendMessageIfError(
      List<String> errorMessages, StorableResource storableResource) {
    if (storableResource.isError()) {
      errorMessages.add(storableResource.getErrorMessage());
    }
    return storableResource;
  }

  private AttachmentInfo getAttachmentInfo(StorableResource storableResource) throws IOException {
    return new AttachmentInfoImpl(
        storableResource.getInputStream(),
        storableResource.getFilename(),
        storableResource
            .getMimeType()
            .orElse(contentTypeFromFilename(storableResource.getFilename())));
  }

  private String contentTypeFromFilename(String filename) {
    String fileExtension = FilenameUtils.getExtension(filename);
    String contentType = null;
    try {
      contentType = mimeTypeMapper.getMimeTypeForFileExtension(fileExtension);
    } catch (MimeTypeResolutionException e) {
      LOGGER.debug("Unable to get contentType based on filename extension {}", fileExtension);
    }
    LOGGER.debug("Refined contentType = {}", contentType);
    return contentType;
  }

  private List<Splitter> lookupSplitters(String mimeType) throws MimeTypeParseException {
    List<Splitter> splitters = splitterLocator.find(new MimeType(mimeType));
    if (CollectionUtils.isEmpty(splitters)) {
      LOGGER.debug("Unable to find a splitter for mime-type {}", mimeType);
    }
    return splitters;
  }

  private void store(
      AttachmentInfo createInfo,
      Consumer<String> idConsumer,
      Consumer<String> errorMessageConsumer,
      Metacard metacard) {

    CreateStorageRequest streamCreateRequest =
        new CreateStorageRequestImpl(
            Collections.singletonList(
                new IncomingContentItem(
                    uuidGenerator,
                    createInfo.getStream(),
                    createInfo.getContentType(),
                    createInfo.getFilename(),
                    metacard)),
            null);
    try {
      CreateResponse createResponse = catalogFramework.create(streamCreateRequest);

      createResponse.getCreatedMetacards().stream().map(Metacard::getId).forEach(idConsumer);

    } catch (IngestException e) {
      String errorMessage = "Error while storing entry in catalog.";
      LOGGER.info(errorMessage, e);
      INGEST_LOGGER.warn(errorMessage, e);
      errorMessageConsumer.accept(errorMessage);
    } catch (SourceUnavailableException e) {
      String exceptionMessage = "Cannot create catalog entry because source is unavailable.";
      LOGGER.info(exceptionMessage, e);
      INGEST_LOGGER.warn(exceptionMessage, e);
      throw new InternalServerErrorException(exceptionMessage);
    }
  }

  private void createBadRequestResponse(String entityMessage, Response response) {
    response.status(Status.BAD_REQUEST.getStatusCode());
    response.body("<pre>" + entityMessage + "</pre>");
    response.type(MediaType.TEXT_HTML);
  }

  protected static class IncomingContentItem extends ContentItemImpl {

    private InputStream inputStream;

    private IncomingContentItem(
        UuidGenerator uuidGenerator,
        InputStream inputStream,
        String mimeTypeRawData,
        String filename,
        Metacard metacard) {
      super(uuidGenerator.generateUuid(), null, mimeTypeRawData, filename, 0L, metacard);
      this.inputStream = inputStream;
    }

    @Override
    public InputStream getInputStream() {
      return inputStream;
    }
  }

  private static class AttachmentInfoImpl implements AttachmentInfo {

    private InputStream inputStream;
    private String filename;
    private String contentType;

    AttachmentInfoImpl(InputStream inputStream, String filename, String contentType) {
      this.inputStream = inputStream;
      this.filename = filename;
      this.contentType = contentType;
    }

    @Override
    public InputStream getStream() {
      return inputStream;
    }

    @Override
    public String getFilename() {
      return filename;
    }

    @Override
    public String getContentType() {
      return contentType;
    }
  }
}

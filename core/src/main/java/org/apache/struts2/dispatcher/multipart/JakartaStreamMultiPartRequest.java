/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.struts2.dispatcher.multipart;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItemInput;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletDiskFileUpload;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Multi-part form data request adapter for Jakarta Commons FileUpload package that
 * leverages the streaming API rather than the traditional non-streaming API.
 * <p>
 * For more details see WW-3025
 *
 * @since 2.3.18
 */
public class JakartaStreamMultiPartRequest extends AbstractMultiPartRequest<File> {

    private static final Logger LOG = LogManager.getLogger(JakartaStreamMultiPartRequest.class);

    /**
     * Processes the upload.
     *
     * @param request the servlet request
     * @param saveDir location of the save dir
     */
    @Override
    protected void processUpload(HttpServletRequest request, String saveDir) throws IOException {
        String charset = StringUtils.isBlank(request.getCharacterEncoding())
                ? defaultEncoding
                : request.getCharacterEncoding();

        Path location = Path.of(saveDir);
        JakartaServletDiskFileUpload servletFileUpload =
                prepareServletFileUpload(Charset.forName(charset), location);

        LOG.debug("Using Jakarta Stream API to process request");
        servletFileUpload.getItemIterator(request).forEachRemaining(item -> {
            if (item.isFormField()) {
                LOG.debug(() -> "Processing a form field: " + sanitizeNewlines(item.getFieldName()));
                processFileItemAsFormField(item);
            } else {
                LOG.debug(() -> "Processing a file: " + sanitizeNewlines(item.getFieldName()));
                processFileItemAsFileField(item, location);
            }
        });
    }

    protected JakartaServletDiskFileUpload createJakartaFileUpload(Charset charset, Path location) {
        DiskFileItemFactory.Builder builder = DiskFileItemFactory.builder();

        LOG.debug("Using file save directory: {}", location);
        builder.setPath(location);

        LOG.debug("Sets buffer size: {}", bufferSize);
        builder.setBufferSize(bufferSize);

        LOG.debug("Using charset: {}", charset);
        builder.setCharset(charset);

        DiskFileItemFactory factory = builder.get();
        return new JakartaServletDiskFileUpload(factory);
    }

    private String readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int length; (length = inputStream.read(buffer)) != -1; ) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    /**
     * Processes the FileItem as a normal form field.
     *
     * @param fileItemInput a form field item input
     */
    protected void processFileItemAsFormField(FileItemInput fileItemInput) throws IOException {
        String fieldName = fileItemInput.getFieldName();
        String fieldValue = readStream(fileItemInput.getInputStream());

        if (exceedsMaxStringLength(fieldName, fieldValue)) {
            return;
        }

        List<String> values;
        if (parameters.containsKey(fieldName)) {
            values = parameters.get(fieldName);
        } else {
            values = new ArrayList<>();
            parameters.put(fieldName, values);
        }
        values.add(fieldValue);
    }

    /**
     * Processes the FileItem as a file field.
     *
     * @param fileItemInput file item representing upload file
     * @param location      location
     */
    protected void processFileItemAsFileField(FileItemInput fileItemInput, Path location) throws IOException {
        // Skip file uploads that don't have a file name - meaning that no file was selected.
        if (fileItemInput.getName() == null || fileItemInput.getName().trim().isEmpty()) {
            LOG.debug(() -> "No file has been uploaded for the field: " + sanitizeNewlines(fileItemInput.getFieldName()));
            return;
        }

        File file = createTemporaryFile(fileItemInput.getName(), location);
        streamFileToDisk(fileItemInput, file);
        createUploadedFile(fileItemInput, file);
    }

    /**
     * Creates a temporary file based on the given filename and location.
     *
     * @param fileName file name
     * @param location location
     * @return a temporary file based on the given filename and location
     */
    protected File createTemporaryFile(String fileName, Path location) {
        String uid = UUID.randomUUID().toString().replace("-", "_");
        File file = location.resolve("upload_" + uid + ".tmp").toFile();
        LOG.debug("Creating temporary file: {} (originally: {})", file.getName(), fileName);
        return file;
    }

    /**
     * Streams the file upload stream to the specified file.
     *
     * @param fileItemInput file item input
     * @param file          the file
     */
    protected void streamFileToDisk(FileItemInput fileItemInput, File file) throws IOException {
        InputStream input = fileItemInput.getInputStream();
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(file.toPath()), bufferSize)) {
            byte[] buffer = new byte[bufferSize];
            LOG.debug("Streaming file: {} using buffer size: {}", fileItemInput.getName(), bufferSize);
            for (int length; ((length = input.read(buffer)) > 0); ) {
                output.write(buffer, 0, length);
            }
        }
    }

    /**
     * Create {@link UploadedFile} abstraction over uploaded file
     *
     * @param fileItemInput file item stream
     * @param file          the file
     */
    protected void createUploadedFile(FileItemInput fileItemInput, File file) {
        String fileName = fileItemInput.getName();
        String fieldName = fileItemInput.getFieldName();

        UploadedFile<File> uploadedFile = StrutsUploadedFile.Builder
                .create(file)
                .withOriginalName(fileName)
                .withContentType(fileItemInput.getContentType())
                .build();

        if (uploadedFiles.containsKey(fieldName)) {
            uploadedFiles.get(fieldName).add(uploadedFile);
        } else {
            List<UploadedFile<File>> infos = new ArrayList<>();
            infos.add(uploadedFile);
            uploadedFiles.put(fieldName, infos);
        }
    }

}

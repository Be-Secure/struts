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

import com.opensymphony.xwork2.LocaleProviderFactory;
import com.opensymphony.xwork2.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.fileupload2.core.FileUploadByteCountLimitException;
import org.apache.commons.fileupload2.core.FileUploadContentTypeException;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.core.FileUploadFileCountLimitException;
import org.apache.commons.fileupload2.core.FileUploadSizeException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletDiskFileUpload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.struts2.StrutsConstants;
import org.apache.struts2.dispatcher.LocalizedMessage;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Abstract class with some helper methods, it should be used
 * when starting development of another implementation of {@link MultiPartRequest}
 */
public abstract class AbstractMultiPartRequest<T> implements MultiPartRequest {

    protected static final String STRUTS_MESSAGES_UPLOAD_ERROR_PARAMETER_TOO_LONG_KEY = "struts.messages.upload.error.parameter.too.long";

    private static final Logger LOG = LogManager.getLogger(AbstractMultiPartRequest.class);

    /**
     * Defines the internal buffer size used during streaming operations.
     */
    public static final int BUFFER_SIZE = 10240;

    /**
     * Internal list of raised errors to be passed to the the Struts2 framework.
     */
    protected List<LocalizedMessage> errors = new ArrayList<>();

    /**
     * Specifies the maximum size of the entire request.
     */
    protected Long maxSize;

    /**
     * Specifies the maximum number of files in one request.
     */
    protected Long maxFiles;

    /**
     * Specifies the maximum length of a string parameter in a multipart request.
     */
    protected Long maxStringLength;

    /**
     * Specifies the maximum size per file in the request.
     */
    protected Long maxFileSize;

    /**
     * Specifies the buffer size to use during streaming.
     */
    protected int bufferSize = BUFFER_SIZE;

    protected String defaultEncoding;

    /**
     * Localization to be used regarding errors.
     */
    protected Locale defaultLocale = Locale.ENGLISH;

    /**
     * Map between file fields and file data.
     */
    protected Map<String, List<UploadedFile<T>>> uploadedFiles = new HashMap<>();

    /**
     * Map between non-file fields and values.
     */
    protected Map<String, List<String>> parameters = new HashMap<>();

    /**
     * @param bufferSize Sets the buffer size to be used.
     */
    @Inject(value = StrutsConstants.STRUTS_MULTIPART_BUFFERSIZE, required = false)
    public void setBufferSize(String bufferSize) {
        this.bufferSize = Integer.parseInt(bufferSize);
    }

    @Inject(StrutsConstants.STRUTS_I18N_ENCODING)
    public void setDefaultEncoding(String enc) {
        this.defaultEncoding = enc;
    }

    /**
     * @param maxSize Injects the Struts multipart request maximum size.
     */
    @Inject(StrutsConstants.STRUTS_MULTIPART_MAXSIZE)
    public void setMaxSize(String maxSize) {
        this.maxSize = Long.parseLong(maxSize);
    }

    @Inject(StrutsConstants.STRUTS_MULTIPART_MAXFILES)
    public void setMaxFiles(String maxFiles) {
        this.maxFiles = Long.parseLong(maxFiles);
    }

    @Inject(value = StrutsConstants.STRUTS_MULTIPART_MAXFILESIZE, required = false)
    public void setMaxFileSize(String maxFileSize) {
        this.maxFileSize = Long.parseLong(maxFileSize);
    }

    @Inject(StrutsConstants.STRUTS_MULTIPART_MAX_STRING_LENGTH)
    public void setMaxStringLength(String maxStringLength) {
        this.maxStringLength = Long.parseLong(maxStringLength);
    }

    @Inject
    public void setLocaleProviderFactory(LocaleProviderFactory localeProviderFactory) {
        defaultLocale = localeProviderFactory.createLocaleProvider().getLocale();
    }

    /**
     * @param request Inspect the servlet request and set the locale if one wasn't provided by
     *                the Struts2 framework.
     */
    protected void setLocale(HttpServletRequest request) {
        if (defaultLocale == null) {
            defaultLocale = request.getLocale();
        }
    }

    /**
     * Process the request extract file upload data
     *
     * @param request current {@link HttpServletRequest}
     * @param saveDir a temporary directory to store files
     */
    protected abstract void processUpload(HttpServletRequest request, String saveDir) throws IOException;

    /**
     * Creates an instance of {@link JakartaServletDiskFileUpload} used by the parser to extract uploaded files
     *
     * @param charset used charset from incoming request
     * @param saveDir a temporary folder to store uploaded files (not always needed)
     */
    protected abstract JakartaServletDiskFileUpload createJakartaFileUpload(Charset charset, Path saveDir);

    protected JakartaServletDiskFileUpload prepareServletFileUpload(Charset charset, Path saveDir) {
        JakartaServletDiskFileUpload servletFileUpload = createJakartaFileUpload(charset, saveDir);

        if (maxSize != null) {
            LOG.debug("Applies max size: {} to file upload request", maxSize);
            servletFileUpload.setSizeMax(maxSize);
        }
        if (maxFiles != null) {
            LOG.debug("Applies max files number: {} to file upload request", maxFiles);
            servletFileUpload.setFileCountMax(maxFiles);
        }
        if (maxFileSize != null) {
            LOG.debug("Applies max size of single file: {} to file upload request", maxFileSize);
            servletFileUpload.setFileSizeMax(maxFileSize);
        }
        return servletFileUpload;
    }

    protected boolean exceedsMaxStringLength(String fieldName, String fieldValue) {
        if (maxStringLength != null && fieldValue.length() > maxStringLength) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Form field: {} of size: {} bytes exceeds limit of: {}.",
                        sanitizeNewlines(fieldName), fieldValue.length(), maxStringLength);
            }
            LocalizedMessage localizedMessage = new LocalizedMessage(this.getClass(),
                    STRUTS_MESSAGES_UPLOAD_ERROR_PARAMETER_TOO_LONG_KEY, null,
                    new Object[]{fieldName, maxStringLength, fieldValue.length()});
            if (!errors.contains(localizedMessage)) {
                errors.add(localizedMessage);
            }
            return true;
        }
        return false;
    }

    /**
     * Processes the upload.
     *
     * @param request the servlet request
     * @param saveDir location of the save dir
     */
    public void parse(HttpServletRequest request, String saveDir) throws IOException {
        try {
            setLocale(request);
            processUpload(request, saveDir);
        } catch (FileUploadException e) {
            LOG.debug("Request exceeded size limit!", e);
            LocalizedMessage errorMessage;
            if (e instanceof FileUploadByteCountLimitException ex) {
                errorMessage = buildErrorMessage(e, new Object[]{
                        ex.getFieldName(), ex.getFileName(), ex.getPermitted(), ex.getActualSize()
                });
            } else if (e instanceof FileUploadFileCountLimitException ex) {
                errorMessage = buildErrorMessage(e, new Object[]{
                        ex.getPermitted(), ex.getActualSize()
                });
            } else if (e instanceof FileUploadSizeException ex) {
                errorMessage = buildErrorMessage(e, new Object[]{
                        ex.getPermitted(), ex.getActualSize()
                });
            } else if (e instanceof FileUploadContentTypeException ex) {
                errorMessage = buildErrorMessage(e, new Object[]{
                        ex.getContentType()
                });
            } else {
                errorMessage = buildErrorMessage(e, new Object[]{});
            }

            if (!errors.contains(errorMessage)) {
                errors.add(errorMessage);
            }
        } catch (Exception e) {
            LOG.debug("Unable to parse request", e);
            LocalizedMessage errorMessage = buildErrorMessage(e, new Object[]{});
            if (!errors.contains(errorMessage)) {
                errors.add(errorMessage);
            }
        }
    }

    /**
     * Build error message.
     *
     * @param e    the Throwable/Exception
     * @param args arguments
     * @return error message
     */
    protected LocalizedMessage buildErrorMessage(Throwable e, Object[] args) {
        String errorKey = "struts.messages.upload.error." + e.getClass().getSimpleName();
        LOG.debug("Preparing error message for key: [{}]", errorKey);

        return new LocalizedMessage(this.getClass(), errorKey, e.getMessage(), args);
    }

    /**
     * @param originalFileName file name
     * @return the canonical name based on the supplied filename
     */
    protected String getCanonicalName(final String originalFileName) {
        String fileName = originalFileName;

        int forwardSlash = fileName.lastIndexOf('/');
        int backwardSlash = fileName.lastIndexOf('\\');
        if (forwardSlash != -1 && forwardSlash > backwardSlash) {
            fileName = fileName.substring(forwardSlash + 1);
        } else {
            fileName = fileName.substring(backwardSlash + 1);
        }
        return fileName;
    }

    protected String sanitizeNewlines(String before) {
        return before.replaceAll("\\R", "_");
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getErrors()
     */
    public List<LocalizedMessage> getErrors() {
        return errors;
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getFileParameterNames()
     */
    public Enumeration<String> getFileParameterNames() {
        return Collections.enumeration(uploadedFiles.keySet());
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getContentType(java.lang.String)
     */
    public String[] getContentType(String fieldName) {
        return uploadedFiles.getOrDefault(fieldName, Collections.emptyList()).stream()
                .map(UploadedFile::getContentType)
                .toArray(String[]::new);
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getFile(java.lang.String)
     */
    @SuppressWarnings("unchecked")
    public UploadedFile<T>[] getFile(String fieldName) {
        return uploadedFiles.getOrDefault(fieldName, Collections.emptyList())
                .toArray(UploadedFile[]::new);
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getFileNames(java.lang.String)
     */
    public String[] getFileNames(String fieldName) {
        return uploadedFiles.getOrDefault(fieldName, Collections.emptyList()).stream()
                .map(file -> getCanonicalName(file.getName()))
                .toArray(String[]::new);
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getFilesystemName(java.lang.String)
     */
    public String[] getFilesystemName(String fieldName) {
        return uploadedFiles.getOrDefault(fieldName, Collections.emptyList()).stream()
                .map(UploadedFile::getAbsolutePath)
                .toArray(String[]::new);
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getParameter(java.lang.String)
     */
    public String getParameter(String name) {
        List<String> paramValue = parameters.getOrDefault(name, Collections.emptyList());
        if (!paramValue.isEmpty()) {
            return paramValue.get(0);
        }

        return null;
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getParameterNames()
     */
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters.keySet());
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#getParameterValues(java.lang.String)
     */
    public String[] getParameterValues(String name) {
        return parameters.getOrDefault(name, Collections.emptyList())
                .toArray(String[]::new);
    }

    /* (non-Javadoc)
     * @see org.apache.struts2.dispatcher.multipart.MultiPartRequest#cleanUp()
     */
    public void cleanUp() {
        LOG.debug("Performing File Upload temporary storage cleanup.");
        for (List<UploadedFile<T>> uploadedFileList : uploadedFiles.values()) {
            for (UploadedFile<T> uploadedFile : uploadedFileList) {
                if (uploadedFile.isFile()) {
                    LOG.debug("Deleting file: {}", uploadedFile.getName());
                    if (!uploadedFile.delete()) {
                        LOG.warn("There was a problem attempting to delete file: {}", uploadedFile.getName());
                    }
                } else {
                    LOG.debug("File: {} already deleted", uploadedFile.getName());
                }
            }
        }
    }

}

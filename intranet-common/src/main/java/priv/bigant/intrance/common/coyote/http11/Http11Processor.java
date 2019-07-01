/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package priv.bigant.intrance.common.coyote.http11;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import priv.bigant.intrance.common.HttpServletResponse;
import priv.bigant.intrance.common.SocketBean;
import priv.bigant.intrance.common.coyote.*;
import priv.bigant.intrance.common.coyote.http11.filters.*;
import priv.bigant.intrance.common.util.ExceptionUtils;
import priv.bigant.intrance.common.util.buf.Ascii;
import priv.bigant.intrance.common.util.buf.ByteChunk;
import priv.bigant.intrance.common.util.buf.MessageBytes;
import priv.bigant.intrance.common.util.http.FastHttpDateFormat;
import priv.bigant.intrance.common.util.http.MimeHeaders;
import priv.bigant.intrance.common.util.http.parser.HttpParser;
import priv.bigant.intrance.common.util.log.UserDataHelper;
import priv.bigant.intrance.common.util.net.*;
import priv.bigant.intrance.common.util.net.AbstractEndpoint.Handler.SocketState;
import priv.bigant.intrance.common.util.res.StringManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public abstract class Http11Processor extends AbstractProcessor {

    private static final Logger log = LoggerFactory.getLogger(Http11Processor.class);

    /**
     * The string manager for this package.
     */
    private static final StringManager sm = StringManager.getManager(Http11Processor.class);

    /**
     * Input.
     */
    protected final Http11InputBuffer inputBuffer;

    /**
     * Input.
     */
    protected final Http11ResponseInputBuffer responseInputBuffer;

    /**
     * Output.
     */
    protected final Http11OutputBuffer outputBuffer;


    private final HttpParser httpParser;


    /**
     * Tracks how many internal filters are in the filter library so they are skipped when looking for pluggable
     * filters.
     */
    private int plugGableFilterIndex = Integer.MAX_VALUE;

    /**
     * Keep-alive.
     */
    protected volatile boolean keepAlive = true;

    /**
     * Flag used to indicate that the socket should be kept open (e.g. for keep alive or send file.
     */
    protected boolean openSocket = false;

    /**
     * Flag that indicates if the request headers have been completely read.
     */
    protected boolean readComplete = true;

    /**
     * HTTP/1.1 flag.
     */
    protected boolean http11 = true;

    /**
     * HTTP/0.9 flag.
     */
    protected boolean http09 = false;

    /**
     * Content delimiter for the request (if false, the connection will be closed at the end of the request).
     */
    protected boolean contentDelimitation = true;

    /**
     * Regular expression that defines the restricted user agents.
     */
    protected Pattern restrictedUserAgents = null;


    /**
     * Maximum number of Keep-Alive requests to honor.
     */
    protected int maxKeepAliveRequests = -1;


    /**
     * Maximum timeout on uploads. 5 minutes as in Apache HTTPD server.
     */
    protected int connectionUploadTimeout = 300000;


    /**
     * Flag to disable setting a different time-out on uploads.
     */
    protected boolean disableUploadTimeout = false;


    /**
     * Allowed compression level.
     */
    protected int compressionLevel = 0;


    /**
     * Minimum content size to make compression.
     */
    protected int compressionMinSize = 2048;


    /**
     * Max saved post size.
     */
    protected int maxSavePostSize = 4 * 1024;


    /**
     * Regular expression that defines the user agents to not use gzip with
     */
    protected Pattern noCompressionUserAgents = null;


    /**
     * List of MIMES for which compression may be enabled. Note: This is not spelled correctly but can't be changed
     * without breaking compatibility
     */
    protected String[] compressableMimeTypes;


    /**
     * Allow a customized the server header for the tin-foil hat folks.
     */
    private String server = null;


    /*
     * Should application provider values for the HTTP Server header be removed.
     * Note that if {@link #server} is set, any application provided value will
     * be over-ridden.
     */
    private boolean serverRemoveAppProvidedValues = false;

    /**
     * Instance of the new protocol to use after the HTTP connection has been upgraded.
     */
    protected UpgradeToken upgradeToken = null;


    /**
     * Sendfile data.
     */
    protected SendfileDataBase sendFileData = null;


    /**
     * UpgradeProtocol information
     */
    private final Map<String, UpgradeProtocol> httpUpgradeProtocols;

    private final boolean allowHostHeaderMismatch;


    public Http11Processor(int maxHttpHeaderSize, boolean allowHostHeaderMismatch,
                           boolean rejectIllegalHeaderName, AbstractEndpoint<?> endpoint, int maxTrailerSize,
                           Set<String> allowedTrailerHeaders, int maxExtensionSize, int maxSwallowSize,
                           Map<String, UpgradeProtocol> httpUpgradeProtocols, boolean sendReasonPhrase,
                           String relaxedPathChars, String relaxedQueryChars) {

        super(endpoint);

        httpParser = new HttpParser(relaxedPathChars, relaxedQueryChars);

        inputBuffer = new Http11InputBuffer(request, maxHttpHeaderSize, rejectIllegalHeaderName, httpParser);
        request.setInputBuffer(inputBuffer);

        responseInputBuffer = new Http11ResponseInputBuffer(response, maxHttpHeaderSize, rejectIllegalHeaderName, httpParser);
        response.setInputBuffer(responseInputBuffer);

        outputBuffer = new Http11OutputBuffer(response, maxHttpHeaderSize, sendReasonPhrase);
        //TODO response.setOutputBuffer(outputBuffer);

        // Create and add the identity filters.
        inputBuffer.addFilter(new IdentityInputFilter(maxSwallowSize));
        outputBuffer.addFilter(new IdentityOutputFilter());

        // Create and add the chunked filters.
        inputBuffer.addFilter(new ChunkedInputFilter(maxTrailerSize, allowedTrailerHeaders, maxExtensionSize, maxSwallowSize));
        outputBuffer.addFilter(new ChunkedOutputFilter());

        // Create and add the void filters.
        inputBuffer.addFilter(new VoidInputFilter());
        outputBuffer.addFilter(new VoidOutputFilter());

        // Create and add buffered input filter
        inputBuffer.addFilter(new BufferedInputFilter());

        // Create and add the chunked filters.
        //inputBuffer.addFilter(new GzipInputFilter());
        outputBuffer.addFilter(new GzipOutputFilter());

        plugGableFilterIndex = inputBuffer.getFilters().length;

        this.httpUpgradeProtocols = httpUpgradeProtocols;
        this.allowHostHeaderMismatch = allowHostHeaderMismatch;
    }


    /**
     * Set compression level.
     *
     * @param compression One of <code>on</code>, <code>force</code>,
     *                    <code>off</code> or the minimum compression size in
     *                    bytes which implies <code>on</code>
     */
    public void setCompression(String compression) {
        if (compression.equals("on")) {
            this.compressionLevel = 1;
        } else if (compression.equals("force")) {
            this.compressionLevel = 2;
        } else if (compression.equals("off")) {
            this.compressionLevel = 0;
        } else {
            try {
                // Try to parse compression as an int, which would give the
                // minimum compression size
                compressionMinSize = Integer.parseInt(compression);
                this.compressionLevel = 1;
            } catch (Exception e) {
                this.compressionLevel = 0;
            }
        }
    }

    /**
     * Set Minimum size to trigger compression.
     *
     * @param compressionMinSize The minimum content length required for compression in bytes
     */
    public void setCompressionMinSize(int compressionMinSize) {
        this.compressionMinSize = compressionMinSize;
    }


    /**
     * Set no compression user agent pattern. Regular expression as supported by {@link Pattern}. e.g.:
     * <code>gorilla|desesplorer|tigrus</code>.
     *
     * @param noCompressionUserAgents The regular expression for user agent strings for which compression should not be
     *                                applied
     */
    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        if (noCompressionUserAgents == null || noCompressionUserAgents.length() == 0) {
            this.noCompressionUserAgents = null;
        } else {
            this.noCompressionUserAgents =
                    Pattern.compile(noCompressionUserAgents);
        }
    }


    /**
     * @param compressibleMimeTypes See {@link Http11Processor#setCompressibleMimeTypes(String[])}
     * @deprecated Use {@link Http11Processor#setCompressibleMimeTypes(String[])}
     */
    @Deprecated
    public void setCompressableMimeTypes(String[] compressibleMimeTypes) {
        setCompressibleMimeTypes(compressibleMimeTypes);
    }


    /**
     * Set compressible mime-type list (this method is best when used with a large number of connectors, where it would
     * be better to have all of them referenced a single array).
     *
     * @param compressibleMimeTypes MIME types for which compression should be enabled
     */
    public void setCompressibleMimeTypes(String[] compressibleMimeTypes) {
        this.compressableMimeTypes = compressibleMimeTypes;
    }


    /**
     * Return compression level.
     *
     * @return The current compression level in string form (off/on/force)
     */
    public String getCompression() {
        switch (compressionLevel) {
            case 0:
                return "off";
            case 1:
                return "on";
            case 2:
                return "force";
        }
        return "off";
    }


    /**
     * Checks if any entry in the string array starts with the specified value
     *
     * @param sArray the StringArray
     * @param value  string
     */
    private static boolean startsWithStringArray(String sArray[], String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < sArray.length; i++) {
            if (value.startsWith(sArray[i])) {
                return true;
            }
        }
        return false;
    }


    /**
     * Set restricted user agent list (which will downgrade the connector to HTTP/1.0 mode). Regular expression as
     * supported by {@link Pattern}.
     *
     * @param restrictedUserAgents The regular expression as supported by {@link Pattern} for the user agents e.g.
     *                             "gorilla|desesplorer|tigrus"
     */
    public void setRestrictedUserAgents(String restrictedUserAgents) {
        if (restrictedUserAgents == null || restrictedUserAgents.length() == 0) {
            this.restrictedUserAgents = null;
        } else {
            this.restrictedUserAgents = Pattern.compile(restrictedUserAgents);
        }
    }


    /**
     * Set the maximum number of Keep-Alive requests to allow. This is to safeguard from DoS attacks. Setting to a
     * negative value disables the limit.
     *
     * @param mkar The new maximum number of Keep-Alive requests allowed
     */
    public void setMaxKeepAliveRequests(int mkar) {
        maxKeepAliveRequests = mkar;
    }


    /**
     * Get the maximum number of Keep-Alive requests allowed. A negative value means there is no limit.
     *
     * @return the number of Keep-Alive requests that we will allow.
     */
    public int getMaxKeepAliveRequests() {
        return maxKeepAliveRequests;
    }


    /**
     * Set the maximum size of a POST which will be buffered in SSL mode. When a POST is received where the security
     * constraints require a client certificate, the POST body needs to be buffered while an SSL handshake takes place
     * to obtain the certificate.
     *
     * @param msps The maximum size POST body to buffer in bytes
     */
    public void setMaxSavePostSize(int msps) {
        maxSavePostSize = msps;
    }


    /**
     * Return the maximum size of a POST which will be buffered in SSL mode.
     *
     * @return The size in bytes
     */
    public int getMaxSavePostSize() {
        return maxSavePostSize;
    }


    /**
     * Set the flag to control whether a separate connection timeout is used during upload of a request body.
     *
     * @param isDisabled {@code true} if the separate upload timeout should be disabled
     */
    public void setDisableUploadTimeout(boolean isDisabled) {
        disableUploadTimeout = isDisabled;
    }

    /**
     * Get the flag that controls upload time-outs.
     *
     * @return {@code true} if the separate upload timeout is disabled
     */
    public boolean getDisableUploadTimeout() {
        return disableUploadTimeout;
    }

    /**
     * Set the upload timeout.
     *
     * @param timeout Upload timeout in milliseconds
     */
    public void setConnectionUploadTimeout(int timeout) {
        connectionUploadTimeout = timeout;
    }

    /**
     * Get the upload timeout.
     *
     * @return Upload timeout in milliseconds
     */
    public int getConnectionUploadTimeout() {
        return connectionUploadTimeout;
    }


    /**
     * Set the server header name.
     *
     * @param server The new value to use for the server header
     */
    public void setServer(String server) {
        if (server == null || server.equals("")) {
            this.server = null;
        } else {
            this.server = server;
        }
    }


    public void setServerRemoveAppProvidedValues(boolean serverRemoveAppProvidedValues) {
        this.serverRemoveAppProvidedValues = serverRemoveAppProvidedValues;
    }


    /**
     * Check if the resource could be compressed, if the client supports it.
     */
    private boolean isCompressible() {

        // Check if content is not already gzipped
        MessageBytes contentEncodingMB = response.getMimeHeaders().getValue("Content-Encoding");

        if ((contentEncodingMB != null)
                && (contentEncodingMB.indexOf("gzip") != -1)) {
            return false;
        }

        // If force mode, always compress (test purposes only)
        if (compressionLevel == 2) {
            return true;
        }

        // Check if sufficient length to trigger the compression
        long contentLength = response.getContentLengthLong();
        if ((contentLength == -1)
                || (contentLength > compressionMinSize)) {
            // Check for compatible MIME-TYPE
            if (compressableMimeTypes != null) {
                return (startsWithStringArray(compressableMimeTypes, response.getContentType()));
            }
        }

        return false;
    }


    /**
     * Check if compression should be used for this resource. Already checked that the resource could be compressed if
     * the client supports it.
     */
    private boolean useCompression() {

        // Check if browser support gzip encoding
        MessageBytes acceptEncodingMB =
                request.getMimeHeaders().getValue("accept-encoding");

        if ((acceptEncodingMB == null)
                || (acceptEncodingMB.indexOf("gzip") == -1)) {
            return false;
        }

        // If force mode, always compress (test purposes only)
        if (compressionLevel == 2) {
            return true;
        }

        // Check for incompatible Browser
        if (noCompressionUserAgents != null) {
            MessageBytes userAgentValueMB =
                    request.getMimeHeaders().getValue("user-agent");
            if (userAgentValueMB != null) {
                String userAgentValue = userAgentValueMB.toString();

                if (noCompressionUserAgents.matcher(userAgentValue).matches()) {
                    return false;
                }
            }
        }

        return true;
    }


    /**
     * Specialized utility method: find a sequence of lower case bytes inside a ByteChunk.
     */
    public static int findBytes(ByteChunk bc, byte[] b) {

        byte first = b[0];
        byte[] buff = bc.getBuffer();
        int start = bc.getStart();
        int end = bc.getEnd();

        // Look for first char
        int srcEnd = b.length;

        for (int i = start; i <= (end - srcEnd); i++) {
            if (Ascii.toLower(buff[i]) != first) {
                continue;
            }
            // found first char, now look for a match
            int myPos = i + 1;
            for (int srcPos = 1; srcPos < srcEnd; ) {
                if (Ascii.toLower(buff[myPos++]) != b[srcPos++]) {
                    break;
                }
                if (srcPos == srcEnd) {
                    return i - start; // found it
                }
            }
        }
        return -1;
    }


    /**
     * Determine if we must drop the connection because of the HTTP status code.  Use the same list of codes as
     * Apache/httpd.
     */
    private static boolean statusDropsConnection(int status) {
        return status == 400 /* SC_BAD_REQUEST */ ||
                status == 408 /* SC_REQUEST_TIMEOUT */ ||
                status == 411 /* SC_LENGTH_REQUIRED */ ||
                status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */ ||
                status == 414 /* SC_REQUEST_URI_TOO_LONG */ ||
                status == 500 /* SC_INTERNAL_SERVER_ERROR */ ||
                status == 503 /* SC_SERVICE_UNAVAILABLE */ ||
                status == 501 /* SC_NOT_IMPLEMENTED */;
    }


    /**
     * Add an input filter to the current request. If the encoding is not supported, a 501 response will be returned to
     * the client.
     */
    private void addInputFilter(InputFilter[] inputFilters, String encodingName) {

        // Trim provided encoding name and convert to lower case since transfer
        // encoding names are case insensitive. (RFC2616, section 3.6)
        encodingName = encodingName.trim().toLowerCase(Locale.ENGLISH);

        if (encodingName.equals("identity")) {
            // Skip
        } else if (encodingName.equals("chunked")) {
            inputBuffer.addActiveFilter(inputFilters[Constants.CHUNKED_FILTER]);
            contentDelimitation = true;
        } else {
            for (int i = plugGableFilterIndex; i < inputFilters.length; i++) {
                if (Objects.equals(inputFilters[i].getEncodingName().toString(), encodingName)) {
                    inputBuffer.addActiveFilter(inputFilters[i]);
                    return;
                }
            }
            // Unsupported transfer encoding
            // 501 - Unimplemented
            //TODO response.setStatus(501);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.prepare") + " Unsupported transfer encoding [" + encodingName + "]");
            }
        }
    }

    private SocketBean receiver;

    @Override
    public AbstractEndpoint.Handler.SocketState service(SocketWrapperBase<?> socketWrapper) throws IOException {
        //RequestInfo rp = request.getRequestProcessor();
        //rp.setStage(priv.bigant.intrance.common.coyote.Constants.STAGE_PARSE);
        // Setting up the I/O
        setSocketWrapper(socketWrapper);
        inputBuffer.init(socketWrapper);
        outputBuffer.init(socketWrapper);
        // Flags
        keepAlive = true;
        openSocket = false;
        readComplete = true;
        boolean keptAlive = false;
        SocketWrapperBase<NioChannel> responseSocketWrapper = null;

        do {
            if (request.isConnection() && response.isConnection()) {
                log.debug("keep-alive");
                responseInputBuffer.nextRequest();
                inputBuffer.nextRequest();
            }

            // Parsing the request header
            try {
                if (!inputBuffer.parseRequestLine(keptAlive)) {//解析http请求第一行
                    if (inputBuffer.getParsingRequestLinePhase() == -1) {
                        return AbstractEndpoint.Handler.SocketState.UPGRADING;
                    } else if (handleIncompleteRequestLineRead()) {
                        break;
                    }
                }

                if (endpoint.isPaused()) {
                    // 503 - Service unavailable
                    //TODO response.setStatus(503);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                } else {
                    keptAlive = true;
                    // Set this every time in case limit has been changed via JMX
                    request.getMimeHeaders().setLimit(endpoint.getMaxHeaderCount());
                    if (!inputBuffer.parseHeaders()) {//解析http请求头
                        // We've read part of the request, don't recycle it
                        // instead associate it with the socket
                        openSocket = true;
                        readComplete = false;
                        break;
                    }
                    if (!disableUploadTimeout) {
                        socketWrapper.setReadTimeout(connectionUploadTimeout);
                    }
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.header.parse"), e);
                }
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                break;
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                UserDataHelper.Mode logMode = userDataHelper.getNextMode();
                if (logMode != null) {
                    String message = sm.getString("http11processor.header.parse");
                    switch (logMode) {
                        case INFO_THEN_DEBUG:
                            message += sm.getString("http11processor.fallToDebug");
                            //$FALL-THROUGH$
                        case INFO:
                            log.info(message, t);
                            break;
                        case DEBUG:
                            log.debug(message, t);
                    }
                }
                // 400 - Bad Request
                //TODO response.setStatus(400);
                setErrorState(ErrorState.CLOSE_CLEAN, t);
            }


            if (responseSocketWrapper == null) {
                receiver = getSocketBean();
                if (receiver == null) {
                    log.error("receiver is null error");
                }

                NioChannel nioChannel = new NioChannel(receiver.getSocketChannel(), new SocketBufferHandler(2048, 2048, true));
                responseSocketWrapper = new NioEndpoint.NioSocketWrapper(nioChannel, (NioEndpoint) endpoint);
                nioChannel.setSocketWrapper(responseSocketWrapper);
                responseInputBuffer.init(responseSocketWrapper);
            }

            try {
                mutual(socketWrapper, inputBuffer.getByteBuffer(), receiver.getSocketChannel(), request.isChunked(), request.getContentLength());
            } catch (IOException e) {
                log.error("request mutual error", e);
                throw e;
            }

            try {
                if (!responseInputBuffer.parseResponseLine(keptAlive)) {//解析http请求第一行
                    if (responseInputBuffer.getParsingRequestLinePhase() == -1) {
                        return AbstractEndpoint.Handler.SocketState.UPGRADING;
                    } else if (handleIncompleteRequestLineRead()) {
                        break;
                    }
                }

                if (endpoint.isPaused()) {
                    // 503 - Service unavailable
                    //TODO response.setStatus(503);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                } else {
                    keptAlive = true;
                    // Set this every time in case limit has been changed via JMX
                    response.getMimeHeaders().setLimit(endpoint.getMaxHeaderCount());
                    if (!responseInputBuffer.parseHeaders()) {//解析http请求头
                        // We've read part of the request, don't recycle it
                        // instead associate it with the socket
                        openSocket = true;
                        readComplete = false;
                        break;
                    }
                    if (!disableUploadTimeout) {
                        socketWrapper.setReadTimeout(connectionUploadTimeout);
                    }
                }

            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.header.parse"), e);
                }
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                break;
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                UserDataHelper.Mode logMode = userDataHelper.getNextMode();
                if (logMode != null) {
                    String message = sm.getString("http11processor.header.parse");
                    switch (logMode) {
                        case INFO_THEN_DEBUG:
                            message += sm.getString("http11processor.fallToDebug");
                            //$FALL-THROUGH$
                        case INFO:
                            log.info(message, t);
                            break;
                        case DEBUG:
                            log.debug(message, t);
                    }
                }
                // 400 - Bad Request
                //TODO response.setStatus(400);
                setErrorState(ErrorState.CLOSE_CLEAN, t);
            }


            try {
                NioChannel socket = (NioChannel) socketWrapper.getSocket();
                mutual(responseSocketWrapper, responseInputBuffer.getByteBuffer(), socket.getIOChannel(), response.isChunked(), response.getContentLength());
            } catch (IOException e) {
                log.error("request mutual error", e);
                throw e;
            }


            //TODO 目测剩下的都没用


            // Has an upgrade been requested?
            /*Enumeration<String> connectionValues = request.getMimeHeaders().values("Connection");
            boolean foundUpgrade = false;
            while (connectionValues.hasMoreElements() && !foundUpgrade) {
                foundUpgrade = connectionValues.nextElement().toLowerCase(Locale.ENGLISH).contains("upgrade");
            }

            if (foundUpgrade) {
                // Check the protocol
                String requestedProtocol = request.getHeader("Upgrade");

                UpgradeProtocol upgradeProtocol = httpUpgradeProtocols.get(requestedProtocol);
                if (upgradeProtocol != null) {
                    if (upgradeProtocol.accept(request)) {
                        // TODO Figure out how to handle request bodies at this
                        // point.
                        response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
                        response.setHeader("Connection", "Upgrade");
                        response.setHeader("Upgrade", requestedProtocol);
                        action(ActionCode.CLOSE, null);
                        getAdapter().log(request, response, 0);

                        InternalHttpUpgradeHandler upgradeHandler = upgradeProtocol.getInternalUpgradeHandler(getAdapter(), cloneRequest(request));
                        //UpgradeToken upgradeToken = new UpgradeToken(upgradeHandler, null, null);
                        //action(ActionCode.UPGRADE, upgradeToken);
                        return SocketState.UPGRADING;
                    }
                }
            }

            if (getErrorState().isIoAllowed()) {
                // Setting up filters, and parse some request headers
                rp.setStage(priv.bigant.intrance.common.coyote.Constants.STAGE_PREPARE);
                try {
                    prepareRequest();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("http11processor.request.prepare"), t);
                    }
                    // 500 - Internal Server Error
                    response.setStatus(500);
                    setErrorState(ErrorState.CLOSE_CLEAN, t);
                }
            }

            if (maxKeepAliveRequests == 1) {
                keepAlive = false;
            } else if (maxKeepAliveRequests > 0 && socketWrapper.decrementKeepAlive() <= 0) {
                keepAlive = false;
            }

            // Process the request in the adapter
            if (getErrorState().isIoAllowed()) {
                try {
                    rp.setStage(priv.bigant.intrance.common.coyote.Constants.STAGE_SERVICE);
                    getAdapter().service(request, response);
                    // Handle when the response was committed before a serious
                    // error occurred.  Throwing a ServletException should both
                    // set the status to 500 and set the errorException.
                    // If we fail here, then the response is likely already
                    // committed, so we can't try and set headers.
                    if (keepAlive && !getErrorState().isError() && !isAsync() && statusDropsConnection(response.getStatus())) {
                        setErrorState(ErrorState.CLOSE_CLEAN, null);
                    }
                } catch (InterruptedIOException e) {
                    setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                } catch (HeadersTooLargeException e) {
                    log.error(sm.getString("http11processor.request.process"), e);
                    // The response should not have been committed but check it
                    // anyway to be safe
                    if (response.isCommitted()) {
                        setErrorState(ErrorState.CLOSE_NOW, e);
                    } else {
                        response.reset();
                        response.setStatus(500);
                        setErrorState(ErrorState.CLOSE_CLEAN, e);
                        response.setHeader("Connection", "close"); // TODO: Remove
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("http11processor.request.process"), t);
                    // 500 - Internal Server Error
                    response.setStatus(500);
                    setErrorState(ErrorState.CLOSE_CLEAN, t);
                    getAdapter().log(request, response, 0);
                }
            }

            // Finish the handling of the request
            rp.setStage(priv.bigant.intrance.common.coyote.Constants.STAGE_ENDINPUT);
            if (!isAsync()) {
                // If this is an async request then the request ends when it has
                // been completed. The AsyncContext is responsible for calling
                // endRequest() in that case.
                endRequest();
            }
            rp.setStage(priv.bigant.intrance.common.coyote.Constants.STAGE_ENDOUTPUT);

            // If there was an error, make sure the request is counted as
            // and error, and update the statistics counter
            if (getErrorState().isError()) {
                response.setStatus(500);
            }

            if (!isAsync() || getErrorState().isError()) {
                request.updateCounters();
                if (getErrorState().isIoAllowed()) {
                    inputBuffer.nextRequest();
                    outputBuffer.nextRequest();
                }
            }

            if (!disableUploadTimeout) {
                int soTimeout = endpoint.getConnectionTimeout();
                if (soTimeout > 0) {
                    socketWrapper.setReadTimeout(soTimeout);
                } else {
                    socketWrapper.setReadTimeout(0);
                }
            }

            rp.setStage(priv.bigant.intrance.common.coyote.Constants.STAGE_KEEPALIVE);

            sendfileState = processSendfile(socketWrapper);*/

            boolean connection = request.isConnection();
            boolean connection1 = response.isConnection();
            System.out.println();
        }
        while (!getErrorState().isError() && request.isConnection() && response.isConnection() && upgradeToken == null && !endpoint.isPaused());
        log.debug("http 完成");
        close();
        /*rp.setStage(priv.bigant.intrance.common.coyote.Constants.STAGE_ENDED);

        if (getErrorState().isError() || endpoint.isPaused()) {
            return AbstractEndpoint.Handler.SocketState.CLOSED;
        } else if (isAsync()) {
            return SocketState.LONG;
        } else if (isUpgrade()) {
            return SocketState.UPGRADING;
        } else {
            if (sendfileState == SendfileState.PENDING) {
                return SocketState.SENDFILE;
            } else {
                if (openSocket) {
                    if (readComplete) {
                        return SocketState.OPEN;
                    } else {
                        return SocketState.LONG;
                    }
                } else {
                    return SocketState.CLOSED;
                }
            }
        }*/
        return null;
    }

    public abstract SocketBean getSocketBean();

    public abstract void close() throws IOException;

    private static final byte[] chunkedEndByte = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    private ByteBuffer thisBuffer = ByteBuffer.allocate(2048);

    //数据传输使用
    private void mutual(SocketWrapperBase socketWrapperBase, ByteBuffer byteBuffer, SocketChannel socketChannel, boolean chunked, int contentLength) throws IOException {
        int bodySize = byteBuffer.limit() - byteBuffer.position();
        byteBuffer.position(0);
        socketChannel.write(byteBuffer);
        if (log.isDebugEnabled()) {
            log.debug("write:" + new String(byteBuffer.array(), StandardCharsets.ISO_8859_1));
        }
        int by = 0;
        if (chunked) {
            byte[] subArray = null;
            do {
                thisBuffer.position(0);
                thisBuffer.limit(thisBuffer.capacity());//展开内存
                int read = socketWrapperBase.read(false, thisBuffer);
                by += read;
                thisBuffer.flip();
                socketChannel.write(thisBuffer);
                if (log.isDebugEnabled()) {
                    log.debug("write:" + new String(thisBuffer.array(), 0, read));
                }
                byte[] array = thisBuffer.array();
                subArray = ArrayUtils.subarray(array, read - 5, read);
            } while (!Arrays.equals(subArray, chunkedEndByte));
        } else {
            while (bodySize < contentLength) {
                thisBuffer.position(0);
                thisBuffer.limit(thisBuffer.capacity());//展开内存
                int read = socketWrapperBase.read(false, thisBuffer);
                bodySize += read;
                thisBuffer.flip();
                socketChannel.write(thisBuffer);
                if (log.isDebugEnabled()) {
                    log.debug("write:" + new String(thisBuffer.array(), 0, read));

                }
            }
        }

    }

    private Request cloneRequest(Request source) throws IOException {
        Request dest = new Request();

        // Transfer the minimal information required for the copy of the Request
        // that is passed to the HTTP upgrade process

        dest.decodedURI().duplicate(source.decodedURI());
        dest.method().duplicate(source.method());
        dest.getMimeHeaders().duplicate(source.getMimeHeaders());
        dest.requestURI().duplicate(source.requestURI());
        dest.queryString().duplicate(source.queryString());

        return dest;

    }

    private boolean handleIncompleteRequestLineRead() {
        // Haven't finished reading the request so keep the socket
        // open
        openSocket = true;
        // Check to see if we have read any of the request line yet
        if (inputBuffer.getParsingRequestLinePhase() > 1) {
            // Started to read request line.
            if (endpoint.isPaused()) {
                // Partially processed the request so need to respond
                //TODO response.setStatus(503);
                setErrorState(ErrorState.CLOSE_CLEAN, null);
                return false;
            } else {
                // Need to keep processor associated with socket
                readComplete = false;
            }
        }
        return true;
    }


    private void checkExpectationAndResponseStatus() {
        if (request.hasExpectation()
            //TODO      && (response.getStatus() < 200 || response.getStatus() > 299)
        ) {
            // Client sent Expect: 100-continue but received a
            // non-2xx final response. Disable keep-alive (if enabled)
            // to ensure that the connection is closed. Some clients may
            // still send the body, some may send the next request.
            // No way to differentiate, so close the connection to
            // force the client to send the next request.
            inputBuffer.setSwallowInput(false);
            keepAlive = false;
        }
    }


    private String userAgent = null;

    private void prepareRequestHttpVersion() {
        http11 = true;
        http09 = false;
        contentDelimitation = false;

        //TODO
        // if (endpoint.isSSLEnabled()) {
        //    request.scheme().setString("https");
        //}
        MessageBytes protocolMB = request.protocol();
        if (protocolMB.equals(Constants.HTTP_11)) {
            http11 = true;
            protocolMB.setString(Constants.HTTP_11);
        } else if (protocolMB.equals(Constants.HTTP_10)) {
            http11 = false;
            keepAlive = false;
            protocolMB.setString(Constants.HTTP_10);
        } else if (protocolMB.equals("")) {
            // HTTP/0.9
            http09 = true;
            http11 = false;
            keepAlive = false;
        } else {
            // Unsupported protocol
            http11 = false;
            // Send 505; Unsupported HTTP version
            //TODO response.setStatus(505);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.prepare") + " Unsupported HTTP version \"" + protocolMB + "\"");
            }
            return;
        }
    }

    private void prepareRequestHttpURL(MessageBytes hostValueMB) {
        MimeHeaders headers = request.getMimeHeaders();
        // Check for an absolute-URI less the query string which has already
        // been removed during the parsing of the request line
        ByteChunk uriBC = request.requestURI().getByteChunk();
        byte[] uriB = uriBC.getBytes();
        if (uriBC.startsWithIgnoreCase("http", 0)) {
            int pos = 4;
            // Check for https
            if (uriBC.startsWithIgnoreCase("s", pos)) {
                pos++;
            }
            // Next 3 characters must be "://"
            if (uriBC.startsWith("://", pos)) {
                pos += 3;
                int uriBCStart = uriBC.getStart();

                // '/' does not appear in the authority so use the first
                // instance to split the authority and the path segments
                int slashPos = uriBC.indexOf('/', pos);
                // '@' in the authority delimits the userinfo
                int atPos = uriBC.indexOf('@', pos);
                if (slashPos > -1 && atPos > slashPos) {
                    // First '@' is in the path segments so no userinfo
                    atPos = -1;
                }

                if (slashPos == -1) {
                    slashPos = uriBC.getLength();
                    // Set URI as "/". Use 6 as it will always be a '/'.
                    // 01234567
                    // http://
                    // https://
                    request.requestURI().setBytes(uriB, uriBCStart + 6, 1);
                } else {
                    request.requestURI().setBytes(uriB, uriBCStart + slashPos, uriBC.getLength() - slashPos);
                }

                // Skip any user info
                if (atPos != -1) {
                    // Validate the userinfo
                    for (; pos < atPos; pos++) {
                        byte c = uriB[uriBCStart + pos];
                        if (!HttpParser.isUserInfo(c)) {
                            // Strictly there needs to be a check for valid %nn
                            // encoding here but skip it since it will never be
                            // decoded because the userinfo is ignored
                            //TODO response.setStatus(400);
                            setErrorState(ErrorState.CLOSE_CLEAN, null);
                            if (log.isDebugEnabled()) {
                                log.debug(sm.getString("http11processor.request.invalidUserInfo"));
                            }
                            break;
                        }
                    }
                    // Skip the '@'
                    pos = atPos + 1;
                }

                if (http11) {
                    // Missing host header is illegal but handled above
                    if (hostValueMB != null) {
                        // Any host in the request line must be consistent with
                        // the Host header
                        if (!hostValueMB.getByteChunk().equals(
                                uriB, uriBCStart + pos, slashPos - pos)) {
                            if (allowHostHeaderMismatch) {
                                // The requirements of RFC 2616 are being
                                // applied. If the host header and the request
                                // line do not agree, the request line takes
                                // precedence
                                hostValueMB = headers.setValue("host");
                                hostValueMB.setBytes(uriB, uriBCStart + pos, slashPos - pos);
                            } else {
                                // The requirements of RFC 7230 are being
                                // applied. If the host header and the request
                                // line do not agree, trigger a 400 response.
                                //TODO response.setStatus(400);
                                setErrorState(ErrorState.CLOSE_CLEAN, null);
                                if (log.isDebugEnabled()) {
                                    log.debug(sm.getString("http11processor.request.inconsistentHosts"));
                                }
                            }
                        }
                    }
                } else {
                    // Not HTTP/1.1 - no Host header so generate one since
                    // Tomcat internals assume it is set
                    hostValueMB = headers.setValue("host");
                    hostValueMB.setBytes(uriB, uriBCStart + pos, slashPos - pos);
                }
            } else {
                //TODO response.setStatus(400);
                setErrorState(ErrorState.CLOSE_CLEAN, null);
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.request.invalidScheme"));
                }
            }
        }

        // Validate the characters in the URI. %nn decoding will be checked at
        // the point of decoding.
        for (int i = uriBC.getStart(); i < uriBC.getEnd(); i++) {
            if (!httpParser.isAbsolutePathRelaxed(uriB[i])) {
                //TODO response.setStatus(400);
                setErrorState(ErrorState.CLOSE_CLEAN, null);
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.request.invalidUri"));
                }
                break;
            }
        }
    }

    private void prepareRequestConnection() {
        MimeHeaders headers = request.getMimeHeaders();
        // Check connection header
        MessageBytes connectionValueMB = headers.getValue(Constants.CONNECTION);
        if (connectionValueMB != null) {
            ByteChunk connectionValueBC = connectionValueMB.getByteChunk();
            if (findBytes(connectionValueBC, Constants.CLOSE_BYTES) != -1) {
                keepAlive = false;
            } else if (findBytes(connectionValueBC, Constants.KEEPALIVE_BYTES) != -1) {
                keepAlive = true;
            }
        }
    }

    private void prepareResponseConnection() {
        MimeHeaders headers = response.getMimeHeaders();
        // Check connection header
        MessageBytes connectionValueMB = headers.getValue(Constants.CONNECTION);
        if (connectionValueMB != null) {
            ByteChunk connectionValueBC = connectionValueMB.getByteChunk();
            if (findBytes(connectionValueBC, Constants.CLOSE_BYTES) != -1) {
                keepAlive = false;
            } else if (findBytes(connectionValueBC, Constants.KEEPALIVE_BYTES) != -1) {
                keepAlive = true;
            }
        }
    }

    private void prepareRequestExpect() {
        MimeHeaders headers = request.getMimeHeaders();
        //TODO  检测连接是否可用  暂且不处理此类型 交给真正服务器处理
        if (http11) {
            MessageBytes expectMB = headers.getValue("expect");
            if (expectMB != null) {
                if (expectMB.indexOfIgnoreCase("100-continue", 0) != -1) {
                    inputBuffer.setSwallowInput(false);
                    request.setExpectation(true);
                } else {
                    //TODO response.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                }
            }
        }
    }

    private void prepareRequestUserAgent() {
        MimeHeaders headers = request.getMimeHeaders();

        // Check user-agent header 操作系统 浏览器 等系统消息
        if (restrictedUserAgents != null && (http11 || keepAlive)) {
            MessageBytes userAgentValueMB = headers.getValue("user-agent");
            // Check in the restricted list, and adjust the http11
            // and keepAlive flags accordingly
            if (userAgentValueMB != null) {
                String userAgentValue = userAgentValueMB.toString();
                userAgent = userAgentValue;
                //正则表达式验证是否合格
                if (restrictedUserAgents != null && restrictedUserAgents.matcher(userAgentValue).matches()) {
                    http11 = false;
                    keepAlive = false;
                }
            }
        }
    }


    private MessageBytes prepareRequestHost() {
        MimeHeaders headers = request.getMimeHeaders();
        // Check host header 请求头host必须有
        MessageBytes hostValueMB = null;
        try {
            hostValueMB = headers.getUniqueValue("host");
        } catch (IllegalArgumentException iae) {
            // Multiple Host headers are not permitted
            // 400 - Bad request
            //TODO response.setStatus(400);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.multipleHosts"));
            }
        }
        if (http11 && hostValueMB == null) {
            // 400 - Bad request
            //TODO response.setStatus(400);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.noHostHeader"));
            }
        }
        String host = hostValueMB.getString();
        int n = host.indexOf(':');
        if (n > 0) {
            //this.host = host.substring(0, n).trim();
        } else {
            //this.host = host.trim();
        }
        return hostValueMB;
    }

    private boolean chunked = false;

    private void prepareRequestTransferEncoding() {
        MimeHeaders headers = request.getMimeHeaders();
        // Parse transfer-encoding header
        if (http11) {
            MessageBytes transferEncodingValueMB = headers.getValue("transfer-encoding");
            if (transferEncodingValueMB != null) {
                String transferEncodingValue = transferEncodingValueMB.toString();
                if (transferEncodingValue != null && transferEncodingValue.contains("chunked")) {
                    chunked = true;
                }
                // Parse the comma separated list. "identity" codings are ignored
               /* int startPos = 0;
                int commaPos = transferEncodingValue.indexOf(',');
                String encodingName = null;
                while (commaPos != -1) {
                    encodingName = transferEncodingValue.substring(startPos, commaPos);
                    addInputFilter(inputFilters, encodingName);
                    startPos = commaPos + 1;
                    commaPos = transferEncodingValue.indexOf(',', startPos);
                }
                encodingName = transferEncodingValue.substring(startPos);
                addInputFilter(inputFilters, encodingName);*/
            }
        }
    }

    private void prepareResponseTransferEncoding() {
        MimeHeaders headers = response.getMimeHeaders();
        // Parse transfer-encoding header
        if (http11) {
            MessageBytes transferEncodingValueMB = headers.getValue("transfer-encoding");
            if (transferEncodingValueMB != null) {
                String transferEncodingValue = transferEncodingValueMB.toString();
                if (transferEncodingValue != null && transferEncodingValue.contains("chunked")) {
                    chunked = true;
                }
            }
        }
    }

    private void prepareRequestContentLength(InputFilter[] inputFilters) {
        MimeHeaders headers = request.getMimeHeaders();
        // Parse content-length header
        long contentLength = request.getContentLength();
        if (contentLength >= 0) {
            if (contentDelimitation) {
                // contentDelimitation being true at this point indicates that
                // chunked encoding is being used but chunked encoding should
                // not be used with a content length. RFC 2616, section 4.4,
                // bullet 3 states Content-Length must be ignored in this case -
                // so remove it.
                headers.removeHeader("content-length");
                request.setContentLength(-1);
            } else {
                inputBuffer.addActiveFilter(inputFilters[Constants.IDENTITY_FILTER]);
                contentDelimitation = true;
            }
        }
    }

    /**
     * After reading the request headers, we have to setup the request filters.
     */
    private void prepareRequest() {
        prepareRequestHttpVersion();
        prepareRequestConnection();
        prepareRequestUserAgent();
        MessageBytes hostValueMB = prepareRequestHost();
        prepareRequestHttpURL(hostValueMB);
        // Input filter setup
        prepareRequestTransferEncoding();

        // Validate host name and extract port if present
        parseHost(hostValueMB);

        if (!contentDelimitation) {
            // If there's no content length
            // (broken HTTP/1.0 or HTTP/1.1), assume
            // the client is not broken and didn't send a body
            //TODO inputBuffer.addActiveFilter(inputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        if (!getErrorState().isIoAllowed()) {
            getAdapter().log(request, response, 0);
        }
    }

    /**
     * After reading the request headers, we have to setup the request filters.
     */
    protected void prepareResponse1() {
        prepareResponseConnection();
        MessageBytes hostValueMB = prepareRequestHost();
        prepareRequestHttpURL(hostValueMB);
        // Input filter setup
        prepareRequestTransferEncoding();
        // Validate host name and extract port if present
        parseHost(hostValueMB);
    }

    /**
     * When committing the response, we have to validate the set of headers, as well as setup the response filters.
     */
    @Override
    protected final void prepareResponse() throws IOException {

        boolean entityBody = true;
        contentDelimitation = false;

        OutputFilter[] outputFilters = outputBuffer.getFilters();

        if (http09) {
            // HTTP/0.9
            outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            outputBuffer.commit();
            return;
        }

        int statusCode = 0;//TODO = response.getStatus();
        if (statusCode < 200 || statusCode == 204 || statusCode == 205 || statusCode == 304) {
            // No entity body
            outputBuffer.addActiveFilter(outputFilters[Constants.VOID_FILTER]);
            entityBody = false;
            contentDelimitation = true;
            if (statusCode == 205) {
                // RFC 7231 requires the server to explicitly signal an empty
                // response in this case
                response.setContentLength(0);
            } else {
                response.setContentLength(-1);
            }
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals("HEAD")) {
            // No entity body
            outputBuffer.addActiveFilter(outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        // Sendfile support
        if (endpoint.getUseSendfile()) {
            prepareSendfile(outputFilters);
        }

        // Check for compression
        boolean isCompressible = false;
        boolean useCompression = false;
        if (entityBody && (compressionLevel > 0) && sendFileData == null) {
            isCompressible = isCompressible();
            if (isCompressible) {
                useCompression = useCompression();
            }
            // Change content-length to -1 to force chunking
            if (useCompression) {
                response.setContentLength(-1);
            }
        }

        MimeHeaders headers = response.getMimeHeaders();
        // A SC_NO_CONTENT response may include entity headers
        if (entityBody || statusCode == HttpServletResponse.SC_NO_CONTENT) {
            String contentType = response.getContentType();
            if (contentType != null) {
                headers.setValue("Content-Type").setString(contentType);
            }
            String contentLanguage = null;//TODO  = response.getContentLanguage();
            if (contentLanguage != null) {
                headers.setValue("Content-Language").setString(contentLanguage);
            }
        }

        long contentLength = response.getContentLengthLong();
        boolean connectionClosePresent = false;
        if (contentLength != -1) {
            headers.setValue("Content-Length").setLong(contentLength);
            outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            contentDelimitation = true;
        } else {
            // If the response code supports an entity body and we're on
            // HTTP 1.1 then we chunk unless we have a Connection: close header
            connectionClosePresent = isConnectionClose(headers);
            if (entityBody && http11 && !connectionClosePresent) {
                outputBuffer.addActiveFilter(outputFilters[Constants.CHUNKED_FILTER]);
                contentDelimitation = true;
                headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
            } else {
                outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            }
        }

        if (useCompression) {
            outputBuffer.addActiveFilter(outputFilters[Constants.GZIP_FILTER]);
            headers.setValue("Content-Encoding").setString("gzip");
        }
        // If it might be compressed, set the Vary header
        if (isCompressible) {
            // Make Proxies happy via Vary (from mod_deflate)
            MessageBytes vary = headers.getValue("Vary");
            if (vary == null) {
                // Add a new Vary header
                headers.setValue("Vary").setString("Accept-Encoding");
            } else if (vary.equals("*")) {
                // No action required
            } else {
                // Merge into current header
                headers.setValue("Vary").setString(vary.getString() + ",Accept-Encoding");
            }
        }

        // Add date header unless application has already set one (e.g. in a
        // Caching Filter)
        if (headers.getValue("Date") == null) {
            headers.addValue("Date").setString(FastHttpDateFormat.getCurrentDate());
        }

        // FIXME: Add transfer encoding header

        if ((entityBody) && (!contentDelimitation)) {
            // Mark as close the connection after the request, and add the
            // connection: close header
            keepAlive = false;
        }

        // This may disabled keep-alive to check before working out the
        // Connection header.
        checkExpectationAndResponseStatus();

        // If we know that the request is bad this early, add the
        // Connection: close header.
        if (keepAlive && statusDropsConnection(statusCode)) {
            keepAlive = false;
        }
        if (!keepAlive) {
            // Avoid adding the close header twice
            if (!connectionClosePresent) {
                headers.addValue(Constants.CONNECTION).setString(Constants.CLOSE);
            }
        } else if (!http11 && !getErrorState().isError()) {
            headers.addValue(Constants.CONNECTION).setString(Constants.KEEPALIVE);
        }

        // Add server header
        if (server == null) {
            if (serverRemoveAppProvidedValues) {
                headers.removeHeader("server");
            }
        } else {
            // server always overrides anything the app might set
            headers.setValue("Server").setString(server);
        }

        // Build the response header
        try {
            outputBuffer.sendStatus();

            int size = headers.size();
            for (int i = 0; i < size; i++) {
                outputBuffer.sendHeader(headers.getName(i), headers.getValue(i));
            }
            outputBuffer.endHeaders();
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // If something goes wrong, reset the header buffer so the error
            // response can be written instead.
            outputBuffer.resetHeaderBuffer();
            throw t;
        }

        outputBuffer.commit();
    }

    private static boolean isConnectionClose(MimeHeaders headers) {
        MessageBytes connection = headers.getValue(Constants.CONNECTION);
        if (connection == null) {
            return false;
        }
        return connection.equals(Constants.CLOSE);
    }

    private void prepareSendfile(OutputFilter[] outputFilters) {
        String fileName = (String) request.getAttribute(
                priv.bigant.intrance.common.coyote.Constants.SENDFILE_FILENAME_ATTR);
        if (fileName == null) {
            sendFileData = null;
        } else {
            // No entity body sent here
            outputBuffer.addActiveFilter(outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
            long pos = (Long) request.getAttribute(priv.bigant.intrance.common.coyote.Constants.SENDFILE_FILE_START_ATTR);
            long end = (Long) request.getAttribute(priv.bigant.intrance.common.coyote.Constants.SENDFILE_FILE_END_ATTR);
            sendFileData = socketWrapper.createSendfileData(fileName, pos, end - pos);
        }
    }


    /*
     * Note: populateHost() is not over-ridden.
     *       request.serverName() will be set to return the default host name by
     *       the Mapper.
     */


    /**
     * {@inheritDoc}
     * <p>
     * This implementation provides the server port from the local port.
     */
    @Override
    protected void populatePort() {
        // Ensure the local port field is populated before using it.
        request.action(ActionCode.REQ_LOCALPORT_ATTRIBUTE, request);
        request.setServerPort(request.getLocalPort());
    }


    @Override
    protected boolean flushBufferedWrite() throws IOException {
        if (outputBuffer.hasDataToWrite()) {
            if (outputBuffer.flushBuffer(false)) {
                // The buffer wasn't fully flushed so re-register the
                // socket for write. Note this does not go via the
                // Response since the write registration state at
                // that level should remain unchanged. Once the buffer
                // has been emptied then the code below will call
                // Adaptor.asyncDispatch() which will enable the
                // Response to respond to this event.
                outputBuffer.registerWriteInterest();
                return true;
            }
        }
        return false;
    }


    @Override
    protected SocketState dispatchEndRequest() {
        if (!keepAlive) {
            return SocketState.CLOSED;
        } else {
            endRequest();
            inputBuffer.nextRequest();
            outputBuffer.nextRequest();
            if (socketWrapper.isReadPending()) {
                return AbstractEndpoint.Handler.SocketState.LONG;
            } else {
                return AbstractEndpoint.Handler.SocketState.OPEN;
            }
        }
    }


    @Override
    protected Logger getLog() {
        return log;
    }


    /*
     * No more input will be passed to the application. Remaining input will be
     * swallowed or the connection dropped depending on the error and
     * expectation status.
     */
    private void endRequest() {
        if (getErrorState().isError()) {
            // If we know we are closing the connection, don't drain
            // input. This way uploading a 100GB file doesn't tie up the
            // thread if the servlet has rejected it.
            inputBuffer.setSwallowInput(false);
        } else {
            // Need to check this again here in case the response was
            // committed before the error that requires the connection
            // to be closed occurred.
            checkExpectationAndResponseStatus();
        }

        // Finish the handling of the request
        if (getErrorState().isIoAllowed()) {
            try {
                inputBuffer.endRequest();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                // 500 - Internal Server Error
                // Can't add a 500 to the access log since that has already been
                // written in the Adapter.service method.
                //TODO response.setStatus(500);
                setErrorState(ErrorState.CLOSE_NOW, t);
                log.error(sm.getString("http11processor.request.finish"), t);
            }
        }
        if (getErrorState().isIoAllowed()) {
            try {
                action(ActionCode.COMMIT, null);
                outputBuffer.end();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                setErrorState(ErrorState.CLOSE_NOW, t);
                log.error(sm.getString("http11processor.response.finish"), t);
            }
        }
    }


    @Override
    protected final void finishResponse() throws IOException {
        outputBuffer.end();
    }


    @Override
    protected final void ack() {
        // Acknowledge request
        // Send a 100 status back if it makes sense (response not committed
        // yet, and client specified an expectation for 100-continue)
        if (//TODO !response.isCommitted() &&
                request.hasExpectation()) {
            inputBuffer.setSwallowInput(true);
            try {
                outputBuffer.sendAck();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            }
        }
    }


    @Override
    protected final void flush() throws IOException {
        outputBuffer.flush();
    }


    @Override
    protected final int available(boolean doRead) {
        return inputBuffer.available(doRead);
    }


    @Override
    protected final void setRequestBody(ByteChunk body) {
        InputFilter savedBody = new SavedRequestInputFilter(body);
        Http11InputBuffer internalBuffer = (Http11InputBuffer) request.getInputBuffer();
        internalBuffer.addActiveFilter(savedBody);
    }


    @Override
    protected final void setSwallowResponse() {
        outputBuffer.responseFinished = true;
    }


    @Override
    protected final void disableSwallowRequest() {
        inputBuffer.setSwallowInput(false);
    }


    @Override
    protected final void sslReHandShake() throws IOException {
        if (sslSupport != null) {
            // Consume and buffer the request body, so that it does not
            // interfere with the client's handshake messages
            InputFilter[] inputFilters = inputBuffer.getFilters();
            ((BufferedInputFilter) inputFilters[Constants.BUFFERED_FILTER]).setLimit(
                    maxSavePostSize);
            inputBuffer.addActiveFilter(inputFilters[Constants.BUFFERED_FILTER]);

            /*
             * Outside the try/catch because we want I/O errors during
             * renegotiation to be thrown for the caller to handle since they
             * will be fatal to the connection.
             */
            socketWrapper.doClientAuth(sslSupport);
            try {
                /*
                 * Errors processing the cert chain do not affect the client
                 * connection so they can be logged and swallowed here.
                 */
                Object sslO = sslSupport.getPeerCertificateChain();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
                }
            } catch (IOException ioe) {
                log.warn(sm.getString("http11processor.socket.ssl"), ioe);
            }
        }
    }


    @Override
    protected final boolean isRequestBodyFullyRead() {
        return inputBuffer.isFinished();
    }


    @Override
    protected final void registerReadInterest() {
        socketWrapper.registerReadInterest();
    }


    @Override
    protected final boolean isReadyForWrite() {
        return outputBuffer.isReady();
    }


    @Override
    public UpgradeToken getUpgradeToken() {
        return upgradeToken;
    }


    @Override
    protected final void doHttpUpgrade(UpgradeToken upgradeToken) {
        this.upgradeToken = upgradeToken;
        // Stop further HTTP output
        outputBuffer.responseFinished = true;
    }


    @Override
    public ByteBuffer getLeftoverInput() {
        return inputBuffer.getLeftover();
    }


    @Override
    public boolean isUpgrade() {
        return upgradeToken != null;
    }


    /**
     * Trigger sendfile processing if required.
     *
     * @return The state of send file processing
     */
    private SendfileState processSendfile(SocketWrapperBase<?> socketWrapper) {
        openSocket = keepAlive;
        // Done is equivalent to sendfile not being used
        SendfileState result = SendfileState.DONE;
        // Do sendfile as needed: add socket to sendfile and end
        if (sendFileData != null && !getErrorState().isError()) {
            if (keepAlive) {
                if (available(false) == 0) {
                    sendFileData.keepAliveState = SendfileKeepAliveState.OPEN;
                } else {
                    sendFileData.keepAliveState = SendfileKeepAliveState.PIPELINED;
                }
            } else {
                sendFileData.keepAliveState = SendfileKeepAliveState.NONE;
            }
            result = socketWrapper.processSendfile(sendFileData);
            switch (result) {
                case ERROR:
                    // Write failed
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("http11processor.sendfile.error"));
                    }
                    setErrorState(ErrorState.CLOSE_CONNECTION_NOW, null);
                    //$FALL-THROUGH$
                default:
                    sendFileData = null;
            }
        }
        return result;
    }


    @Override
    public final void recycle() {
        getAdapter().checkRecycled(request, response);
        super.recycle();
        inputBuffer.recycle();
        outputBuffer.recycle();
        upgradeToken = null;
        socketWrapper = null;
        sendFileData = null;
    }


    @Override
    public void pause() {
        // NOOP for HTTP
    }


}
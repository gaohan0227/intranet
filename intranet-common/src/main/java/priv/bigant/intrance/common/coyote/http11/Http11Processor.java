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
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import priv.bigant.intrance.common.Config;
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
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static java.lang.System.arraycopy;

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

    private final HttpParser httpParser;

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
    private String server = "BigAnt";


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


    private final boolean allowHostHeaderMismatch;

    private Config config;

    private int maxHttpHeaderSize;


    public Http11Processor(int maxHttpHeaderSize, boolean allowHostHeaderMismatch, boolean rejectIllegalHeaderName,
                           String relaxedPathChars, String relaxedQueryChars) {


        super();
        config = Config.getConfig();
        httpParser = new HttpParser(relaxedPathChars, relaxedQueryChars);

        inputBuffer = new Http11InputBuffer(request, maxHttpHeaderSize, rejectIllegalHeaderName, httpParser);
        request.setInputBuffer(inputBuffer);

        responseInputBuffer = new Http11ResponseInputBuffer(response, maxHttpHeaderSize, rejectIllegalHeaderName, httpParser);
        response.setInputBuffer(responseInputBuffer);

        this.allowHostHeaderMismatch = allowHostHeaderMismatch;
        this.maxHttpHeaderSize = maxHttpHeaderSize;
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
            this.noCompressionUserAgents = Pattern.compile(noCompressionUserAgents);
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
        if (StringUtils.isEmpty(server)) {
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

        if ((contentEncodingMB != null) && (contentEncodingMB.indexOf("gzip") != -1)) {
            return false;
        }

        // If force mode, always compress (test purposes only)
        if (compressionLevel == 2) {
            return true;
        }

        // Check if sufficient length to trigger the compression
        long contentLength = response.getContentLengthLong();
        if ((contentLength == -1) || (contentLength > compressionMinSize)) {
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
        MessageBytes acceptEncodingMB = request.getMimeHeaders().getValue("accept-encoding");

        if ((acceptEncodingMB == null) || (acceptEncodingMB.indexOf("gzip") == -1)) {
            return false;
        }

        // If force mode, always compress (test purposes only)
        if (compressionLevel == 2) {
            return true;
        }

        // Check for incompatible Browser
        if (noCompressionUserAgents != null) {
            MessageBytes userAgentValueMB = request.getMimeHeaders().getValue("user-agent");
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

    private SocketBean receiver;

    @Override
    public AbstractEndpoint.Handler.SocketState service(SocketWrapperBase<?> socketWrapper) throws IOException {
        //RequestInfo rp = request.getRequestProcessor();
        //rp.setStage(priv.bigant.intrance.common.coyote.Constants.STAGE_PARSE);
        // Setting up the I/O
        setSocketWrapper(socketWrapper);
        inputBuffer.init(socketWrapper);
        // Flags
        keepAlive = true;
        openSocket = false;
        readComplete = true;
        boolean keptAlive = false;
        int keepCount = 0;
        SocketWrapperBase<NioChannel> responseSocketWrapper = null;

        do {
            if (request.isConnection() && response.isConnection()) {
                log.debug("http keep alive" + (keepCount++));
                responseInputBuffer.nextRequest();
                inputBuffer.nextRequest();
            }

            // Parsing the request header
            try {
                if (!inputBuffer.parseRequestLine(keptAlive)) {//解析http请求第一行
                    if (inputBuffer.getParsingRequestLinePhase() == -1) {
                        return AbstractEndpoint.Handler.SocketState.UPGRADING;
                    } else if (handleIncompleteRequestLineRead()) {
                        prepareResponse(HttpResponseStatus.SC_BAD_REQUEST, "解析请求失败");
                        break;
                    }
                }

                if (isPaused()) {
                    // 503 - Service unavailable
                    //TODO response.setStatus(503);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                } else {
                    keptAlive = true;
                    // Set this every time in case limit has been changed via JMX
                    request.getMimeHeaders().setLimit(getMaxHeaderCount());
                    if (!inputBuffer.parseHeaders()) {//解析http请求头
                        // We've read part of the request, don't recycle it
                        // instead associate it with the socket
                        openSocket = true;
                        readComplete = false;
                        break;
                    }
                    /*if (!disableUploadTimeout) {
                        socketWrapper.setReadTimeout(connectionUploadTimeout);
                    }*/
                }
            } catch (IOException e) {
                log.debug("Error parsing HTTP request header:", e);
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
                    prepareResponse(HttpResponseStatus.SC_NOT_FOUND, "未找到客户端");
                    break;
                }

                NioChannel nioChannel = new NioChannel(receiver.getSocketChannel(), new SocketBufferHandler(config.getHttpProcessReadBufferSize(), config.getHttpProcessWriteBufferSize(), true));
                responseSocketWrapper = new NioSocketWrapper(nioChannel, getNioSelectorPool());
                nioChannel.setSocketWrapper(responseSocketWrapper);
                responseInputBuffer.init(responseSocketWrapper);
            }

            try {
                mutual(socketWrapper, inputBuffer.getByteBuffer(), receiver.getSocketChannel(), request.isChunked(), request.getContentLength());
            } catch (IOException e) {
                prepareResponse(HttpResponseStatus.SC_BAD_REQUEST, "发送至客户端请求失败");
                break;
            }

            try {
                if (!responseInputBuffer.parseResponseLine(keptAlive)) {//解析http请求第一行
                    if (responseInputBuffer.getParsingRequestLinePhase() == -1) {
                        return AbstractEndpoint.Handler.SocketState.UPGRADING;
                    } else if (handleIncompleteRequestLineRead()) {
                        prepareResponse(HttpResponseStatus.SC_BAD_REQUEST, "解析客户端响应失败");
                        break;
                    }
                }

                if (isPaused()) {
                    // 503 - Service unavailable
                    //TODO response.setStatus(503);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                } else {
                    keptAlive = true;
                    // Set this every time in case limit has been changed via JMX
                    response.getMimeHeaders().setLimit(getMaxHeaderCount());
                    if (!responseInputBuffer.parseHeaders()) {//解析http请求头
                        // We've read part of the request, don't recycle it
                        // instead associate it with the socket
                        openSocket = true;
                        readComplete = false;
                        prepareResponse(HttpResponseStatus.SC_BAD_REQUEST, "解析客户端响应头失败");
                        break;
                    }
                    if (!disableUploadTimeout) {
                        socketWrapper.setReadTimeout(connectionUploadTimeout);
                    }
                }

            } catch (SocketTimeoutException e) {
                //log.debug("Error parsing HTTP request header time out", e);
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                break;
            } catch (IOException e) {
                log.debug("Error parsing HTTP response header", e);
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

            log.debug(request.requestURI().getString() + "响应完成");
            try {
                NioChannel socket = (NioChannel) socketWrapper.getSocket();
                mutual(responseSocketWrapper, responseInputBuffer.getByteBuffer(), socket.getIOChannel(), response.isChunked(), response.getContentLength());
            } catch (IOException e) {
                log.error("request mutual error", e);
                break;
            }
        }
        while (!getErrorState().isError() && request.isConnection() && response.isConnection() && upgradeToken == null && !isPaused());
        log.debug("http 完成");
        close();
        return null;
    }

    public abstract SocketBean getSocketBean() throws IOException;

    public abstract int getMaxHeaderCount();

    public abstract boolean isPaused();

    public abstract NioSelectorPool getNioSelectorPool();

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
        if (chunked) {
            byte[] subArray = null;
            do {
                thisBuffer.position(0);
                thisBuffer.limit(thisBuffer.capacity());//展开内存
                int read = socketWrapperBase.read(false, thisBuffer);
                thisBuffer.flip();
                socketChannel.write(thisBuffer);
                /*if (log.isDebugEnabled()) {
                    log.debug("write:" + new String(thisBuffer.array(), 0, read));
                }*/

                //校验是否为最后的分块
                if (thisBuffer.position() > 4) {
                    byte[] array = thisBuffer.array();
                    subArray = ArrayUtils.subarray(array, read - 5, read);
                } else {
                    if (subArray == null)
                        continue;
                    int position = thisBuffer.position();
                    arraycopy(subArray, position, subArray, 0, 5 - position);
                    arraycopy(thisBuffer.array(), 0, subArray, 5 - position, position);
                }

            } while (!Arrays.equals(subArray, chunkedEndByte));
        } else {
            while (bodySize < contentLength) {
                thisBuffer.position(0);
                thisBuffer.limit(thisBuffer.capacity());//展开内存
                int read = socketWrapperBase.read(false, thisBuffer);
                bodySize += read;
                thisBuffer.flip();
                socketChannel.write(thisBuffer);
                /*if (log.isDebugEnabled()) {
                    log.debug("write:" + new String(thisBuffer.array(), 0, read));
                }*/
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
            if (isPaused()) {
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
     * When committing the response, we have to validate the set of headers
     */
    protected final void prepareResponse(HttpResponseStatus status, String bodyStr) throws IOException {

        byte[] body = null;
        if (StringUtils.isNotEmpty(bodyStr)) {
            body = createBody(status, bodyStr).getBytes(StandardCharsets.UTF_8);
        }
        Http11OutputBuffer outputBuffer = new Http11OutputBuffer(maxHttpHeaderSize);
        outputBuffer.init(socketWrapper);

        if (http09) {
            // HTTP/0.9
            outputBuffer.commit();
            return;
        }

        MimeHeaders headers = new MimeHeaders();
        if (ArrayUtils.isNotEmpty(body)) {
            headers.setValue("Content-Length").setLong(body.length);
        }
        headers.setValue("Content-Type").setString("text/html;charset=UTF-8");
        headers.setValue("Vary").setString("Accept-Encoding");
        headers.addValue("Date").setString(FastHttpDateFormat.getCurrentDate());
        headers.addValue(Constants.CONNECTION).setString(Constants.CLOSE);
        if (StringUtils.isNotEmpty(server)) {
            headers.setValue("Server").setString(server);
        }
        outputBuffer.sendStatus(status);

        int size = headers.size();
        for (int i = 0; i < size; i++) {
            outputBuffer.sendHeader(headers.getName(i), headers.getValue(i));
        }
        outputBuffer.endHeaders();

        if (ArrayUtils.isNotEmpty(body))
            outputBuffer.write(body);

        outputBuffer.commit();

        socketWrapper.flush(true);

    }

    public String bodyTemp = "<div style='text-align:center'>\n" +
            "  <div>\n" +
            "  <h1>BigAnt</h1>\n" +
            "  <hr/>\n" +
            "  </div>\n" +
            "<div>\n" +
            "  <div>\n" +
            "    <span>状态码：%d</span>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<span>内容：%s</span>\n" +
            "  </div>\n" +
            "  <div>\n" +
            "    <h2>\n" +
            "      <a href='http://www.baidu.com'>去官网</a>  \n" +
            "    </h2>\n" +
            "  </div>\n" +
            "</div>\n" +
            "</div>";

    private String createBody(HttpResponseStatus status, String bodyStr) {
        return String.format(bodyTemp, status.getStatus(), bodyStr);
    }

    private static boolean isConnectionClose(MimeHeaders headers) {
        MessageBytes connection = headers.getValue(Constants.CONNECTION);
        if (connection == null) {
            return false;
        }
        return connection.equals(Constants.CLOSE);
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
        /*TODO if (outputBuffer.hasDataToWrite()) {
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
        }*/
        return false;
    }


    @Override
    protected SocketState dispatchEndRequest() {
        if (!keepAlive) {
            return SocketState.CLOSED;
        } else {
            endRequest();
            inputBuffer.nextRequest();
            //TODO outputBuffer.nextRequest();
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
                //TODO outputBuffer.end();
            } /*catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            }*/ catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                setErrorState(ErrorState.CLOSE_NOW, t);
                log.error(sm.getString("http11processor.response.finish"), t);
            }
        }
    }


    @Override
    protected final void finishResponse() throws IOException {
        //TODO outputBuffer.end();
    }


    @Override
    protected final void ack() {
        // Acknowledge request
        // Send a 100 status back if it makes sense (response not committed
        // yet, and client specified an expectation for 100-continue)
        /*TODO
        if (!httpResponse.isCommitted() && request.hasExpectation()) {
            inputBuffer.setSwallowInput(true);
            try {
                outputBuffer.sendAck();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            }
        }*/
    }


    @Override
    protected final void flush() throws IOException {
        //TODO outputBuffer.flush();
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
        //TODO
        // outputBuffer.responseFinished = true;
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
            ((BufferedInputFilter) inputFilters[Constants.BUFFERED_FILTER]).setLimit(maxSavePostSize);
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
        return false;//TODO outputBuffer.isReady();
    }


    @Override
    public UpgradeToken getUpgradeToken() {
        return upgradeToken;
    }


    @Override
    protected final void doHttpUpgrade(UpgradeToken upgradeToken) {
        this.upgradeToken = upgradeToken;
        // Stop further HTTP output
        //TODO outputBuffer.responseFinished = true;
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
        //getAdapter().checkRecycled(request, response);
        request.recycle();
        response.recycle();
        super.recycle();
        inputBuffer.recycle();
        //TODO outputBuffer.recycle();
        responseInputBuffer.recycle();
        upgradeToken = null;
        socketWrapper = null;
        sendFileData = null;
    }


    @Override
    public void pause() {
        // NOOP for HTTP
    }


}

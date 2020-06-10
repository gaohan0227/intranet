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
package priv.bigant.intrance.common.coyote;

import priv.bigant.intrance.common.util.net.AbstractEndpoint.Handler.SocketState;
import priv.bigant.intrance.common.util.net.SocketEvent;
import priv.bigant.intrance.common.util.net.SocketWrapperBase;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Common interface for processors of all protocols.
 */
public interface Processor {

    /**
     * Process a connection. This is called whenever an event occurs (e.g. more data arrives) that allows processing to
     * continue for a connection that is not currently being processed.
     *
     * @param socketWrapper The connection to process
     * @param status        The status of the connection that triggered this additional processing
     * @return The state the caller should put the socket in when this method returns
     * @throws IOException If an I/O error occurs during the processing of the request
     */
    SocketState process(SocketWrapperBase<?> socketWrapper, SocketEvent status) throws IOException;

    /**
     * @return {@code true} if the Processor is currently processing an upgrade request, otherwise {@code false}
     */
    boolean isUpgrade();


    /**
     * @return The request associated with this processor.
     */
    Request getRequest();

    /**
     * Recycle the processor, ready for the next request which may be on the same connection or a different connection.
     */
    void recycle();

}

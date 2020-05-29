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


import priv.bigant.intrance.common.ContextBind;
import priv.bigant.intrance.common.coyote.http11.servlet.http.HttpUpgradeHandler;

/**
 * Token used during the upgrade process.
 */
public final class UpgradeToken {

    private final ContextBind contextBind;
    private final HttpUpgradeHandler httpUpgradeHandler;

    public UpgradeToken(HttpUpgradeHandler httpUpgradeHandler, ContextBind contextBind) {
        this.contextBind = contextBind;
        this.httpUpgradeHandler = httpUpgradeHandler;
    }

    public final ContextBind getContextBind() {
        return contextBind;
    }

    public final HttpUpgradeHandler getHttpUpgradeHandler() {
        return httpUpgradeHandler;
    }


}

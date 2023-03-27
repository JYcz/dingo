/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.exec.channel;

import io.dingodb.exec.Services;
import io.dingodb.net.Channel;
import io.dingodb.net.Message;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class SendEndpoint {
    private final String host;
    private final int port;
    @Getter
    private final String tag;

    private Channel channel;

    public SendEndpoint(String host, int port, String tag) {
        this.host = host;
        this.port = port;
        this.tag = tag;
    }

    public void init() {
        EndpointManager.INSTANCE.registerSendEndpoint(this);
        // This may block.
        channel = Services.openNewChannel(host, port);
        if (log.isDebugEnabled()) {
            log.debug("(tag = {}) Opened channel to {}:{}.", tag, host, port);
        }
    }

    synchronized void wakeUp() {
        notify();
    }

    synchronized boolean checkAvailableBufferCount(int size) {
        boolean successful = false;
        AtomicInteger bufferCount = EndpointManager.INSTANCE.getBufferCount(tag);
        while (!successful) {
            int origSize = bufferCount.get();
            if (origSize < 0) {
                return false;
            }
            if (origSize > size) {
                successful = bufferCount.compareAndSet(origSize, origSize - size);
            } else {
                try {
                    wait();
                } catch (InterruptedException e) {
                    log.warn("Catch (tag = {}) Interrupted while waiting for channel to be ready.", tag);
                }
            }
        }
        return true;
    }

    public boolean send(byte[] content) {
        return send(content, false);
    }

    public boolean send(byte @NonNull [] content, boolean needed) {
        boolean ok = checkAvailableBufferCount(content.length);
        if (ok || needed) {
            Message msg = new Message(tag, content);
            channel.send(msg);
        }
        return ok;
    }

    public void close() {
        EndpointManager.INSTANCE.unregisterSendEndpoint(this);
        channel.close();
        if (log.isDebugEnabled()) {
            log.debug("(tag = {}) Closed channel to {}:{}.", tag, host, port);
        }
    }
}

package io.github.vvb2060.packageinstaller.datamodel;

import android.net.TrafficStats;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.webkit.URLUtil;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.core.BinderWrapper;

public class DownloadEngine extends Thread implements Closeable {
    private static final String TAG = DownloadEngine.class.getSimpleName();
    private static final int MAX_CHANNEL_COUNT = 16;
    private static final long IDLE_TIMEOUT = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_RETRY_COUNT = 6;

    final class Task {
        private long offset;
        private final ByteBuffer buffer;
        private final CountDownLatch latch = new CountDownLatch(1);
        private IOException paddingException;
        private volatile boolean canceled = false;
        private int retryCount = 0;

        private Task(long offset, ByteBuffer buffer) {
            this.offset = offset;
            this.buffer = buffer;
        }

        void await() throws IOException {
            try {
                while (!latch.await(1, TimeUnit.SECONDS)) {
                    selector.wakeup();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (paddingException != null) {
                throw paddingException;
            } else {
                buffer.flip();
            }
        }

        boolean cancel(boolean wait) {
            if (tasks.removeLastOccurrence(this)) {
                return true;
            }
            canceled = true;
            try {
                while (wait && !latch.await(1, TimeUnit.SECONDS)) {
                    selector.wakeup();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return latch.getCount() == 0;
        }
    }

    private final SSLContext sslContext;
    private final Selector selector;

    private URI uri;
    private IpPool ipPool;

    private int concurrent = 1;
    private int cancelledKeys = 0;
    private final ConcurrentLinkedDeque<Task> tasks = new ConcurrentLinkedDeque<>();
    private long fileSize = -1;
    private String fileName;

    public DownloadEngine(SSLContext sslContext) throws IOException {
        this.sslContext = sslContext;
        this.selector = Selector.open();
    }

    public String getFileName() {
        return fileName;
    }

    long open(URI uri, ByteBuffer buffer) throws IOException {
        try {
            BinderWrapper.deviceIdle.addPowerSaveTempWhitelistApp(BuildConfig.APPLICATION_ID,
                1000, Process.myUserHandle().hashCode(), 316, "shell");
            BinderWrapper.deviceIdle.addPowerSaveWhitelistApp(BuildConfig.APPLICATION_ID);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to add power save whitelist", e);
        }

        if (uri.getHost() == null) {
            uri = URI.create("https://" + uri);
        }
        this.uri = uri;
        this.ipPool = new IpPool(uri.getHost());

        var task = new Task(-1, buffer);
        tasks.offer(task);
        start();
        task.await();
        return fileSize;
    }

    Task fetch(long offset, ByteBuffer dst) {
        var task = new Task(offset, dst);
        var empty = tasks.isEmpty();
        tasks.offer(task);
        if (empty) selector.wakeup();
        return task;
    }

    private boolean needTLS() {
        var scheme = uri.getScheme();
        if (scheme == null) {
            return true;
        }
        return !scheme.equalsIgnoreCase("http");
    }

    private int getPort() {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        } else {
            return needTLS() ? 443 : 80;
        }
    }

    private String getTarget() {
        var target = uri.getRawPath();
        if (target == null || target.isEmpty()) {
            target = "/";
        }
        var query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            target += "?" + query;
        }
        return target;
    }

    private ChannelSlot openChannel() throws IOException {
        var channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        var port = getPort();
        channel.connect(new InetSocketAddress(ipPool.get(), port));

        SSLEngine engine = null;
        if (needTLS()) {
            engine = sslContext.createSSLEngine(uri.getHost(), port);
            engine.setUseClientMode(true);
            var params = engine.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            engine.setSSLParameters(params);
            engine.beginHandshake();
        }
        var slot = new ChannelSlot(channel, engine);

        var key = channel.register(selector, SelectionKey.OP_CONNECT);
        key.attach(slot);

        return slot;
    }

    @Override
    public void close() {
        interrupt();
        try {
            BinderWrapper.deviceIdle.removePowerSaveWhitelistApp(BuildConfig.APPLICATION_ID);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to remove power save whitelist", e);
        }
    }

    @Override
    public void run() {
        TrafficStats.setThreadStatsTag(42);
        try (selector) {
            var keys = selector.keys();
            var selectedKeys = selector.selectedKeys();
            while (!isInterrupted()) {
                try {
                    for (var i = keys.size() - cancelledKeys; i < concurrent; i++) {
                        var slot = openChannel();
                        slot.setTask(tasks.poll());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to open channel", e);
                } finally {
                    cancelledKeys = 0;
                }
                selector.select();
                var now = System.nanoTime();
                processKeys(keys, now);
                selectedKeys.clear();
            }
            var exception = new ClosedByInterruptException();
            for (var key : keys) {
                var slot = (ChannelSlot) key.attachment();
                slot.close(exception);
            }
            for (var task : tasks) {
                task.paddingException = exception;
                task.latch.countDown();
            }
        } catch (IOException e) {
            Log.e(TAG, "Selector error", e);
        }
    }

    private void processKeys(Set<SelectionKey> keys, long nanoTime) {
        for (var key : keys) {
            if (!key.isValid()) continue;
            var slot = (ChannelSlot) key.attachment();
            if (slot.task != null && slot.task.canceled) {
                if (slot.state.ordinal() > HttpState.READY_TO_REQUEST.ordinal()) {
                    slot.close(null);
                    continue;
                } else {
                    slot.task.latch.countDown();
                    slot.task = null;
                }
            }
            var readyOps = key.readyOps() & key.interestOps();
            try {
                if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                    slot.lastActiveTime = nanoTime;
                    slot.handleConnect(key);
                } else if ((readyOps & SelectionKey.OP_READ) != 0) {
                    if (slot.handleRead(key)) {
                        slot.lastActiveTime = nanoTime;
                    }
                } else if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                    slot.lastActiveTime = nanoTime;
                    slot.handleWrite(key);
                } else if (key.interestOps() == 0) {
                    if (slot.setTask(tasks.poll())) {
                        key.interestOps(SelectionKey.OP_WRITE);
                    }
                    continue;
                }
                if (nanoTime - slot.lastActiveTime > IDLE_TIMEOUT) {
                    var address = slot.socketChannel.socket().getInetAddress();
                    ipPool.delete(address);
                    slot.close(new IOException("SocketChannel timeout"));
                }
            } catch (IOException e) {
                slot.close(e);
            }
        }
    }


    private enum HttpState {
        HANDSHAKING,
        READY_TO_REQUEST,
        SENDING_REQUEST,
        READING_HEADER,
        READING_BODY,
        CLOSED,
    }

    private class ChannelSlot {
        final SocketChannel socketChannel;
        final SSLEngine engine;
        final SSLEngineResult emptyResult;
        final ByteBuffer headerAccumulator = ByteBuffer.allocate(8 * 1024);

        ByteBuffer myAppData;
        ByteBuffer myNetData;
        ByteBuffer peerNetData;
        ByteBuffer peerAppData;

        HttpState state;
        Task task;
        long taskStartTime;
        long lastActiveTime = System.nanoTime();

        ChannelSlot(SocketChannel socketChannel, SSLEngine engine) {
            this.socketChannel = socketChannel;
            this.engine = engine;

            if (engine == null) {
                state = HttpState.READY_TO_REQUEST;
                var appBufferSize = 16 * 1024;
                myAppData = ByteBuffer.allocateDirect(appBufferSize);
                peerAppData = ByteBuffer.allocateDirect(appBufferSize);
                myNetData = myAppData.duplicate();
                peerNetData = peerAppData.duplicate();
                emptyResult = new SSLEngineResult(SSLEngineResult.Status.OK,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0);
            } else {
                state = HttpState.HANDSHAKING;
                var session = engine.getSession();
                var appBufferSize = session.getApplicationBufferSize();
                var netBufferSize = session.getPacketBufferSize();
                myAppData = ByteBuffer.allocateDirect(appBufferSize);
                peerAppData = ByteBuffer.allocateDirect(appBufferSize);
                myNetData = ByteBuffer.allocateDirect(netBufferSize);
                peerNetData = ByteBuffer.allocateDirect(netBufferSize);
                emptyResult = null;
            }
        }

        private SSLEngineResult unwrap(ByteBuffer src, ByteBuffer dst) throws SSLException {
            if (engine != null) return engine.unwrap(src, dst);
            var remaining = src.remaining();
            src.position(src.position() + remaining);
            dst.position(dst.position() + remaining);
            return emptyResult;
        }

        private void wrap(ByteBuffer src, ByteBuffer dst) throws SSLException {
            if (engine == null) {
                var remaining = src.remaining();
                src.position(src.position() + remaining);
                dst.position(dst.position() + remaining);
            } else {
                var result = engine.wrap(src, dst);
                if (result.getStatus() != SSLEngineResult.Status.OK) {
                    throw new SSLException("SSL wrap failed: " + result.getStatus());
                }
            }
        }

        boolean setTask(Task task) {
            if (task == null) return false;
            this.task = task;
            taskStartTime = System.nanoTime();
            return true;
        }

        void handleConnect(SelectionKey key) throws IOException {
            if (socketChannel.finishConnect()) {
                key.interestOps(SelectionKey.OP_WRITE);
                evaluateHandshakeStatus(key);
            }
        }

        boolean handleRead(SelectionKey key) throws IOException {
            int bytesRead = socketChannel.read(peerNetData);
            if (bytesRead == 0) {
                return false;
            } else if (bytesRead == -1) {
                close(new IOException("SocketChannel eof"));
                return false;
            }

            peerNetData.flip();
            while (peerNetData.hasRemaining()) {
                var result = unwrap(peerNetData, peerAppData);
                switch (result.getStatus()) {
                    case OK -> {
                        peerAppData.flip();
                        if (state == HttpState.READING_HEADER && peerAppData.hasRemaining()) {
                            readHeader();
                        }
                        if (state == HttpState.READING_BODY && peerAppData.hasRemaining()) {
                            writeToBuffer();
                        }
                        if (state == HttpState.READY_TO_REQUEST) {
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                        peerAppData.clear();
                    }
                    case BUFFER_OVERFLOW -> {
                        int size = engine.getSession().getApplicationBufferSize();
                        var newBuffer = ByteBuffer.allocateDirect(peerAppData.capacity() + size);
                        peerAppData.flip();
                        newBuffer.put(peerAppData);
                        peerAppData = newBuffer;
                        continue;
                    }
                    case BUFFER_UNDERFLOW -> {
                        peerNetData.compact();
                        return true;
                    }
                    case CLOSED -> {
                        close(null);
                        return true;
                    }
                }
                evaluateHandshakeStatus(key);
            }
            peerNetData.clear();
            return true;
        }

        void handleWrite(SelectionKey key) throws IOException {
            if (state == HttpState.READY_TO_REQUEST) {
                if (task == null && !setTask(tasks.poll()) && !stealTask()) {
                    key.interestOps(0);
                    return;
                }
                wrapHttpRequest();
                state = HttpState.SENDING_REQUEST;
            }

            if (myNetData.position() > 0) {
                myNetData.flip();
                socketChannel.write(myNetData);
                myNetData.compact();
                if (myNetData.position() > 0) {
                    return;
                }
            }

            if (state == HttpState.SENDING_REQUEST) {
                state = HttpState.READING_HEADER;
                key.interestOps(SelectionKey.OP_READ);
            }

            evaluateHandshakeStatus(key);
        }

        private void evaluateHandshakeStatus(SelectionKey key) throws IOException {
            if (state != HttpState.HANDSHAKING) return;

            while (true) {
                var status = engine.getHandshakeStatus();
                switch (status) {
                    case NOT_HANDSHAKING -> {
                        state = HttpState.READY_TO_REQUEST;
                        key.interestOps(SelectionKey.OP_WRITE);
                        return;
                    }
                    case NEED_TASK -> {
                        Runnable delegateTask;
                        while ((delegateTask = engine.getDelegatedTask()) != null) {
                            delegateTask.run();
                        }
                    }
                    case NEED_WRAP -> {
                        myAppData.clear();
                        myAppData.flip();
                        wrap(myAppData, myNetData);
                        key.interestOps(SelectionKey.OP_WRITE);
                        return;
                    }
                    case NEED_UNWRAP -> {
                        key.interestOps(SelectionKey.OP_READ);
                        return;
                    }
                }
            }
        }

        private boolean stealTask() {
            if (taskStartTime == 0) return false;
            ChannelSlot victim = null;
            long min = taskStartTime;

            for (var key : selector.keys()) {
                if (!key.isValid()) continue;
                var slot = (ChannelSlot) key.attachment();
                if (slot.task == null) continue;
                if (slot.taskStartTime < min) {
                    min = slot.taskStartTime;
                    victim = slot;
                }
            }

            if (victim == null) return false;
            if (victim.task.buffer.remaining() <= 64 * 1024) return false;

            setTask(victim.task);
            victim.task = null;
            victim.close(null);
            return true;
        }

        private void wrapHttpRequest() throws SSLException {
            var chunkLength = task.buffer.limit();
            var offset = task.offset;
            var start = String.valueOf(offset + task.buffer.position());
            var end = offset + chunkLength - 1;
            if (offset == -1) {
                start = "";
                end = chunkLength;
            } else if (offset == -2) {
                start = "0";
                end = 0;
            }
            String request = "GET " + getTarget() + " HTTP/1.1\r\n" +
                "Host: " + uri.getHost() + "\r\n" +
                "Range: bytes=" + start + "-" + end + "\r\n" +
                "User-Agent: PackageInstaller\r\n" +
                "Accept: */*\r\n\r\n";

            myAppData.clear();
            myAppData.put(request.getBytes(StandardCharsets.UTF_8));
            myAppData.flip();

            wrap(myAppData, myNetData);
        }

        private void readHeader() throws IOException {
            int scanStart = Math.max(0, headerAccumulator.position() - 3);

            if (peerAppData.remaining() > headerAccumulator.remaining()) {
                var oldLimit = peerAppData.limit();
                peerAppData.limit(peerAppData.position() + headerAccumulator.remaining());
                headerAccumulator.put(peerAppData);
                peerAppData.limit(oldLimit);
            } else {
                headerAccumulator.put(peerAppData);
            }

            headerAccumulator.flip();
            var rawData = headerAccumulator.array();
            int limit = headerAccumulator.limit();
            int headerSize = -1;

            for (int i = scanStart; i <= limit - 4; i++) {
                if (rawData[i] == '\r' && rawData[i + 1] == '\n' &&
                    rawData[i + 2] == '\r' && rawData[i + 3] == '\n') {
                    headerSize = i + 4;
                    break;
                }
            }

            if (headerSize != -1) {
                headerAccumulator.limit(headerSize);
                state = parseHeader(headerAccumulator);

                int remainingBodyBytes = limit - headerSize;
                if (remainingBodyBytes > 0) {
                    peerAppData.position(peerAppData.position() - remainingBodyBytes);
                }
                headerAccumulator.clear();
            } else {
                var capacity = headerAccumulator.capacity();
                if (limit == capacity) throw new IOException("HTTP header too large");
                headerAccumulator.position(limit);
                headerAccumulator.limit(capacity);
            }
        }

        private void writeToBuffer() {
            if (task == null) {
                state = HttpState.READY_TO_REQUEST;
                return;
            }
            task.buffer.put(peerAppData);
            if (!task.buffer.hasRemaining()) {
                task.latch.countDown();
                task = null;
                state = HttpState.READY_TO_REQUEST;
            }
        }

        private HttpState parseHeader(ByteBuffer buffer) throws IOException {
            var rawData = buffer.array();
            int spaceIndex = -1;
            for (int i = 0, limit = buffer.limit(); i < limit; i++) {
                if (rawData[i] == ' ') {
                    spaceIndex = i;
                    break;
                }
            }
            var code = new String(rawData, spaceIndex + 1, 3, StandardCharsets.UTF_8);
            int statusCode = Integer.parseInt(code);

            if (fileSize != -1) {
                if (statusCode != 206) {
                    throw new ConnectException("Server did not return 206: " + code);
                }
                return HttpState.READING_BODY;
            } else if (statusCode == 501) {
                task.offset = -2;
                return HttpState.READY_TO_REQUEST;
            } else if (statusCode >= 400) {
                throw new IOException("Server returned error: " + code);
            }

            var skipRead = task.offset == -2;
            String disposition = null;
            var header = new String(rawData, 0, buffer.limit(), StandardCharsets.UTF_8);
            for (var line : header.split("\r\n")) {
                var s = line.toLowerCase(Locale.ROOT).trim();
                if (s.startsWith("location:")) {
                    var location = line.substring("location:".length()).trim();
                    var oldUri = uri;
                    var oldPort = getPort();
                    uri = oldUri.resolve(location);
                    if (uri.getHost().equals(oldUri.getHost()) && getPort() == oldPort) {
                        return HttpState.READY_TO_REQUEST;
                    } else {
                        ipPool = new IpPool(uri.getHost());
                        close(null);
                        return HttpState.CLOSED;
                    }
                } else if (s.startsWith("content-range:")) {
                    var slashIndex = line.indexOf('/');
                    var sizeStr = line.substring(slashIndex + 1).trim();
                    fileSize = Long.parseLong(sizeStr);
                    concurrent = MAX_CHANNEL_COUNT;
                    if (fileSize < task.buffer.limit()) {
                        task.buffer.limit((int) fileSize);
                    }
                    task.offset = fileSize - task.buffer.limit();
                } else if (s.startsWith("content-disposition:")) {
                    disposition = line.substring("content-disposition:".length()).trim();
                }
            }

            if (statusCode == 206) {
                var guessFileName = URLUtil.guessFileName(uri.toString(), disposition, null);
                // noinspection deprecation
                fileName = URLDecoder.decode(guessFileName);
                return skipRead ? HttpState.READY_TO_REQUEST : HttpState.READING_BODY;
            } else {
                throw new IOException("Only range requests are supported: " + code);
            }
        }

        void close(IOException e) {
            try {
                socketChannel.close();
                cancelledKeys++;
            } catch (IOException ex) {
                Log.e(TAG, "Failed to close channel", ex);
            }
            if (e instanceof ConnectException) {
                concurrent = Math.max(1, concurrent - 1);
            }

            if (task == null) {
                return;
            }
            if (task.canceled) {
                task.latch.countDown();
                return;
            }
            if (fileSize == -1 && e != null) {
                task.paddingException = e;
                task.latch.countDown();
                concurrent = 0;
                return;
            }
            if (e instanceof ClosedByInterruptException) {
                task.retryCount += MAX_RETRY_COUNT;
            }
            if (task.retryCount >= MAX_RETRY_COUNT && e != null) {
                task.paddingException = e;
                task.latch.countDown();
                return;
            }

            task.retryCount++;
            tasks.offerFirst(task);
        }
    }

    private static class IpPool {
        final ArraySet<InetAddress> set;
        final String host;
        int count = 0;

        IpPool(String host) throws IOException {
            this.set = new ArraySet<>(InetAddress.getAllByName(host));
            this.host = host;
        }

        void delete(InetAddress address) {
            set.remove(address);
            try {
                Collections.addAll(set, InetAddress.getAllByName(host));
            } catch (UnknownHostException _) {
            }
        }

        InetAddress get() {
            return set.valueAt(count++ % set.size());
        }
    }
}

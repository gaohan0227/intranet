package priv.bigant.intrance.common;

import priv.bigant.intrance.common.log.LogUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerConnector implements Connector {

    private String name;
    private Process process;
    private int port;
    private ServerSocketChannel server;
    private ConnectorThread connectorThread;
    private Config config;

    public ServerConnector(String name, Process process, int port, Config config) {
        this.name = name;
        this.process = process;
        this.port = port;
        this.config = config;
    }

    @Override
    public String getName() {
        return name;
    }


    public void start() {
        try {
            connect();
            connectorThread = new ConnectorThread(process, getName() + "-thread", config);
            connectorThread.register(server, SelectionKey.OP_ACCEPT);
            connectorThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void connect() throws IOException {
        this.server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress(port));
    }


    public void showdown() {
        try {
            if (server != null) server.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            connectorThread.showdown();
        }

    }

    /**
     * nio process 监控线程
     */
    public static class ConnectorThread extends Thread implements Connector {
        private static final Logger LOG = LogUtil.getLog();
        private Selector selector;
        private Process process;
        private Boolean stopStatus = false;

        public ConnectorThread(Process process, String name, Config config) throws IOException {
            super(name);
            this.process = process;
            this.selector = Selector.open();
        }

        public void register(SelectableChannel selectableChannel, int ops, Object attn) throws ClosedChannelException {
            selectableChannel.register(selector, ops, attn);
        }

        public void register(SelectableChannel selectableChannel, int ops) throws ClosedChannelException {
            selectableChannel.register(selector, ops);
        }

        public void showdown() {
            try {
                selector.close();
                process.showdown();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                stopStatus = true;
            }
        }

        private boolean isShowDown() {
            return stopStatus;
        }

        @Override
        public void run() {
            while (!isShowDown()) {
                int i;
                try {
                    i = selector.selectNow();
                } catch (IOException e) {
                    i = 0;
                    LOG.severe(process.getName() + " process 监控线程 select error" + e);
                    e.printStackTrace();
                } catch (ClosedSelectorException e) {
                    continue;
                }

                if (i < 1)
                    continue;
                Iterator<SelectionKey> selectionKeys = selector.selectedKeys().iterator();
                while (selectionKeys.hasNext() && !isShowDown()) {

                    SelectionKey selectionKey = selectionKeys.next();
                    selectionKeys.remove();

                    try {
                        if (selectionKey.isAcceptable()) {
                            LOG.finer(getName() + " accept");
                            process.accept(this, selectionKey);
                        } else if (selectionKey.isReadable()) {
                            LOG.finer(getName() + " read");
                            process.read(this, selectionKey);
                        }
                    } catch (ClosedSelectorException | CancelledKeyException e) {
                        LOG.log(Level.FINE, process.getName() + " 处理器处理事件失败 ", e);
                        selectionKey.cancel();
                    } catch (Exception e) {
                        LOG.log(Level.SEVERE, process.getName() + " 处理器处理事件失败 ", e);
                        selectionKey.cancel();
                    }

                }
            }
        }

    }
}


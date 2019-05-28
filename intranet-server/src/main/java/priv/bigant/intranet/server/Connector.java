package priv.bigant.intranet.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import priv.bigant.intrance.common.LifecycleException;
import priv.bigant.intrance.common.LifecycleMBeanBase;
import priv.bigant.intrance.common.LifecycleState;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;

public class Connector extends LifecycleMBeanBase implements BigAnt {

    private String name;
    private Process process;
    private int port;
    private ServerSocketChannel server;

    public Connector(String name, Process process, int port) {
        this.name = name;
        this.process = process;
        this.port = port;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    protected String getDomainInternal() {
        return null;
    }

    @Override
    protected String getObjectNameKeyProperties() {
        return "type=" + name;
    }

    @Override
    protected void startInternal() throws LifecycleException {
        setState(LifecycleState.STARTING);
        try {
            build();
            ConnectorThread connectorThread = new ConnectorThread();
            connectorThread.register(server, SelectionKey.OP_ACCEPT);
            connectorThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void build() throws IOException {
        this.server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress(port));
    }

    @Override
    protected void stopInternal() throws LifecycleException {

    }

    class ConnectorThread extends Thread {
        private final Logger LOG = LoggerFactory.getLogger(ConnectorThread.class);
        private Selector selector;

        public ConnectorThread() throws IOException {
            this.selector = Selector.open();
        }

        public void register(SelectableChannel selectableChannel, int ops, Object attn) throws ClosedChannelException {
            selectableChannel.register(selector, ops, attn);
        }

        public void register(SelectableChannel selectableChannel, int ops) throws ClosedChannelException {
            selectableChannel.register(selector, ops);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    if (selector.select() < 1)
                        continue;
                    Iterator<SelectionKey> selectionKeys = selector.selectedKeys().iterator();
                    while (selectionKeys.hasNext()) {

                        SelectionKey selectionKey = selectionKeys.next();
                        selectionKeys.remove();

                        if (selectionKey.isAcceptable()) {
                            SocketChannel socketChannel = server.accept();
                            //socketChannel.configureBlocking(false);
                            process.accept(this, socketChannel);
                        } else if (selectionKey.isReadable()) {
                            SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
                            process.read(this, socketChannel);
                        }
                    }

                } catch (IOException e) {
                    LOG.error(getName() + "select error", e);
                }
            }
        }
    }
}

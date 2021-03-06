package priv.bigant.intranet.server.process;

import priv.bigant.intrance.common.ProcessBase;
import priv.bigant.intrance.common.ServerConnector.ConnectorThread;
import priv.bigant.intranet.server.ServerConfig;
import priv.bigant.intranet.server.communication.ServerCommunication;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * 用于与客户端交互处理器
 */
public class CommunicationProcessor extends ProcessBase {

    private ThreadPoolExecutor executor;
    private ServerConfig serverConfig;

    public CommunicationProcessor(ServerConfig config) {
        this.serverConfig = config;
        this.executor = new ThreadPoolExecutor(serverConfig.getCorePoolSize(), serverConfig.getMaximumPoolSize(), serverConfig.getKeepAliveTime(), TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    }

    @Override
    public String getName() {
        return "CommunicationProcess";
    }

    @Override
    public void showdown() {

    }

    @Override
    public void read(ConnectorThread connectorThread, SelectionKey selectionKey) throws IOException {
        ServerCommunication serverCommunication = (ServerCommunication) selectionKey.attachment();
        serverCommunication.disposeRequests();
    }

    @Override
    public void accept(ConnectorThread connectorThread, SelectionKey selectionKey) throws IOException {
        SocketChannel socketChannel = ((ServerSocketChannel) selectionKey.channel()).accept();
        socketChannel.configureBlocking(false);
        connectorThread.register(socketChannel, SelectionKey.OP_READ, new ServerCommunication(socketChannel, serverConfig));
    }


}

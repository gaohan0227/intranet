package priv.bigant.intranet.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import priv.bigant.intrance.common.Config;
import priv.bigant.intrance.common.Connector;
import priv.bigant.intrance.common.ProcessBase;
import priv.bigant.intrance.common.ServerConnector.ConnectorThread;
import priv.bigant.intrance.common.communication.Communication;
import priv.bigant.intrance.common.communication.CommunicationDispose;
import priv.bigant.intrance.common.communication.CommunicationRequest;
import priv.bigant.intrance.common.communication.CommunicationRequest.CommunicationRequestHttpReturn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class CommunicationProcessor extends ProcessBase {


    private static final Logger LOG = LoggerFactory.getLogger(CommunicationProcessor.class);
    private Communication clientCommunication;
    private Connector connector;

    public CommunicationProcessor(Communication clientCommunication, ConnectorThread serviceConnector) {
        this.clientCommunication = clientCommunication;
        clientCommunication.setCommunicationDispose(new ClientCommunicationDispose(serviceConnector, this));
    }

    public CommunicationProcessor(Communication clientCommunication) {
        this.clientCommunication = clientCommunication;
    }

    public void showdown() {
        connector.showdown();
    }

    public Connector getConnector() {
        return connector;
    }

    public void setConnector(Connector connector) {
        this.connector = connector;
    }

    @Override
    public void read(ConnectorThread connectorThread, SelectionKey selectionKey) throws IOException {
        clientCommunication.disposeRequests();
    }


    @Override
    public void accept(ConnectorThread connectorThread, SelectionKey selectionKey) {
        //不可能有的
    }

    @Override
    public String getName() {
        return "client communication process";
    }


    /**
     * 统一处理请求
     */
    public static class ClientCommunicationDispose extends CommunicationDispose {
        private ClientConfig clientConfig;
        private static final Logger LOG = LoggerFactory.getLogger(ClientCommunicationDispose.class);
        private CommunicationProcessor communicationProcessor;
        private ConnectorThread serviceConnector;

        public ClientCommunicationDispose(ConnectorThread serviceConnector, CommunicationProcessor communicationProcessor) {
            this.serviceConnector = serviceConnector;
            this.communicationProcessor = communicationProcessor;
            clientConfig = (ClientConfig) Config.getConfig();
        }

        public ClientCommunicationDispose(ConnectorThread serviceConnector) {
            this.serviceConnector = serviceConnector;
            clientConfig = (ClientConfig) Config.getConfig();
        }

        @Override
        protected void httpReturn(CommunicationRequest communicationRequest, Communication communication) {
            try {
                CommunicationRequestHttpReturn communicationRequestHttpReturn = communicationRequest.toJavaObject(CommunicationRequestHttpReturn.class);
                switch (communicationRequestHttpReturn.getStatus()) {
                    case SUCCESS:
                        LOG.info("连接成功");
                        break;
                    case DOMAIN_OCCUPIED:
                        communicationProcessor.showdown();
                        LOG.error("域名已被占用");
                        break;
                }
            } catch (Exception e) {
                communicationProcessor.showdown();
                LOG.error("连接失败", e);
            }
        }

        @Override
        protected void test(CommunicationRequest communicationRequest, Communication communication) {
            //测试数据先不管
        }

        @Override
        public void http(CommunicationRequest communicationRequest, Communication communication) {
            //客户端收不到此类型数据
        }

        @Override
        public void httpAdd(CommunicationRequest communicationRequest, Communication communication) {
            SocketChannel socketChannel;
            CommunicationRequest.CommunicationRequestHttpAdd communicationRequestHttpAdd = communicationRequest.toJavaObject(CommunicationRequest.CommunicationRequestHttpAdd.class);
            try {
                socketChannel = SocketChannel.open(new InetSocketAddress(clientConfig.getHostName(), clientConfig.getHttpAcceptPort()));
                socketChannel.socket().setKeepAlive(true);

                Communication.writeN(CommunicationRequest.createCommunicationRequest(communicationRequestHttpAdd), socketChannel);

                socketChannel.configureBlocking(false);
                serviceConnector.register(socketChannel, SelectionKey.OP_READ);
            } catch (Exception e) {
                LOG.error("add http socket error", e);
            }
        }
    }
}
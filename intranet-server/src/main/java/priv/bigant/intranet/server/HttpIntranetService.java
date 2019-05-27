package priv.bigant.intranet.server;

import java.util.HashMap;
import java.util.Map;

public class HttpIntranetService extends LifecycleMBeanBase implements Service {

    private static final String NAME = "HttpIntranetService";
    private Server server;
    private Map<String, Connector> connectors = new HashMap();


    @Override
    public Server getServer() {
        return server;
    }

    @Override
    public void setServer(Server server) {
        this.server = server;
    }

    @Override
    public void addConnector(Connector connector) {
        connectors.put(connector.getName(), connector);
    }

    @Override
    public void removeConnector(Connector connector) {

    }

    @Override
    protected void startInternal() throws LifecycleException {
        connectors.values().forEach(connector -> {
            try {
                connector.start();
            } catch (LifecycleException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    protected void stopInternal() throws LifecycleException {

    }

    @Override
    protected String getDomainInternal() {
        return null;
    }

    @Override
    protected String getObjectNameKeyProperties() {
        return "type=HttpIntranetService";
    }

    @Override
    public String getName() {
        return NAME;
    }
}
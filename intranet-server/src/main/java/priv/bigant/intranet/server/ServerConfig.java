package priv.bigant.intranet.server;

import priv.bigant.intrance.common.Config;

public class ServerConfig extends Config {
    private int socketTimeOut = 5000;
    private int httpPort = 8888;

    private int corePoolSize = 5;
    private int maximumPoolSize = 30;
    private int keepAliveTime = 1000;
    private int waitSocketTime = 20000;


    public int getWaitSocketTime() {
        return waitSocketTime;
    }

    public void setWaitSocketTime(int waitSocketTime) {
        this.waitSocketTime = waitSocketTime;
    }

    private ServerConfig() {

    }

    public static Config getConfig() {
        if (config == null) {
            synchronized (Config.class) {
                if (config == null) {
                    config = new ServerConfig();
                }
            }
        }
        return config;
    }


    public int getSocketTimeOut() {
        return socketTimeOut;
    }

    public void setSocketTimeOut(int socketTimeOut) {
        this.socketTimeOut = socketTimeOut;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public int getKeepAliveTime() {
        return keepAliveTime;
    }

    public void setKeepAliveTime(int keepAliveTime) {
        this.keepAliveTime = keepAliveTime;
    }


}

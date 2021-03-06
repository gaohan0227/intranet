package priv.bigant.intranet.client;

import priv.bigant.intrance.common.HttpIntranetServiceProcessAbs;
import priv.bigant.intrance.common.coyote.http11.Http11Processor;

/**
 * 客户端 http Nio 处理中心
 */
public class HttpProcessor extends HttpIntranetServiceProcessAbs {

    public HttpProcessor() {
        super();
    }

    @Override
    public Http11Processor createHttp11Processor() {
        return new Http11ProcessorServer(8 * 1024, null, null);
    }

    @Override
    public String getName() {
        return "client http process";
    }
}

package priv.bigant.intranet.client;

import priv.bigant.intrance.common.HttpIntranetServiceProcessAbs;
import priv.bigant.intrance.common.coyote.http11.Http11Processor;

import java.util.HashMap;

public class HttpIntranetServiceProcess extends HttpIntranetServiceProcessAbs {

    @Override
    public Http11Processor createHttp11Processor() {
        return new Http11ProcessorServer(8 * 1024, true, false, new HashMap<>(), true, null, null);
    }

    @Override
    public String getName() {
        return null;
    }
}
package priv.bigant.intrance.common.http;


import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * Created by GaoHan on 2018/5/22.
 * <p>
 * socket连接的Bean
 */
public class SocketBeanss {
    // 和本线程相关的Socket
    private Socket socket;
    //private BufferedReader br = null;
    //private PrintWriter pw = null;
    private InputStream is;
    private OutputStream os;
    private InetAddress inetAddress;
    private String domainName;

    public SocketBeanss(Socket socket) throws IOException {
        this.socket = socket;
        this.inetAddress = socket.getInetAddress();
        is = socket.getInputStream();
        os = socket.getOutputStream();
        //this.br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        //this.pw = new PrintWriter(socket.getOutputStream());
    }


    public void close() throws IOException {
        /*if (br != null)
            br.close();

        if (pw != null)

            pw.close();*/


        if (os != null)
            os.close();

        if (is != null)
            is.close();


        if (socket != null) {
            System.out.print("");
            socket.close();
//            socket.shutdownOutput();
//            socket.shutdownInput();
        }
    }

    public Socket getSocket() {
        return socket;
    }

    public void setSocket(Socket socket) {
        this.socket = socket;
    }

    public InputStream getIs() {
        return is;
    }

    public void setIs(InputStream is) {
        this.is = is;
    }

    public OutputStream getOs() {
        return os;
    }

    public void setOs(OutputStream os) {
        this.os = os;
    }

    public InetAddress getInetAddress() {
        return inetAddress;
    }

    public void setInetAddress(InetAddress inetAddress) {
        this.inetAddress = inetAddress;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }
}

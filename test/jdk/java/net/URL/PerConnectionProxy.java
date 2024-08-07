/*
 * Copyright (c) 2003, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/* @test
 * @bug 4920526
 * @summary Needs per connection proxy support for URLs
 * @library /test/lib
 * @compile PerConnectionProxy.java
 * @run main/othervm -Dhttp.proxyHost=inexistant -Dhttp.proxyPort=8080 PerConnectionProxy
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jdk.test.lib.net.URIBuilder;

public class PerConnectionProxy {
    static HttpServer server;

    public static void main(String[] args) {
        try {
            InetAddress loopbackAddress = InetAddress.getLoopbackAddress();
            server = HttpServer.create(new InetSocketAddress(loopbackAddress, 0), 10);
            server.createContext("/", new PerConnectionProxyHandler());
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            ProxyServer pserver = new ProxyServer(loopbackAddress, server.getAddress().getPort());
            // start proxy server
            new Thread(pserver).start();

            URL url = URIBuilder.newBuilder()
                    .scheme("http")
                    .loopback()
                    .port(server.getAddress().getPort())
                    .toURLUnchecked();

            // for non existing proxy expect an IOException
            try {
                InetSocketAddress isa = InetSocketAddress.createUnresolved("inexistent", 8080);
                Proxy proxy = new Proxy(Proxy.Type.HTTP, isa);
                HttpURLConnection urlc = (HttpURLConnection)url.openConnection (proxy);
                InputStream is = urlc.getInputStream ();
                is.close();
                throw new RuntimeException("non existing per connection proxy should lead to IOException");
            } catch (IOException ioex) {
                // expected
            }
            // for NO_PROXY, expect direct connection
            try {
                HttpURLConnection urlc = (HttpURLConnection)url.openConnection (Proxy.NO_PROXY);
                int respCode = urlc.getResponseCode();
                urlc.disconnect();
            } catch (IOException ioex) {
                throw new RuntimeException("direct connection should succeed :"+ioex.getMessage());
            }
            // for a normal proxy setting expect to see connection
            // goes through that proxy
            try {
                InetSocketAddress isa = InetSocketAddress.createUnresolved(
                        loopbackAddress.getHostAddress(),
                        pserver.getPort());
                Proxy p = new Proxy(Proxy.Type.HTTP, isa);
                HttpURLConnection urlc = (HttpURLConnection)url.openConnection (p);
                int respCode = urlc.getResponseCode();
                urlc.disconnect();
            } catch (IOException ioex) {
                throw new RuntimeException("connection through a local proxy should succeed :"+ioex.getMessage());
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (server != null) {
                server.stop(1);
            }
        }
    }

    static class ProxyServer extends Thread {
        private static ServerSocket ss = null;

        // client requesting for a tunnel
        private Socket clientSocket = null;

        /*
         * Origin server's address and port that the client
         * wants to establish the tunnel for communication.
         */
        private InetAddress serverInetAddr;
        private int     serverPort;

        public ProxyServer(InetAddress server, int port) throws IOException {
            serverInetAddr = server;
            serverPort = port;
            ss = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        }

        public void run() {
            try {
                clientSocket = ss.accept();
                processRequests();
            } catch (Exception e) {
                System.out.println("Proxy Failed: " + e);
                e.printStackTrace();
                try {
                    ss.close();
                }
                catch (IOException excep) {
                    System.out.println("ProxyServer close error: " + excep);
                    excep.printStackTrace();
                }
            }
        }

        private void processRequests() throws Exception {
            // connection set to the tunneling mode
            Socket serverSocket = new Socket(serverInetAddr, serverPort);
            ProxyTunnel clientToServer = new ProxyTunnel(
                                                         clientSocket, serverSocket);
            ProxyTunnel serverToClient = new ProxyTunnel(
                                                         serverSocket, clientSocket);
            clientToServer.start();
            serverToClient.start();
            System.out.println("Proxy: Started tunneling.......");

            clientToServer.join();
            serverToClient.join();
            System.out.println("Proxy: Finished tunneling........");

            clientToServer.close();
            serverToClient.close();
        }

        /**
***************************************************************
*                       helper methods follow
***************************************************************
*/
        public int getPort() {
            return ss.getLocalPort();
        }
        /*
         * This inner class provides unidirectional data flow through the sockets
         * by continuously copying bytes from the input socket onto the output
         * socket, until both sockets are open and EOF has not been received.
         */
        static class ProxyTunnel extends Thread {
            Socket sockIn;
            Socket sockOut;
            InputStream input;
            OutputStream output;

            public ProxyTunnel(Socket sockIn, Socket sockOut)
                throws Exception {
                this.sockIn = sockIn;
                this.sockOut = sockOut;
                input = sockIn.getInputStream();
                output = sockOut.getOutputStream();
            }

            public void run() {
                int BUFFER_SIZE = 400;
                byte[] buf = new byte[BUFFER_SIZE];
                int bytesRead = 0;
                int count = 0;  // keep track of the amount of data transfer

                try {
                    while ((bytesRead = input.read(buf)) >= 0) {
                        output.write(buf, 0, bytesRead);
                        output.flush();
                        count += bytesRead;
                    }
                } catch (IOException e) {
                    /*
                     * The peer end has closed the connection
                     * we will close the tunnel
                     */
                    close();
                }
            }

            public void close() {
                try {
                    if (!sockIn.isClosed())
                        sockIn.close();
                    if (!sockOut.isClosed())
                        sockOut.close();
                } catch (IOException ignored) { }
            }
        }
    }
}

class PerConnectionProxyHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.sendResponseHeaders(200, 0);
        } catch (IOException e) {
        }
        try(PrintWriter pw = new PrintWriter(exchange.getResponseBody(), false, Charset.forName("UTF-8"))) {
            pw.print("Hello .");
        }
    }
}


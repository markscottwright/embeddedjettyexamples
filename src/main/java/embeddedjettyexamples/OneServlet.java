package embeddedjettyexamples;

import java.io.IOException;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class OneServlet {
    public static void main(String[] args) throws Exception {
        var server = new Server();
        var serverConnector = new ServerConnector(server);
        serverConnector.setPort(9000);
        server.addConnector(serverConnector);

        var servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/");
        var servletHolder = new ServletHolder(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException {
                resp.getWriter().write("Hello world");
            }
        });
        servletContextHandler.addServlet(servletHolder, "/hello");
        server.setHandler(servletContextHandler);
        
        server.start();
        server.join();
    }

}

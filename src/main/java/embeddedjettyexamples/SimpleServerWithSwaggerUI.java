package embeddedjettyexamples;

import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static java.util.stream.Collectors.toSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.wadl.WadlFeature;
import org.glassfish.jersey.servlet.ServletContainer;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;

public class SimpleServerWithSwaggerUI extends Application {

    public static class Greeting {
        public String greeting;
        public Integer repeat;
    }

    public static class Database {
        AtomicReference<String> currentGreeting = new AtomicReference<String>("Hola " + LocalDateTime.now());
    }

    @Path("/hello")
    static public class SimpleResource {
        private Database database;

        public SimpleResource(Database database) {
            this.database = database;
        }

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String getAGreeting() {
            return database.currentGreeting.get();
        }

        @POST
        @Produces(MediaType.TEXT_PLAIN)
        @Consumes(MediaType.APPLICATION_JSON)
        public String setTheGreeting(Greeting greeting) {
            database.currentGreeting.set(greeting.greeting + " " + greeting.repeat + " times");
            return database.currentGreeting.get();
        }
    }

    @Override
    public Set<Object> getSingletons() {
        // this, unfortunately, presents a warning. The warning seems incorrect - the
        // only way around it is to use a DI framework.
        // It can be disabled with one of these:
        // log4j.logger.org.glassfish.jersey.internal=OFF
        // log4j.logger.org.glassfish.jersey.internal.inject.Providers=ERROR
        return Set.of(new SimpleResource(new Database()));
    }

    public static void main(String[] args) throws Exception {

        // Disable uninteresting warning. keep a reference to this logger, or it gets
        // gc'ed and the config change is lost
        Logger wadlLogger = Logger.getLogger(WadlFeature.class.getName());
        wadlLogger.setLevel(Level.SEVERE);

        // doesn't happen here, though
        Logger.getLogger("org.glassfish.jersey.internal").setLevel(Level.SEVERE);

        // base web server support
        var server = new Server();
        var serverConnector = new ServerConnector(server);
        serverConnector.setPort(9000);
        server.addConnector(serverConnector);
        var servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/");
        server.setHandler(servletContextHandler);

        // add rest api endpoint
        var application = ResourceConfig.forApplication(new SimpleServerWithSwaggerUI());
        var servletHolder = new ServletHolder(new ServletContainer(application));
        servletContextHandler.addServlet(servletHolder, "/api/*");

        // add swagger definition servlet
        Reader reader = new Reader(new SwaggerConfiguration()) {
            @Override
            protected String resolveApplicationPath() {
                return "api";
            }
        };
        var openApi = reader.read(application.getSingletons().stream().map(Object::getClass).collect(toSet()));
        var openApiJson = Json.pretty(openApi);
        servletContextHandler.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws ServletException, IOException {
                resp.addHeader("Content-Type", "application/json");
                resp.getWriter().write(openApiJson);
            }
        }), "/swagger.json");

        // add swagger-ui servlet
        // (this is incomplete - we should set media-types, cache ttls and compression.
        // Also, we should safety check req.getRequestURI since it comes from the client)
        servletContextHandler.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws ServletException, IOException {
                // serve contents of swagger-ui webjar, but replace URL with ours
                String resourcePath = "/META-INF/resources/webjars" + req.getRequestURI();
                var swaggerUiFile = getClass().getResourceAsStream(resourcePath);
                if (swaggerUiFile == null) {
                    resp.setStatus(NOT_FOUND.getStatusCode());
                } else if (resourcePath.endsWith("/swagger-initializer.js")) {
                    ByteArrayOutputStream originialInitializer = new ByteArrayOutputStream();
                    swaggerUiFile.transferTo(originialInitializer);
                    String initializer = originialInitializer.toString().replaceAll(
                            "https://petstore.swagger.io/v2/swagger.json", "http://localhost:9000/swagger.json");
                    resp.getOutputStream().write(initializer.getBytes(StandardCharsets.UTF_8));
                } else {
                    swaggerUiFile.transferTo(resp.getOutputStream());
                }
            };
        }), "/swagger-ui/*");

        // add CORS filter
        var corsFilterHolder = new FilterHolder(new Filter() {

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                String requestOrigin = ((HttpServletRequest) request).getHeader("Origin");
                if (requestOrigin != null
                        && requestOrigin.matches("(http|https)://(127.0.0.[0-9]+|localhost)(:[0-9]+)?")) {
                    var responseWrapper = new HttpServletResponseWrapper((HttpServletResponse) response) {
                    };
                    responseWrapper.addHeader("Access-Control-Allow-Origin", requestOrigin);
                    response = responseWrapper;
                }
                chain.doFilter(request, response);
            }

        });
        servletContextHandler.addFilter(corsFilterHolder, "/swagger.json", EnumSet.of(DispatcherType.REQUEST));
        servletContextHandler.addFilter(corsFilterHolder, "/api/*", EnumSet.of(DispatcherType.REQUEST));

        // TODO:
        // jdbi+HikariCP
        // flyway
        // oauth
        // https
        // logging

        server.start();
        server.join();
    }
}

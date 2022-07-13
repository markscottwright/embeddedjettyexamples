package embeddedjettyexamples;

import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static java.util.stream.Collectors.toSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.flywaydb.core.Flyway;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.wadl.WadlFeature;
import org.glassfish.jersey.servlet.ServletContainer;
import org.jdbi.v3.core.Jdbi;
import org.postgresql.ds.PGSimpleDataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

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

public class SimpleServerWithFlyway extends Application {

    private Jdbi jdbi;

    public static class Greeting {
        public String greeting;
        public Integer repeat;
    }

    public static class Database {
        private Jdbi jdbi;

        public Database(Jdbi jdbi) {
            this.jdbi = jdbi;
        }

        public String getGreeting() {
            return jdbi.withHandle(h -> {
                return h.select("select greeting from greetings order by added desc limit 1").mapTo(String.class)
                        .findOne();
            }).orElse("Hi ya!");
        }

        public void addGreeting(String greeting) {
            jdbi.useHandle(h -> {
                h.execute("insert into greetings (greeting, added) values (?, current_timestamp)", greeting);
            });
        }
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
            return database.getGreeting();
        }

        @POST
        @Produces(MediaType.TEXT_PLAIN)
        @Consumes(MediaType.APPLICATION_JSON)
        public String setTheGreeting(Greeting greeting) {
            database.addGreeting(greeting.greeting);
            return greeting.greeting;
        }
    }

    public SimpleServerWithFlyway(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Set<Object> getSingletons() {
        // this, unfortunately, presents a warning. The warning seems incorrect - the
        // only way around it is to use a DI framework.
        // It can be disabled with one of these:
        // log4j.logger.org.glassfish.jersey.internal=OFF
        // log4j.logger.org.glassfish.jersey.internal.inject.Providers=ERROR
        return Set.of(new SimpleResource(new Database(jdbi)));
    }

    public static void main(String[] args) throws Exception {
        // Disable uninteresting warning. keep a reference to this logger, or it gets
        // gc'ed and the config change is lost
        Logger wadlLogger = Logger.getLogger(WadlFeature.class.getName());
        wadlLogger.setLevel(Level.SEVERE);

        // doesn't happen here, though
        Logger.getLogger("org.glassfish.jersey.internal").setLevel(Level.SEVERE);

        // create user jetty2 with encrypted password 'jetty2';
        // grant all privileges on database jetty2 to jetty2;
        var dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] { "localhost" });
        dataSource.setDatabaseName("jetty2");
        dataSource.setUser("jetty2");
        dataSource.setPassword("jetty2");
        var hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(dataSource);
        HikariDataSource hikariDataSource = new HikariDataSource(hikariConfig);
        Jdbi jdbi = Jdbi.create(hikariDataSource);

        Flyway flyway = Flyway.configure().dataSource(hikariDataSource).load();
        flyway.migrate();

        int port = 9000;
        String apiPath = "api";
        String apiPathSpec = "/" + apiPath + "/*";
        String originsAllowedToUseApi = "(http|https)://(127.0.0.[0-9]+|localhost)(:[0-9]+)?";
        String swaggerPathSpec = "/swagger.json";

        // base web server support
        var server = new Server();
        var serverConnector = new ServerConnector(server);
        serverConnector.setPort(port);
        server.addConnector(serverConnector);
        var servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/");
        server.setHandler(servletContextHandler);

        // add rest api endpoint
        var application = ResourceConfig.forApplication(new SimpleServerWithFlyway(jdbi));
        var servletHolder = new ServletHolder(new ServletContainer(application));
        servletContextHandler.addServlet(servletHolder, apiPathSpec);

        // add swagger definition servlet - note that this could be easily pre-computed
        Reader reader = new Reader(new SwaggerConfiguration()) {
            @Override
            protected String resolveApplicationPath() {
                return apiPath;
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
        }), swaggerPathSpec);

        // add swagger-ui servlet
        // (this is incomplete - we should set media-types, cache ttls and compression.
        // Also, we should safety check req.getRequestURI since it comes from the
        // client)
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

        // add CORS filter that allows any port on localhost or 127.0.0.x.
        var corsFilterHolder = new FilterHolder(new Filter() {

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                String requestOrigin = ((HttpServletRequest) request).getHeader("Origin");
                if (requestOrigin != null && requestOrigin.matches(originsAllowedToUseApi)) {
                    var responseWrapper = new HttpServletResponseWrapper((HttpServletResponse) response) {
                    };
                    responseWrapper.addHeader("Access-Control-Allow-Origin", requestOrigin);
                    String requestedHeaders = ((HttpServletRequest) request)
                            .getHeader("Access-Control-Request-Headers");
                    String requestedMethod = ((HttpServletRequest) request).getHeader("Access-Control-Request-Method");
                    if (requestedHeaders != null)
                        responseWrapper.addHeader("Access-Control-Allow-Headers", requestedHeaders);
                    if (requestedMethod != null)
                        responseWrapper.addHeader("Access-Control-Allow-Methods", requestedMethod);
                    response = responseWrapper;
                }
                chain.doFilter(request, response);
            }

        });
        servletContextHandler.addFilter(corsFilterHolder, swaggerPathSpec, EnumSet.of(DispatcherType.REQUEST));
        servletContextHandler.addFilter(corsFilterHolder, apiPathSpec, EnumSet.of(DispatcherType.REQUEST));

        // TODO:
        // oauth
        // https
        // logging

        server.start();
        server.join();
    }
}

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

import org.eclipse.jetty.server.CustomRequestLog;
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
import org.jdbi.v3.core.statement.Slf4JSqlLogger;
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

public class SimpleServer5WithLogging extends Application {

    private Database database;

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

    public SimpleServer5WithLogging(Database database) {
        this.database = database;
    }

    @Override
    public Set<Object> getSingletons() {
        // this, unfortunately, presents a warning. The warning seems incorrect - the
        // only way around it is to use a DI framework.
        // It can be disabled with one of these:
        // log4j.logger.org.glassfish.jersey.internal=OFF
        // log4j.logger.org.glassfish.jersey.internal.inject.Providers=ERROR
        return Set.of(new SimpleResource(database));
    }

    public static void main(String[] args) throws Exception {
        // #1
        // I prefer one line per log entry
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT.%1$tL %4$s %2$s %5$s%6$s%n");

        // #2
        // By default, there's only one handler - a console one at WARNING. Which means
        // no matter what we set the Loggers to, we'll never see anything because the
        // Handler will ignore it. Change the handler to log everything the Loggers are
        // set to log
        Logger.getLogger("").getHandlers()[0].setLevel(Level.FINEST);

        // #3
        // Disable uninteresting warnings. keep a reference to loggers, or they get
        // gc'ed and the config change is lost
        Logger wadlLogger = Logger.getLogger(WadlFeature.class.getName());
        wadlLogger.setLevel(Level.SEVERE);
        Logger jerseyLogger = Logger.getLogger("org.glassfish.jersey.internal");
        jerseyLogger.setLevel(Level.SEVERE);

        // create user jetty2 with encrypted password 'jetty2';
        // grant all privileges on database jetty2 to jetty2;
        var dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] { "localhost" });
        dataSource.setDatabaseName("jetty2");
        dataSource.setUser("jetty2");
        dataSource.setPassword("jetty2");
        var hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(dataSource);
        var hikariDataSource = new HikariDataSource(hikariConfig);

        Flyway.configure().dataSource(hikariDataSource).load().migrate();

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

        var jdbi = Jdbi.create(hikariDataSource);

        // add rest api endpoint
        var application = ResourceConfig
                .forApplication(new SimpleServer5WithLogging(new Database(jdbi)));
        var servletHolder = new ServletHolder(new ServletContainer(application));
        servletContextHandler.addServlet(servletHolder, apiPathSpec);

        // add swagger definition servlet - note that this could be easily pre-computed
        var reader = new Reader(new SwaggerConfiguration()) {
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

        // #4
        // Log access requests in standard web server format
        server.setRequestLog(new CustomRequestLog());

        // #5
        // enable SQL statement logging
        Logger jdbiLogger = Logger.getLogger("org.jdbi.sql");
        jdbiLogger.setLevel(Level.FINE);
        jdbi.setSqlLogger(new Slf4JSqlLogger());

        // TODO: oauth
        // TODO: https

        server.start();
        server.join();
    }
}

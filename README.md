# Embedded Jetty Examples

I wanted to see how far I could go reproducing something like Dropwizard with these features/goals:

- Dependency injection *everywhere*.  As little magic as possible - I want it to be possible to use
  `find references` and `go to definition` for all meaningful code.
- Startup speed should be as low as possible.  At most a second or two.
- But a real web application, with Swagger, nice RESTful APIs, automatic database upgrades, etc.

OneServlet
: Demonstrate the simplest possible web server.

SimpleRestServer
: Demonstrate the simplest possible RESTful server.

SimpleServerWithSwagger
: Add a Swagger/OpenAPI endpoint to SimpleRestServer

SimpleServerWithSwaggerUI
: Host a SwaggerUI endpoint and add the necessary CORS headers to allow it to access our API

SimpleServerWithJdbi
: Add a database to our example, using JDBI, because ORMs are, in my experience, always more
  trouble than they're worth.

SimpleServerWithFlyway
: Add database migrations

SimpleServerWithLogging
: Set up the logger to ignore some irrelevant errors, log SQL statements and log HTTP access

# What's left

- We need authentication.  I'd like to show something like Keycloak oauth
- I'd like to show how to create a self-signed cert
- I'd like to show how to auto-renew with letsencrypt.com
- Kubernetes-compatible health-checks
- Pagination?
- mTLS authentication?  I am a PKI guy...
- Dockerfile + picocli for configuration
- Version resource, populated by Maven

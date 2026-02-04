# Grizzly NIO

Writing scalable server applications in the Java™ programming language
has always been difficult. Before the advent of the Java New I/O API (NIO),
thread management issues made it impossible for a server to scale to
thousands of users. The Grizzly NIO framework has been designed to help
developers to take advantage of the Java™ NIO API. Grizzly’s goal is to
help developers to build scalable and robust servers using NIO as well
as offering extended framework components: Web Framework (HTTP/S),
WebSocket, Comet, and more!

### Versions and Branches

- `main` : This is the main development branch, currently for 5.0.x (Jakarta EE 11, Java 21+). (latest release is 5.0.0)
- `4.1.x` : This is the sustaining branch for 4.1.x (Jakarta EE 11 with Java 17 support). (no release yet)
- `4.0.x` : This is the sustaining branch for 4.0.x (Jakarta EE 10). (latest release is 4.0.2)
- `3.0.x` : This is the sustaining branch for 3.x (Jakarta EE 9). (latest release is 3.0.1)
- `2.4.x` : This is the sustaining branch for 2.4 (Jakarta EE 8). (latest release is 2.4.4)

Branches for older versions can be found in the [javaee/grizzly](https://github.com/javaee/grizzly) repository.

## Getting Started

### Prerequisites

We have different JDK requirements depending on the branch in use:

- Java 21+ for main and 5.x.x.
- Java 17 for 4.1.x. (no release yet)
- Java 11 for 3.x.x and 4.0.x.
- Oracle JDK 1.8 for 2.4.x.
- Oracle JDK 1.7 for 2.3.x.

Apache Maven 3.8.9 or later in order to build and run the tests.

### Installing

Grizzly is built, assembled and installed using Maven. 
Grizzly is deployed to the [Maven Central](https://central.sonatype.com/namespace/org.glassfish.grizzly) repository. 
Binary, source, javadoc, and sample JARs can all be found there.

#### Core framework

`grizzly-framework` is the minimum requirement for all Grizzly applications.

```
<dependencies>
    <dependency>
        <groupId>org.glassfish.grizzly</groupId>
        <artifactId>grizzly-framework</artifactId>
        <version>5.0.0</version>
    </dependency>
</dependencies>
```

Additionally, we provide various frameworks and libraries 
such as HTTP framework, HTTP Server framework, HTTP Servlet framework, HTTP2 framework, 
Port unification, WebSockets and etc.

For more information, see the [Maven coordinates](#maven-coordinates) section and 
the [Dependencies](https://eclipse-ee4j.github.io/glassfish-grizzly/dependencies.html) for the maven
coordinates of the release artifacts.

#### Maven BOM (Bill of Materials)

Grizzly also provides the BOM concept which is natively supported by Maven.

1. Import the BOM by adding the following snippet to your pom.xml's `<dependencyManagement>` section.

```
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.glassfish.grizzly</groupId>
            <artifactId>grizzly-bom</artifactId>
            <version>5.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

2. Add your dependencies to the relevant projects, as usual (except without a `<version>`).

```
<dependencies>
    <dependency>
        <groupId>org.glassfish.grizzly</groupId>
        <artifactId>grizzly-framework</artifactId>
    </dependency>
</dependencies>
<dependencies>
    <dependency>
        <groupId>org.glassfish.grizzly</groupId>
        <artifactId>grizzly-framework-monitoring</artifactId>
    </dependency>
</dependencies>
```

#### Building

If building in your local environment:

```
mvn clean install
```

## Running the tests

```
mvn clean install
```

## Maven coordinates

### JAR

|Module|`<artifactId>`|Description|
|:-:|:-:|:-:|
|Core framework|`grizzly-framework`|All core services for TCP/UDP transports, memory management services/buffers, NIO event loop/filter chains/filters.|
|HTTP framework|`grizzly-http`|The base logic for dealing with HTTP messages on both the server and client sides.|
|HTTP Server framework|`grizzly-http-server`|HTTP server services using an API very similar to Servlets.|
|HTTP Servlet framework|`grizzly-http-servlet`|Building on top of `grizzly-http-server`, provides basic Servlet functionality. NOTE: This is not a Servlet compliant implementation and as such, not all features exposed by a typical Servlet container are available here.|
|HTTP/2 framework|`grizzly-http2`|The base logic for dealing with HTTP/2 messages on both the server and client sides.|
|Port unification|`grizzly-portunif`|The ability to run multiple protocols (example: http, https, or other protocols) over a single TCP port.|
|Comet|`grizzly-comet`|Building on top of `grizzly-http-server`, provides a framework for building scalable Comet-based applications.|
|WebSockets|`grizzly-websockets`|A custom API (this predates JSR 356) for building Websocket applications on both the server and client sides.|
|AJP|`grizzly-http-ajp`|Support for the AJP protocol.|
|JAX-WS|`grizzly-http-server-jaxws`|Building on top of `grizzly-http-server`, provides the ability to create JAX-WS applications.|
|Core framework monitoring|`grizzly-framework-monitoring`|Allows developers to leverage `grizzly-framework`'s JMX monitoring within their applications.|
|HTTP framework monitoring|`grizzly-http-monitoring`|Allows developers to leverage `grizzly-http`'s JMX monitoring within their applications.|
|HTTP Server framework monitoring|`grizzly-http-server-monitoring`|Allows developers to leverage `grizzly-http-server`'s JMX monitoring within their applications.|
|Connection Pool|`connection-pool`|A robust client-side connection pool implementation.|
|Server Name Indication (SNI) TLS extension support|`tls-sni`||
|OSGi HTTP Service|`grizzly-httpservice-bundle`|An OSGi HTTP Service implementation. This JAR includes the Grizzly Servlet, Websocket, port unification, and comet libraries allowing developers to build common types of HTTP server applications within an OSGi environment.|
|HTTP Server Multipart|`grizzly-http-server-multipart`|A non-blocking API for processing multipart requests.|
|Grizzly HTTP Servlet Extras|`grizzly-http-servlet-extras`|A drop-in Servlet Filter that builds on the HTTP Server Multipart library providing non-blocking multipart handling.|

### Bundle

In addition to the individual dependency JARs, 
we also offer bundles which aggregate multiple modules together into a single JAR for convenience.

|Bundle|`<artifactId>`|Description|
|:-:|:-:|:-:|
|The Core Framework Bundle|`grizzly-core`|Aggregates the `grizzly-framework` and `grizzly-portunif` modules.|
|The HTTP Server Core Bundle|`grizzly-http-server-core`|Aggregates the `grizzly-core` bundle with the `grizzly-http-server`, `grizzly-http-ajp`, and `grizzly-http-server-multipart` modules.|
|The HTTP Servlet Bundle|`grizzly-http-servlet-server`|Aggregates the `grizzly-http-server-core` bundle with the `grizzly-http-servlet` module.|
|The Grizzly Comet Bundle|`grizzly-comet-server`|Aggregates the `grizzly-http-server-core` bundle with the `grizzly-comet` module.|
|The Grizzly Websockets Bundle|`grizzly-websockets-server`|Aggregates the `grizzly-http-server-core` bundle with the `grizzly-websockets` module.|
|The Grizzly HTTP ‘All’ Bundle|`grizzly-http-all`|Aggregates the `grizzly-http-server-core` bundle with the `grizzly-http-servlet`, `grizzly-comet`, and `grizzly-websocket` modules.|

## License

This project is licensed under the EPL-2.0 - see the [LICENSE.txt](https://github.com/eclipse-ee4j/grizzly/blob/master/LICENSE.txt) file for details.

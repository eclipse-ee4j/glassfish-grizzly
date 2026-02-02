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

- JDK 11+ for master and 3.x.x.
- Oracle JDK 1.8 for 2.4.x.
- Oracle JDK 1.7 for 2.3.x.

Apache Maven 3.3.9 or later in order to build and run the tests.

### Installing

See https://eclipse-ee4j.github.io/grizzly/dependencies.html for the maven
coordinates of the 2.3.x release artifacts.

If building in your local environment:

```
mvn clean install
```


## Running the tests

```
mvn clean install
```

## License

This project is licensed under the EPL-2.0 - see the [LICENSE.txt](https://github.com/eclipse-ee4j/grizzly/blob/master/LICENSE.txt) file for details.


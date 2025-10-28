UDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Traccar is a GPS tracking server that supports 150+ device protocols. It's built with Java 17 using a Netty-based event-driven architecture with Google Guice for dependency injection.

## Build and Development Commands

### Building the Project
```bash
# Build the project (creates JAR in target/)
./gradlew build

# Build without running tests
./gradlew build -x test

# Clean and rebuild
./gradlew clean build

# Copy dependencies to target/lib
./gradlew copyDependencies
```

### Running Tests
```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests org.traccar.protocol.Gps103ProtocolDecoderTest

# Run tests matching a pattern
./gradlew test --tests "*ProtocolDecoderTest"
```

### Code Quality
```bash
# Run checkstyle
./gradlew checkstyle

# Checkstyle configuration is in gradle/checkstyle.xml
```

### Running the Server
```bash
# Run from JAR (after building)
java -jar target/tracker-server-6.6.jar traccar.xml

# Main class: org.traccar.Main
```

## Architecture Overview

### Core Data Flow
Device → Protocol Handler → Frame Decoder → Protocol Decoder → Position → Processing Pipeline → Storage → Notification/Forwarding

### Key Package Structure

- **org.traccar.protocol/** (657 files) - Individual device protocol implementations (TCP/UDP handlers, decoders, encoders)
- **org.traccar.handler/** - Position and event processing chain
  - **handler/events/** - Event detection (overspeed, geofence, motion, alarm, etc.)
  - **handler/network/** - Network-level handlers (logging, acknowledgment, forwarding)
- **org.traccar.api/** - REST API layer
  - **api/resource/** - JAX-RS resources (DeviceResource, PositionResource, UserResource, etc.)
  - **api/security/** - Authentication & authorization (OAuth2, LDAP, JWT)
- **org.traccar.storage/** - Database abstraction layer
  - Storage interface with multiple backends (MySQL, PostgreSQL, H2, SQL Server, MariaDB)
  - QueryBuilder pattern for dynamic SQL
- **org.traccar.database/** - Business logic managers (NotificationManager, CommandsManager, etc.)
- **org.traccar.session/** - Device session management and caching
- **org.traccar.model/** - Domain objects (Device, Position, User, Event, etc.)
- **org.traccar.forward/** - Position/event forwarding to external systems (HTTP, Kafka, MQTT, Redis, AMQP)
- **org.traccar.geocoder/** - Reverse geocoding providers (15+ services)

### Position Processing Pipeline

ProcessingHandler orchestrates a sequential chain of handlers. Each handler can filter (stop propagation) or pass to the next:

1. ComputedAttributesHandler.Early - Pre-calculation custom attributes
2. OutdatedHandler - Mark outdated positions
3. TimeHandler - Time adjustments
4. GeolocationHandler - Cell tower geolocation
5. HemisphereHandler - Hemisphere corrections
6. DistanceHandler - Calculate cumulative distance
7. FilterHandler - GPS accuracy filtering
8. GeofenceHandler - Geofence trigger detection
9. GeocoderHandler - Reverse address lookup
10. SpeedLimitHandler - Speed limit violations
11. MotionHandler - Motion state detection
12. ComputedAttributesHandler.Late - Post-calculation attributes
13. EngineHoursHandler - Engine hours accumulation
14. DriverHandler - Driver assignment via iButton/RFID
15. CopyAttributesHandler - Attribute copying between positions
16. PositionForwardingHandler - Forward to external systems
17. DatabaseHandler - Persist to database

Event handlers run asynchronously after position processing.

## Protocol Implementation Pattern

To add a new device protocol, create 3-4 classes following this pattern:

### 1. Protocol Class (e.g., `ExampleProtocol.java`)
```java
public class ExampleProtocol extends BaseProtocol {
    public ExampleProtocol() {
        super("example");
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new ExampleFrameDecoder());
                pipeline.addLast(new ExampleProtocolDecoder(ExampleProtocol.this));
            }
        });
    }
}
```

### 2. Frame Decoder (optional, for TCP protocols)
```java
public class ExampleFrameDecoder extends BaseFrameDecoder {
    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ByteBuf buf) {
        // Extract logical message frames from byte stream
    }
}
```

### 3. Protocol Decoder (required)
```java
public class ExampleProtocolDecoder extends BaseProtocolDecoder {
    public ExampleProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) {
        // Parse message and return Position object
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, uniqueId);
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        // ... set position fields
        return position;
    }
}
```

### 4. Protocol Encoder (optional, for commands)
```java
public class ExampleProtocolEncoder extends BaseProtocolEncoder {
    @Override
    protected Object encodeCommand(Command command) {
        // Convert command to device-specific format
    }
}
```

**Auto-discovery**: ServerManager uses ClassScanner to discover all Protocol implementations. Protocols are enabled via configuration: `protocol.example.port=5000`

## Configuration Management

Configuration is stored in `traccar.xml` (XML properties format). Environment variables can be used if `CONFIG_USE_ENVIRONMENT_VARIABLES=true`.

### Common Configuration Patterns
```xml
<!-- Database configuration -->
<entry key='database.driver'>com.mysql.cj.jdbc.Driver</entry>
<entry key='database.url'>jdbc:mysql://localhost/traccar</entry>

<!-- Protocol-specific configuration -->
<entry key='protocol.gps103.port'>5001</entry>
<entry key='protocol.gps103.timeout'>30000</entry>

<!-- Feature toggles -->
<entry key='geocoder.enable'>true</entry>
<entry key='speedLimit.enable'>true</entry>
<entry key='filter.enable'>true</entry>
```

Access configuration in code via injection:
```java
@Inject
private Config config;

int timeout = config.getInteger(Keys.PROTOCOL_TIMEOUT.withPrefix(protocol));
```

## Database and Storage

### Storage Interface
The `Storage` interface abstracts database operations. Inject it into any class:
```java
@Inject
private Storage storage;

List<Device> devices = storage.getObjects(Device.class, new Request(
    new Columns.Include("id", "name", "uniqueId"),
    new Condition.Equals("groupId", groupId)
));
```

### Migrations
Liquibase handles schema migrations. Migration files are in `src/main/resources/org/traccar/storage/`.

### Supported Databases
- MySQL/MariaDB (primary)
- PostgreSQL
- H2 (embedded, for testing)
- Microsoft SQL Server

## Dependency Injection with Guice

### Constructor/Setter Injection
```java
public class MyService {
    private final Storage storage;

    @Inject
    public void setStorage(Storage storage) {
        this.storage = storage;
    }
}
```

### Singletons
Most services are singletons:
```java
@Singleton
public class MyManager { }
```

## Web API (REST Endpoints)

### Adding a New Endpoint
Extend `BaseObjectResource` or `BaseResource`:
```java
@Path("myresource")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MyResource extends BaseObjectResource<MyModel> {
    @GET
    @Path("{id}")
    public MyModel get(@PathParam("id") long id) {
        // Implementation
    }
}
```

**Technology Stack**: Jersey 3 (JAX-RS) on Jetty 11 with Jackson for JSON serialization.

## Testing

### Test Structure
- Protocol tests extend test base classes and use `.verifyPosition()` helpers
- Tests are in `src/test/java/` mirroring the main source structure
- Use JUnit 5 for all tests

### Running Protocol Decoder Tests
Protocol decoder tests verify message parsing:
```java
var decoder = inject(new Gps103ProtocolDecoder(null));
verifyPosition(decoder, text("imei:123456789012345,tracker,150101120000,+12.345678,+123.456789,0.00,0;"));
```

## Important Patterns and Conventions

### Session Management
- `ConnectionManager` tracks active device connections
- `DeviceSession` maintains per-device state (device ID, model, IP)
- `CacheManager` provides fast device attribute lookups
- Always get session: `DeviceSession session = getDeviceSession(channel, remoteAddress, uniqueId)`

### Command Handling
- Commands are queued in the database
- Dequeued when device connects
- Protocol-specific encoders transform generic commands to device format

### Position Forwarding
Implement `PositionForwarder` interface to forward positions to external systems. Built-in forwarders:
- HTTP (JSON/URL-encoded)
- Kafka
- MQTT
- Redis
- AMQP (RabbitMQ)

### Error Handling
- Handlers use try-catch with logging to prevent pipeline breakage
- One failing handler doesn't stop the pipeline
- Log errors with SLF4J: `LOGGER.warn("Error processing", exception)`

## Code Style

- Follow Java naming conventions
- Use checkstyle configuration in `gradle/checkstyle.xml`
- Indent with 4 spaces
- Keep methods focused and small
- Use meaningful variable names (avoid single-letter except in loops)

# ambassador-java-rate-limiter
A Java-based Rate Limiter service for the Ambassador API gateway


# Build

```shell
mvn clean package
```

# Run

```shell
java \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  -Dio.netty.tryReflectionSetAccessible=true \
  -jar target/simpleimpl-0.1.0-SNAPSHOT.jar
```
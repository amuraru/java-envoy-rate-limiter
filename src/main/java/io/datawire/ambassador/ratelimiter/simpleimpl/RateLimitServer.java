package io.datawire.ambassador.ratelimiter.simpleimpl;

import io.envoyproxy.envoy.service.ratelimit.v3.RateLimitRequest;
import io.envoyproxy.envoy.service.ratelimit.v3.RateLimitResponse;
import io.envoyproxy.envoy.service.ratelimit.v3.RateLimitServiceGrpc;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class RateLimitServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitServer.class);

    private static final int port = 50051;

    private Server server;

    private void start() throws Exception {
        LOGGER.info("Attempting to start server listening on {}", port);

        server = ServerBuilder.forPort(port)
                .addService(new RateLimiterImpl())
                .addService(ProtoReflectionService.newInstance())
                .build()
                .start();

        LOGGER.info("Server started, listening on {}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown hook.
            System.err.println("Shutting down gRPC server since JVM is shutting down");
            RateLimitServer.this.stop();
            System.err.println("gRPC Server shut down");
        }));
    }

    private void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Main launches the server from the command line.
     */
    public static void main(String[] args) throws Exception {
        final RateLimitServer server = new RateLimitServer();
        server.start();
        server.blockUntilShutdown();
    }

    private static class RateLimiterImpl extends RateLimitServiceGrpc.RateLimitServiceImplBase {

        private final Map<String, Bucket> serviceBuckets = new ConcurrentHashMap<>();

        @Override
        public void shouldRateLimit(RateLimitRequest rateLimitRequest, StreamObserver<RateLimitResponse> responseStreamObserver) {
            logDebug(rateLimitRequest);
            String destServiceName = extractDestServiceNameFrom(rateLimitRequest);
            Bucket bucket = getServiceBucketFor(destServiceName);

            RateLimitResponse.Code code;
            if (bucket.tryConsume(1)) {
                code = RateLimitResponse.Code.OK;
            } else {
                code = RateLimitResponse.Code.OVER_LIMIT;
            }

            RateLimitResponse rateLimitResponse = generateRateLimitResponse(code);
            responseStreamObserver.onNext(rateLimitResponse);
            responseStreamObserver.onCompleted();
        }

        private void logDebug(RateLimitRequest rateLimitRequest) {
            LOGGER.debug("Domain: {}", rateLimitRequest.getDomain());
            LOGGER.debug("DescriptorsCount: {}", rateLimitRequest.getDescriptorsCount());

            if (LOGGER.isDebugEnabled()) {
                rateLimitRequest.getDescriptorsList()
                        .forEach(d -> {
                            LOGGER.debug("-- New descriptor -- ");
                            d.getEntriesList().forEach(e -> LOGGER.debug("Descriptor Entry: [{}, {}]", e.getKey(), e.getValue()));
                        });
            }
        }

        private String extractDestServiceNameFrom(RateLimitRequest rateLimitRequest) {
            // we're making the assumption that the dest(ination) service name
            // will always be in this position
            return rateLimitRequest.getDescriptors(0).getEntries(1).getValue();
        }

        private Bucket getServiceBucketFor(String destServiceName) {
            Bucket bucket = serviceBuckets.get(destServiceName);
            if (bucket == null) {
                bucket = createNewBucket();
                serviceBuckets.put(destServiceName, bucket);
                LOGGER.debug("Created new bucket for destination {}", destServiceName);
            }
            return bucket;
        }

        private Bucket createNewBucket() {
            long overdraft = 20;
            Refill refill = Refill.greedy(10, Duration.ofSeconds(1));
            Bandwidth limit = Bandwidth.classic(overdraft, refill);
            return Bucket.builder().addLimit(limit).build();
        }

        private RateLimitResponse generateRateLimitResponse(RateLimitResponse.Code code) {
            LOGGER.debug("Generate rate limit response with code: {} ", code);
            return RateLimitResponse.newBuilder().setOverallCode(code).build();
        }
    }
}

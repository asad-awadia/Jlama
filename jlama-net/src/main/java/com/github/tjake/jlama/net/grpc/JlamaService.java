package com.github.tjake.jlama.net.grpc;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.net.*;
import com.github.tjake.jlama.tensor.AbstractTensor;
import com.github.tjake.jlama.tensor.FloatBufferTensor;
import com.github.tjake.jlama.tensor.TensorShape;
import com.github.tjake.jlama.tensor.operations.TensorOperationsProvider;
import com.github.tjake.jlama.util.Pair;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.UnsafeByteOperations;
import io.grpc.stub.StreamObserver;
import jdk.incubator.vector.FloatVector;
import org.jctools.queues.MpmcArrayQueue;
import org.jctools.queues.MpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class JlamaService extends JlamaServiceGrpc.JlamaServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(JlamaService.class);
    private static final Descriptors.FieldDescriptor tensorField = NormRequest.getDescriptor().findFieldByNumber(NormRequest.TENSOR_FIELD_NUMBER);
    private final AbstractModel model;
    private final int workerCount;
    private final ConcurrentMap<UUID, RegisterResponse> workers;

    private final GeneratorGroup generatorGroup;

    private final ConcurrentMap<String, MpmcArrayQueue<Pair<NormRequest, StreamObserver<NormResponse>>>> norms;
    private final int headSize;
    private final int headCount;
    private final int headsPerWorker;

    public JlamaService(AbstractModel model, int workerCount) {
        Preconditions.checkArgument(workerCount <= model.getConfig().numberOfHeads, "Worker count must be less than or equal to number of heads");
        this.model = model;
        this.workerCount = workerCount;
        this.workers = new ConcurrentHashMap<>();
        this.norms = new ConcurrentHashMap<>();
        this.generatorGroup = new GeneratorGroup();

        this.headCount = model.getConfig().numberOfHeads;
        this.headSize = model.getConfig().headSize;
        this.headsPerWorker = headCount / workerCount;
    }

    public void waitForReady() {
        while (true) {
            if (generatorGroup.generators.size() == workerCount) {
                generatorGroup.waitForReady();
                return;
            }
            Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
        }
    }

    public void shutdown() {
        for (Generator g : generatorGroup.generators) {
            try {
                g.responseObserver.onCompleted();
            } catch (Exception e) {
                logger.debug("Exception when shutting down", e);
            }
        }
    }

    /**
     * Register a worker with the coordinator.  The coordinator will return the offset and length of the embedding that
     * the worker is responsible for.
     */
    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        synchronized (workers) {
            ByteBuffer bb = request.getWorkerid().asReadOnlyByteBuffer();
            UUID wid = new UUID(bb.getLong(), bb.getLong());
            if (workers.containsKey(wid)) {
                responseObserver.onNext(workers.get(wid));
                responseObserver.onCompleted();
            } else {

                if (workers.size() == workerCount) {
                    responseObserver.onError(new RuntimeException("Not accepting any more workers"));
                    return;
                }

                int offset = workers.size() * headsPerWorker * headSize;
                int length = headsPerWorker * headSize;

                RegisterResponse r = RegisterResponse.newBuilder().setOffset(offset).setLength(length).build();
                workers.put(wid, r);
                logger.info("Registered worker {} with offset {} and length {}", wid, offset, length);

                responseObserver.onNext(r);
                responseObserver.onCompleted();
            }
        }
    }

    public AbstractTensor generateNextOutput(UUID session, int tokenId, int position) {
        return generatorGroup.generateNextOutput(session, tokenId, position);
    }

    @Override
    public StreamObserver<GenerateRequest> generate(StreamObserver<GenerateResponse> responseObserver) {
        Generator generator = new Generator(responseObserver);
        generatorGroup.add(generator);
        logger.info("Added worker {}", generatorGroup.generators.size());
        return generator;
    }

    @Override
    public StreamObserver<NormRequest> norm(StreamObserver<NormResponse> responseObserver) {

        return new StreamObserver<NormRequest>() {
            @Override
            public void onNext(NormRequest request)
            {
                String key = STR."\{UUID.nameUUIDFromBytes(request.getUuid().toByteArray())}:\{request.getLayer()}";
                MpmcArrayQueue<Pair<NormRequest, StreamObserver<NormResponse>>> norm = norms.computeIfAbsent(key, k -> new MpmcArrayQueue<>(workerCount + 1));
                norm.add(Pair.create(request, responseObserver));

                // If we have all the workers, then we can calculate the result and send it back
                if (norm.size() == workerCount && norms.remove(key, norm))
                {
                    float sumSq = 0;
                    float sum = 0;
                    MemorySegment[] tensors = null;
                    Integer length = null;
                    for (Pair<NormRequest, StreamObserver<NormResponse>> f : norm)
                    {
                        sumSq += f.left.getSumSq();
                        sum += f.left.getSum();
                        if (f.left.getTensorCount() > 0)
                        {
                            if (tensors == null)
                            {
                                tensors = new MemorySegment[f.left.getTensorCount()];
                                for (int i = 0; i < tensors.length; i++)
                                {
                                    ByteBuffer bb = ByteBuffer.wrap(f.left.getTensor(i).toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                                    tensors[i] = MemorySegment.ofBuffer(bb);
                                    if (length == null)
                                        length = bb.remaining() / Float.BYTES;
                                }
                            }
                            else
                            {
                                for (int i = 0; i < tensors.length; i++)
                                {
                                    MemorySegment ms = MemorySegment.ofBuffer(f.left.getTensor(i).asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN));
                                    //Sum float buffers
                                    accumulateF32(tensors[i], ms, length);
                                }
                            }
                        }
                    }

                    NormResponse.Builder responseBuilder = NormResponse.newBuilder()
                            .setSumSq(sumSq)
                            .setSum(sum);

                    if (tensors != null)
                    {
                        for (int i = 0; i < tensors.length; i++)
                            responseBuilder = responseBuilder.addTensor(UnsafeByteOperations.unsafeWrap(tensors[i].asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)));
                    }

                    NormResponse response = responseBuilder.build();
                    for (Pair<NormRequest, StreamObserver<NormResponse>> f : norm)
                    {
                        f.right.onNext(response);
                        //f.right.onCompleted();
                    }

                    norm.clear();
                }
            }

            @Override
            public void onError(Throwable throwable)
            {

            }

            @Override
            public void onCompleted()
            {

            }
        };
    }

    void accumulateF32(MemorySegment a, MemorySegment b, int length) {
        int upperBound = FloatVector.SPECIES_PREFERRED.loopBound(length);
        int i = 0;

        for (; i < upperBound; i += FloatVector.SPECIES_PREFERRED.length()) {
            int fi = i * Float.BYTES;
            FloatVector va = FloatVector.fromMemorySegment(FloatVector.SPECIES_PREFERRED, a, fi, ByteOrder.LITTLE_ENDIAN);
            FloatVector vb = FloatVector.fromMemorySegment(FloatVector.SPECIES_PREFERRED, b, fi, ByteOrder.LITTLE_ENDIAN);
            va.add(vb).intoMemorySegment(a, fi, ByteOrder.LITTLE_ENDIAN);
        }

        // tail
        for (; i < length; i++) {
            a.set(ValueLayout.JAVA_FLOAT, i, a.get(ValueLayout.JAVA_FLOAT, i) + b.get(ValueLayout.JAVA_FLOAT, i));
        }
    }

    public class GeneratorGroup {
        private final List<Generator> generators;

        private GeneratorGroup() {
            this.generators = new ArrayList<>();
        }

        private void add(Generator generator) {
            generators.add(generator);
        }

        public void waitForReady() {
            for (Generator g : generators) {
                Uninterruptibles.awaitUninterruptibly(g.isReady());
            }
        }

        public AbstractTensor generateNextOutput(UUID session, int tokenId, int position) {
            Preconditions.checkArgument(generators.size() == workerCount, "Missing workers %d", workers.size());
            ByteString sid = ByteString.copyFrom(ByteBuffer.allocate(128).putLong(session.getMostSignificantBits()).putLong(session.getLeastSignificantBits()).flip());
            GenerateResponse gr = GenerateResponse.newBuilder().setSession(sid).setToken(tokenId).setPosition(position).build();
            for (Generator g : generators) {
                g.registerLatch(session);
                g.responseObserver.onNext(gr);
            }

            AbstractTensor output = model.makeFullTensor(model.getConfig().embeddingLength);

            for (Generator g : generators) {
                ByteString v = g.waitForOutput(session);
                RegisterResponse r = workers.get(g.workerId);

                FloatBuffer f = v.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                for(int i = 0; i < r.getLength(); i++) {
                    output.set(f.get(), r.getOffset() + i);
                }
            }

            //logger.info("Received output from worker {}", TensorOperationsProvider.get().sum(output));

            return output;
        }
    }

    class Generator implements StreamObserver<GenerateRequest> {
        private static final Logger logger = LoggerFactory.getLogger(Generator.class);

        private volatile UUID workerId;
        private CountDownLatch readyLatch;
        private final StreamObserver<GenerateResponse> responseObserver;
        private final ConcurrentMap<UUID, ByteString> outputs;
        private final ConcurrentMap<UUID, CountDownLatch> outputLatches;


        public Generator(StreamObserver<GenerateResponse> responseObserver) {
            this.workerId = null;
            this.readyLatch = new CountDownLatch(1);
            this.responseObserver = responseObserver;
            this.outputs = new ConcurrentHashMap<>();
            this.outputLatches = new ConcurrentHashMap<>();
        }

        @Override
        public void onNext(GenerateRequest generateRequest) {
            if (workerId == null) {
                ByteBuffer bb = generateRequest.getWorkerid().asReadOnlyByteBuffer();
                workerId = new UUID(bb.getLong(), bb.getLong());
                readyLatch.countDown();
                logger.info("Worker {} ready", workerId);
                return;
            }

            ByteBuffer bb = generateRequest.getSession().asReadOnlyByteBuffer();
            UUID session = new UUID(bb.getLong(), bb.getLong());

            if (outputs.containsKey(session)) {
                logger.error("Previous output not consumed from worker {}", workerId);
            }

            outputs.put(session, generateRequest.getTensor());

            if (outputLatches.containsKey(session)) {
                outputLatches.get(session).countDown();
            } else {
                logger.error("No latch registered for session {}", session);
            }
        }

        public void registerLatch(UUID session) {
            outputLatches.put(session, new CountDownLatch(1));
        }

        public ByteString waitForOutput(UUID session) {
            CountDownLatch latch = outputLatches.get(session);
            if (latch == null)
                throw new RuntimeException("No latch registered for session " + session);

            Uninterruptibles.awaitUninterruptibly(latch);
            ByteString output = outputs.get(session);
            if (output == null)
                throw new RuntimeException("No output received for session " + session);

            outputs.remove(session);
            return output;
        }

        @Override
        public void onError(Throwable throwable) {
            logger.error("Error encountered from worker {}", workerId, throwable);
        }

        @Override
        public void onCompleted() {
            logger.info("Worker {} completed", workerId);
        }

        public CountDownLatch isReady() {
            return readyLatch;
        }
    }
}

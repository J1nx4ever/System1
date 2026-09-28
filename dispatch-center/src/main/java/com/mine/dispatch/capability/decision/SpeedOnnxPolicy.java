package com.mine.dispatch.capability.decision;

import Fusion.grpc.Action;
import Fusion.grpc.ActionDict;
import Fusion.grpc.ObservationDict;
import ai.onnxruntime.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class SpeedOnnxPolicy implements AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final SpeedGraphObservationBuilder graphBuilder = new SpeedGraphObservationBuilder();

    public SpeedOnnxPolicy(String modelPath) {
        try {
            Path path = Path.of(modelPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("speed ONNX model does not exist: " + path);
            }
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = environment.createSession(path.toString(), options);
            }
            for (String name : new String[]{"nodes", "edges", "edge_indices", "node_mask", "edge_mask"}) {
                if (!session.getInputNames().contains(name)) {
                    throw new IllegalStateException("speed ONNX input missing: " + name);
                }
            }
            if (!session.getOutputNames().contains("acceleration")) {
                throw new IllegalStateException("speed ONNX output missing: acceleration");
            }
        } catch (OrtException exception) {
            throw new IllegalStateException("cannot load speed ONNX model", exception);
        }
    }

    public ActionDict predict(ObservationDict request) {
        SpeedGraphObservationBuilder.Batch batch = graphBuilder.build(request);
        long[] nodeShape = {batch.size(), SpeedGraphObservationBuilder.MAX_NODES,
                SpeedGraphObservationBuilder.NODE_DIM};
        long[] edgeShape = {batch.size(), SpeedGraphObservationBuilder.MAX_EDGES,
                SpeedGraphObservationBuilder.EDGE_DIM};
        long[] indexShape = {batch.size(), SpeedGraphObservationBuilder.MAX_EDGES, 2};
        long[] nodeMaskShape = {batch.size(), SpeedGraphObservationBuilder.MAX_NODES};
        long[] edgeMaskShape = {batch.size(), SpeedGraphObservationBuilder.MAX_EDGES};
        try (OnnxTensor nodes = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(batch.nodes()), nodeShape);
             OnnxTensor edges = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(batch.edges()), edgeShape);
             OnnxTensor indices = OnnxTensor.createTensor(environment,
                     LongBuffer.wrap(batch.edgeIndices()), indexShape);
             OnnxTensor nodeMask = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(batch.nodeMask()), nodeMaskShape);
             OnnxTensor edgeMask = OnnxTensor.createTensor(environment,
                     FloatBuffer.wrap(batch.edgeMask()), edgeMaskShape);
             OrtSession.Result result = session.run(Map.of(
                     "nodes", nodes, "edges", edges, "edge_indices", indices,
                     "node_mask", nodeMask, "edge_mask", edgeMask))) {
            float[][] accelerations = (float[][]) result.get("acceleration")
                    .orElseThrow().getValue();
            if (accelerations.length != batch.size()) {
                throw new IllegalStateException("speed ONNX returned wrong batch size");
            }
            ActionDict.Builder actions = ActionDict.newBuilder();
            for (int i = 0; i < accelerations.length; i++) {
                if (accelerations[i].length != 1 || !Float.isFinite(accelerations[i][0])) {
                    throw new IllegalStateException("speed ONNX returned invalid acceleration");
                }
                actions.addActions(Action.newBuilder()
                        .setAgentId(request.getObservations(i).getAgentId())
                        .addAction(accelerations[i][0]));
            }
            return actions.build();
        } catch (OrtException exception) {
            throw new IllegalStateException("speed ONNX inference failed", exception);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException exception) {
            throw new IllegalStateException("cannot close speed ONNX session", exception);
        }
    }
}

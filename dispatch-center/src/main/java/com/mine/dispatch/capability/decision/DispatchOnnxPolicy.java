package com.mine.dispatch.capability.decision;

import ai.onnxruntime.*;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class DispatchOnnxPolicy implements AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession session;

    public DispatchOnnxPolicy(String modelPath) {
        try {
            Path path = Path.of(modelPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("dispatch ONNX model does not exist: " + path);
            }
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = environment.createSession(path.toString(), options);
            }
            requireNames(session.getInputNames(), "observation");
            requireNames(session.getOutputNames(), "logits", "value");
        } catch (OrtException exception) {
            throw new IllegalStateException("cannot load dispatch ONNX model", exception);
        }
    }

    public Prediction predict(java.util.List<Float> values) {
        if (values.size() != 27) {
            throw new IllegalArgumentException("dispatch observation must contain 27 values");
        }
        float[] observation = new float[27];
        for (int i = 0; i < observation.length; i++) {
            observation[i] = values.get(i);
            if (!Float.isFinite(observation[i])) {
                throw new IllegalArgumentException("dispatch observation is non-finite");
            }
            if (observation[i] < -1.000001f || observation[i] > 1.000001f) {
                throw new IllegalArgumentException("dispatch observation outside [-1,1]");
            }
        }
        long started = System.nanoTime();
        try (OnnxTensor input = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(observation), new long[]{1, 27});
             OrtSession.Result result = session.run(Map.of("observation", input))) {
            float[] logits = ((float[][]) result.get("logits").orElseThrow().getValue())[0];
            float value = ((float[][]) result.get("value").orElseThrow().getValue())[0][0];
            if (logits.length != 4 || !Float.isFinite(value)) {
                throw new IllegalStateException("invalid dispatch ONNX output shape/value");
            }
            int action = 0;
            for (int i = 0; i < logits.length; i++) {
                if (!Float.isFinite(logits[i])) {
                    throw new IllegalStateException("dispatch ONNX returned non-finite logits");
                }
                if (logits[i] > logits[action]) action = i;
            }
            return new Prediction(action, value, Arrays.copyOf(logits, logits.length),
                    Math.max(0L, (System.nanoTime() - started) / 1_000L));
        } catch (OrtException exception) {
            throw new IllegalStateException("dispatch ONNX inference failed", exception);
        }
    }

    private static void requireNames(java.util.Set<String> actual, String... required) {
        for (String name : required) {
            if (!actual.contains(name)) throw new IllegalStateException("ONNX tensor missing: " + name);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException exception) {
            throw new IllegalStateException("cannot close dispatch ONNX session", exception);
        }
    }

    public record Prediction(int action, float value, float[] logits, long latencyUs) {}
}

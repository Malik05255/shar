package com.malik.alshurti.neural;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Minimal Android port of the MIT-licensed Supertonic 3 Java reference inference path.
 *
 * Upstream reference:
 * https://github.com/supertone-inc/supertonic/tree/main/java
 *
 * The desktop-only javax.sound.sampled code is intentionally omitted. Android playback
 * is handled by NeuralArabicVoice with AudioTrack. The model weights themselves are
 * downloaded separately and remain subject to the upstream model license.
 */
public final class SupertonicCore implements AutoCloseable {
    public static final class Result {
        public final float[] audio;
        public final int sampleRate;

        Result(float[] audio, int sampleRate) {
            this.audio = audio;
            this.sampleRate = sampleRate;
        }
    }

    private static final class Config {
        int sampleRate;
        int baseChunkSize;
        int chunkCompressFactor;
        int latentDim;
    }

    private static final class Style implements AutoCloseable {
        final OnnxTensor ttl;
        final OnnxTensor dp;

        Style(OnnxTensor ttl, OnnxTensor dp) {
            this.ttl = ttl;
            this.dp = dp;
        }

        @Override
        public void close() {
            closeQuietly(ttl);
            closeQuietly(dp);
        }
    }

    private static final class TextInput {
        final long[][] ids;
        final float[][][] mask;

        TextInput(long[][] ids, float[][][] mask) {
            this.ids = ids;
            this.mask = mask;
        }
    }

    private static final class LatentInput {
        final float[][][] noisy;
        final float[][][] mask;

        LatentInput(float[][][] noisy, float[][][] mask) {
            this.noisy = noisy;
            this.mask = mask;
        }
    }

    private final OrtEnvironment environment;
    private final OrtSession durationPredictor;
    private final OrtSession textEncoder;
    private final OrtSession vectorEstimator;
    private final OrtSession vocoder;
    private final Config config;
    private final long[] unicodeIndexer;
    private final Style style;
    private final Random random = new Random();

    private SupertonicCore(
        OrtEnvironment environment,
        OrtSession durationPredictor,
        OrtSession textEncoder,
        OrtSession vectorEstimator,
        OrtSession vocoder,
        Config config,
        long[] unicodeIndexer,
        Style style
    ) {
        this.environment = environment;
        this.durationPredictor = durationPredictor;
        this.textEncoder = textEncoder;
        this.vectorEstimator = vectorEstimator;
        this.vocoder = vocoder;
        this.config = config;
        this.unicodeIndexer = unicodeIndexer;
        this.style = style;
    }

    public static SupertonicCore load(File modelDir, File styleFile) throws Exception {
        File durationFile = required(modelDir, "duration_predictor.onnx");
        File textEncoderFile = required(modelDir, "text_encoder.onnx");
        File vectorFile = required(modelDir, "vector_estimator.onnx");
        File vocoderFile = required(modelDir, "vocoder.onnx");
        File configFile = required(modelDir, "tts.json");
        File indexerFile = required(modelDir, "unicode_indexer.json");
        if (!styleFile.isFile()) {
            throw new IOException("Missing Supertonic voice style: " + styleFile.getAbsolutePath());
        }

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));

        OrtSession dp = null;
        OrtSession text = null;
        OrtSession vector = null;
        OrtSession vocoder = null;
        Style style = null;
        try {
            Config cfg = readConfig(configFile);
            long[] indexer = readIndexer(indexerFile);
            dp = env.createSession(durationFile.getAbsolutePath(), options);
            text = env.createSession(textEncoderFile.getAbsolutePath(), options);
            vector = env.createSession(vectorFile.getAbsolutePath(), options);
            vocoder = env.createSession(vocoderFile.getAbsolutePath(), options);
            style = readStyle(styleFile, env);
            options.close();
            return new SupertonicCore(env, dp, text, vector, vocoder, cfg, indexer, style);
        } catch (Throwable error) {
            closeQuietly(style);
            closeQuietly(dp);
            closeQuietly(text);
            closeQuietly(vector);
            closeQuietly(vocoder);
            closeQuietly(options);
            throw error;
        }
    }

    public synchronized Result synthesize(String rawText, int totalSteps, float speed) throws Exception {
        String text = sanitizeForSpeech(rawText);
        if (text.isEmpty()) {
            return new Result(new float[0], config.sampleRate);
        }
        // The app intentionally keeps police replies short. Hard-capping protects memory
        // and time-to-first-audio on mid-range phones.
        if (text.length() > 280) {
            text = text.substring(0, 280);
        }

        TextInput processed = processText(text, "ar");
        OnnxTensor idsTensor = null;
        OnnxTensor textMaskTensor = null;
        OnnxTensor totalStepsTensor = null;
        OrtSession.Result durationResult = null;
        OrtSession.Result textResult = null;
        try {
            idsTensor = createLongTensor(processed.ids);
            textMaskTensor = createFloatTensor(processed.mask);

            Map<String, OnnxTensor> durationInputs = new HashMap<>();
            durationInputs.put("text_ids", idsTensor);
            durationInputs.put("style_dp", style.dp);
            durationInputs.put("text_mask", textMaskTensor);
            durationResult = durationPredictor.run(durationInputs);
            float[] duration = firstFloatVector(durationResult.get(0));
            for (int i = 0; i < duration.length; i++) {
                duration[i] /= Math.max(0.65f, speed);
            }

            Map<String, OnnxTensor> encoderInputs = new HashMap<>();
            encoderInputs.put("text_ids", idsTensor);
            encoderInputs.put("style_ttl", style.ttl);
            encoderInputs.put("text_mask", textMaskTensor);
            textResult = textEncoder.run(encoderInputs);
            OnnxTensor textEmbedding = (OnnxTensor) textResult.get(0);

            LatentInput latent = sampleNoisyLatent(duration);
            float[][][] current = latent.noisy;
            float[] stepCount = new float[]{(float) Math.max(2, totalSteps)};
            totalStepsTensor = OnnxTensor.createTensor(environment, stepCount);

            for (int step = 0; step < Math.max(2, totalSteps); step++) {
                OnnxTensor currentStepTensor = null;
                OnnxTensor noisyTensor = null;
                OnnxTensor latentMaskTensor = null;
                OnnxTensor loopTextMaskTensor = null;
                OrtSession.Result vectorResult = null;
                try {
                    currentStepTensor = OnnxTensor.createTensor(environment, new float[]{(float) step});
                    noisyTensor = createFloatTensor(current);
                    latentMaskTensor = createFloatTensor(latent.mask);
                    loopTextMaskTensor = createFloatTensor(processed.mask);

                    Map<String, OnnxTensor> vectorInputs = new HashMap<>();
                    vectorInputs.put("noisy_latent", noisyTensor);
                    vectorInputs.put("text_emb", textEmbedding);
                    vectorInputs.put("style_ttl", style.ttl);
                    vectorInputs.put("latent_mask", latentMaskTensor);
                    vectorInputs.put("text_mask", loopTextMaskTensor);
                    vectorInputs.put("current_step", currentStepTensor);
                    vectorInputs.put("total_step", totalStepsTensor);
                    vectorResult = vectorEstimator.run(vectorInputs);
                    current = (float[][][]) vectorResult.get(0).getValue();
                } finally {
                    closeQuietly(vectorResult);
                    closeQuietly(currentStepTensor);
                    closeQuietly(noisyTensor);
                    closeQuietly(latentMaskTensor);
                    closeQuietly(loopTextMaskTensor);
                }
            }

            OnnxTensor finalLatent = null;
            OrtSession.Result vocoderResult = null;
            try {
                finalLatent = createFloatTensor(current);
                Map<String, OnnxTensor> vocoderInputs = new HashMap<>();
                vocoderInputs.put("latent", finalLatent);
                vocoderResult = vocoder.run(vocoderInputs);
                float[][] batch = (float[][]) vocoderResult.get(0).getValue();
                int wantedSamples = Math.max(1, (int) (duration[0] * config.sampleRate));
                float[] source = batch[0];
                int count = Math.min(wantedSamples, source.length);
                float[] audio = new float[count];
                System.arraycopy(source, 0, audio, 0, count);
                return new Result(audio, config.sampleRate);
            } finally {
                closeQuietly(vocoderResult);
                closeQuietly(finalLatent);
            }
        } finally {
            closeQuietly(textResult);
            closeQuietly(durationResult);
            closeQuietly(totalStepsTensor);
            closeQuietly(textMaskTensor);
            closeQuietly(idsTensor);
        }
    }

    private TextInput processText(String text, String language) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace('–', '-')
            .replace('—', '-')
            .replace('_', ' ')
            .replace('[', ' ')
            .replace(']', ' ')
            .replace('|', ' ')
            .replace('/', ' ')
            .replace('#', ' ')
            .replaceAll("\\s+", " ")
            .trim();
        if (!normalized.matches(".*[.!?؟؛…]$")) {
            normalized += ".";
        }
        String tagged = "<" + language + ">" + normalized + "</" + language + ">";
        int[] codePoints = tagged.codePoints().toArray();
        long[][] ids = new long[1][codePoints.length];
        for (int i = 0; i < codePoints.length; i++) {
            int codePoint = codePoints[i];
            if (codePoint < 0 || codePoint >= unicodeIndexer.length) {
                throw new IllegalArgumentException("Unsupported code point: " + codePoint);
            }
            ids[0][i] = unicodeIndexer[codePoint];
        }
        float[][][] mask = new float[1][1][codePoints.length];
        for (int i = 0; i < codePoints.length; i++) {
            mask[0][0][i] = 1.0f;
        }
        return new TextInput(ids, mask);
    }

    private LatentInput sampleNoisyLatent(float[] duration) {
        float maxDuration = 0f;
        for (float value : duration) {
            maxDuration = Math.max(maxDuration, value);
        }
        long maxWaveLength = Math.max(1L, (long) (maxDuration * config.sampleRate));
        long[] waveLengths = new long[duration.length];
        for (int i = 0; i < duration.length; i++) {
            waveLengths[i] = Math.max(1L, (long) (duration[i] * config.sampleRate));
        }

        int chunkSize = config.baseChunkSize * config.chunkCompressFactor;
        int latentLength = (int) ((maxWaveLength + chunkSize - 1L) / chunkSize);
        int latentDimension = config.latentDim * config.chunkCompressFactor;
        float[][][] noisy = new float[duration.length][latentDimension][latentLength];
        float[][][] mask = latentMask(waveLengths, latentLength);

        for (int b = 0; b < noisy.length; b++) {
            for (int d = 0; d < latentDimension; d++) {
                for (int t = 0; t < latentLength; t++) {
                    double u1 = Math.max(1e-10, random.nextDouble());
                    double u2 = random.nextDouble();
                    float gaussian = (float) (Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2));
                    noisy[b][d][t] = gaussian * mask[b][0][t];
                }
            }
        }
        return new LatentInput(noisy, mask);
    }

    private float[][][] latentMask(long[] waveLengths, int maxLatentLength) {
        int latentSize = config.baseChunkSize * config.chunkCompressFactor;
        float[][][] mask = new float[waveLengths.length][1][maxLatentLength];
        for (int b = 0; b < waveLengths.length; b++) {
            long length = (waveLengths[b] + latentSize - 1L) / latentSize;
            for (int t = 0; t < maxLatentLength; t++) {
                mask[b][0][t] = t < length ? 1f : 0f;
            }
        }
        return mask;
    }

    private OnnxTensor createFloatTensor(float[][][] array) throws OrtException {
        int d0 = array.length;
        int d1 = array[0].length;
        int d2 = array[0][0].length;
        float[] flat = new float[d0 * d1 * d2];
        int index = 0;
        for (float[][] plane : array) {
            for (float[] row : plane) {
                for (float value : row) {
                    flat[index++] = value;
                }
            }
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), new long[]{d0, d1, d2});
    }

    private OnnxTensor createLongTensor(long[][] array) throws OrtException {
        int d0 = array.length;
        int d1 = array[0].length;
        long[] flat = new long[d0 * d1];
        int index = 0;
        for (long[] row : array) {
            for (long value : row) {
                flat[index++] = value;
            }
        }
        return OnnxTensor.createTensor(environment, LongBuffer.wrap(flat), new long[]{d0, d1});
    }

    private static float[] firstFloatVector(OnnxValue value) throws OrtException {
        Object raw = value.getValue();
        if (raw instanceof float[][]) {
            return ((float[][]) raw)[0];
        }
        if (raw instanceof float[]) {
            return (float[]) raw;
        }
        throw new OrtException("Unexpected duration predictor output: " + raw.getClass().getName());
    }

    private static Config readConfig(File file) throws Exception {
        JSONObject root = new JSONObject(readText(file));
        JSONObject ae = root.getJSONObject("ae");
        JSONObject ttl = root.getJSONObject("ttl");
        Config config = new Config();
        config.sampleRate = ae.getInt("sample_rate");
        config.baseChunkSize = ae.getInt("base_chunk_size");
        config.chunkCompressFactor = ttl.getInt("chunk_compress_factor");
        config.latentDim = ttl.getInt("latent_dim");
        return config;
    }

    private static long[] readIndexer(File file) throws Exception {
        JSONArray values = new JSONArray(readText(file));
        long[] result = new long[values.length()];
        for (int i = 0; i < values.length(); i++) {
            result[i] = values.getLong(i);
        }
        return result;
    }

    private static Style readStyle(File file, OrtEnvironment environment) throws Exception {
        JSONObject root = new JSONObject(readText(file));
        OnnxTensor ttl = styleTensor(root.getJSONObject("style_ttl"), environment);
        OnnxTensor dp = null;
        try {
            dp = styleTensor(root.getJSONObject("style_dp"), environment);
            return new Style(ttl, dp);
        } catch (Throwable error) {
            closeQuietly(ttl);
            closeQuietly(dp);
            throw error;
        }
    }

    private static OnnxTensor styleTensor(JSONObject object, OrtEnvironment environment) throws Exception {
        JSONArray dimsJson = object.getJSONArray("dims");
        if (dimsJson.length() != 3) {
            throw new IOException("Unexpected voice style rank");
        }
        long[] shape = new long[]{dimsJson.getLong(0), dimsJson.getLong(1), dimsJson.getLong(2)};
        int total = Math.toIntExact(shape[0] * shape[1] * shape[2]);
        float[] flat = new float[total];
        JSONArray batch = object.getJSONArray("data");
        int index = 0;
        for (int i = 0; i < batch.length(); i++) {
            JSONArray plane = batch.getJSONArray(i);
            for (int j = 0; j < plane.length(); j++) {
                JSONArray row = plane.getJSONArray(j);
                for (int k = 0; k < row.length(); k++) {
                    flat[index++] = (float) row.getDouble(k);
                }
            }
        }
        if (index != total) {
            throw new IOException("Voice style tensor size mismatch");
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), shape);
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File required(File directory, String name) throws IOException {
        File file = new File(directory, name);
        if (!file.isFile() || file.length() == 0L) {
            throw new IOException("Missing Supertonic model file: " + name);
        }
        return file;
    }

    private static String sanitizeForSpeech(String text) {
        if (text == null) return "";
        String cleaned = text
            .replace("*", "")
            .replace("#", "")
            .replace("_", " ")
            .replaceAll("https?://\\S+", "")
            .replaceAll("\\s+", " ")
            .trim();
        return cleaned;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort native cleanup.
        }
    }

    @Override
    public void close() {
        closeQuietly(style);
        closeQuietly(durationPredictor);
        closeQuietly(textEncoder);
        closeQuietly(vectorEstimator);
        closeQuietly(vocoder);
    }
}

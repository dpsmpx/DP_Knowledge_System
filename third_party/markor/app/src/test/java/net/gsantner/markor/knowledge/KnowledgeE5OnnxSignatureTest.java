/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class KnowledgeE5OnnxSignatureTest {
    private static KnowledgeE5OnnxSignature.Tensor t(
            final KnowledgeE5OnnxSignature.DataType type, final long... shape) {
        return new KnowledgeE5OnnxSignature.Tensor(type, shape);
    }

    private static Map<String, KnowledgeE5OnnxSignature.Tensor> inputs() {
        final Map<String, KnowledgeE5OnnxSignature.Tensor> values = new LinkedHashMap<>();
        values.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, -1, -1));
        values.put("attention_mask", t(KnowledgeE5OnnxSignature.DataType.INT64, -1, -1));
        return values;
    }

    private static Map<String, KnowledgeE5OnnxSignature.Tensor> outputs() {
        final Map<String, KnowledgeE5OnnxSignature.Tensor> values = new LinkedHashMap<>();
        values.put("last_hidden_state", t(KnowledgeE5OnnxSignature.DataType.FLOAT32, -1, -1, 384));
        return values;
    }

    private static void reject(final Map<String, KnowledgeE5OnnxSignature.Tensor> inputs,
                               final Map<String, KnowledgeE5OnnxSignature.Tensor> outputs) {
        try {
            KnowledgeE5OnnxSignature.require(inputs, outputs, 384);
            fail("Invalid ONNX graph binding was accepted");
        } catch (IllegalArgumentException expected) {
            // Graph I/O must be validated before model inference.
        }
    }

    @Test public void twoOrThreeInputsAndOptionalPoolerAreCompatible() {
        KnowledgeE5OnnxSignature.require(inputs(), outputs(), 384);
        final Map<String, KnowledgeE5OnnxSignature.Tensor> three = inputs();
        three.put("token_type_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 512));
        final Map<String, KnowledgeE5OnnxSignature.Tensor> twoOutputs = outputs();
        twoOutputs.put("pooler_output", t(KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 384));
        KnowledgeE5OnnxSignature.require(three, twoOutputs, 384);
        assertEquals(2, inputs().size());
    }

    @Test public void invalidNamesTypesRanksAndShapesAreRejected() {
        final Map<String, KnowledgeE5OnnxSignature.Tensor> missing = inputs();
        missing.remove("attention_mask");
        reject(missing, outputs());
        final Map<String, KnowledgeE5OnnxSignature.Tensor> unknown = inputs();
        unknown.put("untrusted_input", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 512));
        reject(unknown, outputs());
        final Map<String, KnowledgeE5OnnxSignature.Tensor> wrongType = inputs();
        wrongType.put("attention_mask", t(KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 512));
        reject(wrongType, outputs());
        final Map<String, KnowledgeE5OnnxSignature.Tensor> wrongRank = inputs();
        wrongRank.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 512));
        reject(wrongRank, outputs());
        final Map<String, KnowledgeE5OnnxSignature.Tensor> wide = inputs();
        wide.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 513));
        reject(wide, outputs());
        final Map<String, KnowledgeE5OnnxSignature.Tensor> wrongBatch = inputs();
        wrongBatch.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 2, 512));
        reject(wrongBatch, outputs());
        final Map<String, KnowledgeE5OnnxSignature.Tensor> disagree = inputs();
        disagree.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 128));
        disagree.put("attention_mask", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 256));
        reject(disagree, outputs());
        final Map<String, KnowledgeE5OnnxSignature.Tensor> outputMismatch = outputs();
        outputMismatch.put("last_hidden_state", t(KnowledgeE5OnnxSignature.DataType.FLOAT32,
                1, 512, 768));
        reject(inputs(), outputMismatch);
        final Map<String, KnowledgeE5OnnxSignature.Tensor> wrongOutputName = outputs();
        wrongOutputName.put("extra", t(KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 384));
        reject(inputs(), wrongOutputName);
        final Map<String, KnowledgeE5OnnxSignature.Tensor> pooledOnly = outputs();
        pooledOnly.remove("last_hidden_state");
        pooledOnly.put("pooler_output", t(KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 384));
        reject(inputs(), pooledOnly);
        final Map<String, KnowledgeE5OnnxSignature.Tensor> mismatchedOutputSeq = outputs();
        mismatchedOutputSeq.put("last_hidden_state",
                t(KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 256, 384));
        final Map<String, KnowledgeE5OnnxSignature.Tensor> fixed = inputs();
        fixed.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 512));
        fixed.put("attention_mask", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 512));
        reject(fixed, mismatchedOutputSeq);
        reject(null, outputs());
        reject(inputs(), null);
    }

    @Test public void tensorDescriptorsCopyCallerDimensions() {
        final long[] shape = {1, 512};
        final KnowledgeE5OnnxSignature.Tensor copy =
                t(KnowledgeE5OnnxSignature.DataType.INT64, shape);
        shape[0] = 2;
        final Map<String, KnowledgeE5OnnxSignature.Tensor> input = inputs();
        input.put("input_ids", copy);
        input.put("attention_mask", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 512));
        KnowledgeE5OnnxSignature.require(input, outputs(), 384);
        try {
            new KnowledgeE5OnnxSignature.Tensor(null, 1, 512);
            fail("Missing tensor type accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void strictSmallSignatureRequiresVerifiedConfigAnd384Channels()
            throws Exception {
        final KnowledgeE5ConfigProfile.Result verified = KnowledgeE5ConfigProfile.parse(
                KnowledgeE5ConfigProfileTest.model(),
                KnowledgeE5ConfigProfileTest.tokenizer());
        KnowledgeE5OnnxSignature.requireE5Small(inputs(), outputs(), verified);
        try {
            KnowledgeE5OnnxSignature.requireE5Small(inputs(), outputs(), null);
            fail("Unverified E5 config accepted");
        } catch (IllegalArgumentException expected) { }
        final Map<String, KnowledgeE5OnnxSignature.Tensor> wrong = outputs();
        wrong.put("last_hidden_state", t(KnowledgeE5OnnxSignature.DataType.FLOAT32,
                1, 512, 768));
        try {
            KnowledgeE5OnnxSignature.requireE5Small(inputs(), wrong, verified);
            fail("ONNX output channels differed from canonical E5 config");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void nativeAndOfflineDescriptorsMustMatchExactNamesTypesAndAxes() {
        final Map<String, KnowledgeE5OnnxSignature.Tensor> first = inputs();
        final Map<String, KnowledgeE5OnnxSignature.Tensor> same = inputs();
        assertTrue(KnowledgeE5OnnxSignature.sameDescriptors(first, same));
        same.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 512));
        assertFalse(KnowledgeE5OnnxSignature.sameDescriptors(first, same));
        same.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.FLOAT32, -1, -1));
        assertFalse(KnowledgeE5OnnxSignature.sameDescriptors(first, same));
        same.remove("attention_mask");
        same.put("masked_attention", t(KnowledgeE5OnnxSignature.DataType.INT64, -1, -1));
        assertFalse(KnowledgeE5OnnxSignature.sameDescriptors(first, same));
        assertFalse(KnowledgeE5OnnxSignature.sameDescriptors(null, same));
    }

    @Test public void exactE5FingerprintIsOrderedByNamesAndChangesForEveryAxisOrVariant() {
        final String baseline = KnowledgeE5OnnxSignature.descriptorSha256(inputs(), outputs());
        assertTrue(baseline.matches("[0-9a-f]{64}"));
        final Map<String, KnowledgeE5OnnxSignature.Tensor> reordered = new LinkedHashMap<>();
        reordered.put("attention_mask", inputs().get("attention_mask"));
        reordered.put("input_ids", inputs().get("input_ids"));
        assertEquals(baseline,
                KnowledgeE5OnnxSignature.descriptorSha256(reordered, outputs()));

        final Map<String, KnowledgeE5OnnxSignature.Tensor> fixed = inputs();
        fixed.put("input_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        fixed.put("attention_mask", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        final Map<String, KnowledgeE5OnnxSignature.Tensor> fixedOutput = outputs();
        fixedOutput.put("last_hidden_state",
                t(KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 6, 384));
        final String fixedHash = KnowledgeE5OnnxSignature.descriptorSha256(fixed, fixedOutput);
        assertNotEquals("Matching tensor counts must not mask different axes", baseline, fixedHash);

        fixed.put("token_type_ids", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        assertNotEquals(fixedHash,
                KnowledgeE5OnnxSignature.descriptorSha256(fixed, fixedOutput));
        fixedOutput.put("pooler_output",
                t(KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 384));
        assertNotEquals(fixedHash,
                KnowledgeE5OnnxSignature.descriptorSha256(fixed, fixedOutput));

        final Map<String, KnowledgeE5OnnxSignature.Tensor> extra = inputs();
        extra.put("private_model_name", t(KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        try {
            KnowledgeE5OnnxSignature.descriptorSha256(extra, outputs());
            fail("Unknown names cannot be serialized into IPC fingerprint");
        } catch (IllegalArgumentException expected) { }
    }
}

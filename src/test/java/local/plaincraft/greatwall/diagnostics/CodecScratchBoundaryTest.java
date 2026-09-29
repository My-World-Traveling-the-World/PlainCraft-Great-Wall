package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import org.junit.jupiter.api.Test;

/** Budget counters test admission and ownership, not observed JVM heap usage. */
class CodecScratchBoundaryTest {
    @Test void bufferGrowthDenialKeepsOriginalCapacityAndContents() {
        long initial = 64 + CodecMemory.arrayBytes(8);
        try (var budget = new AllocationBudget(initial + CodecMemory.arrayBytes(16) - 1)) {
            CompiledAllocation.run(budget, () -> {
                var output = new BoundedByteArrayOutputStream(16, 8);
                try {
                    output.write(new byte[8], 0, 8);
                    var error = assertThrows(WallFailure.class, () -> output.write(1));
                    assertEquals(FailureCode.CAPACITY, error.code());
                    assertEquals(8, output.capacity());
                    assertEquals(8, output.size());
                    assertEquals(initial, budget.stats().used());
                    assertEquals(initial, budget.stats().peak());
                    assertArrayEquals(new byte[8], output.takeBytes());
                } finally { output.releaseBuffer(); }
                return 0L;
            }, Long::longValue);
            assertEquals(0, budget.stats().used());
        }
    }

    @Test void copyDenialDoesNotReleaseOrModifyOriginalBuffer() {
        long initial = 64 + CodecMemory.arrayBytes(64);
        try (var budget = new AllocationBudget(initial + CodecMemory.arrayBytes(64) - 1)) {
            CompiledAllocation.run(budget, () -> {
                var output = new BoundedByteArrayOutputStream(64, 64);
                try {
                    output.write(new byte[64], 0, 64);
                    assertThrows(WallFailure.class, output::toByteArray);
                    assertEquals(initial, budget.stats().used());
                    assertEquals(64, output.size());
                    assertEquals(64, output.capacity());
                } finally { output.releaseBuffer(); }
                assertEquals(0, budget.stats().used());
                return 0L;
            }, Long::longValue);
        }
    }

    @Test void exactTakeTransfersOnlyTheArrayWithoutCopyHeadroom() {
        long initial = 64 + CodecMemory.arrayBytes(8192);
        try (var budget = new AllocationBudget(initial)) {
            var result = CompiledAllocation.retainAll(budget, () -> {
                var output = new BoundedByteArrayOutputStream(8192, 8192);
                output.write(new byte[8192], 0, 8192);
                var bytes = output.takeBytes();
                output.releaseBuffer();
                assertThrows(IllegalStateException.class, () -> output.write(1));
                return bytes;
            });
            assertEquals(8192, result.length);
            assertEquals(CodecMemory.arrayBytes(result.length), budget.stats().used());
            assertEquals(initial, budget.stats().peak());
        }
    }

    @Test void byteArrayStreamCloseRemainsCompatibleWithReadingAfterClose() throws Exception {
        try (var memory = CodecMemory.open()) {
            var output = new BoundedByteArrayOutputStream(32, 4);
            try (var data = new DataOutputStream(output)) { data.writeInt(0x12345678); }
            assertArrayEquals(new byte[] {0x12, 0x34, 0x56, 0x78}, output.takeBytes());
        }
    }

    @Test void failedNestedScopeReleasesScratchWithoutReleasingParentCharge() {
        try (var budget = new AllocationBudget(32768)) {
            CompiledAllocation.run(budget, () -> {
                CompiledAllocation.charge(257);
                assertThrows(UncheckedIOException.class, () -> {
                    try (var outer = CodecMemory.open(); var scratch = CodecMemory.scratch(1024);
                            var inner = CodecMemory.open()) {
                        CodecMemory.charge(512);
                        throw new UncheckedIOException(new IOException("fixture failure"));
                    }
                });
                assertEquals(257, budget.stats().used());
                return 0L;
            }, Long::longValue);
            assertEquals(0, budget.stats().used());
        }
    }

    @Test void nestedResultsAndScratchTransferOnlyRetainedBytes() {
        try (var budget = new AllocationBudget(32768)) {
            CompiledAllocation.run(budget, () -> {
                try (var outer = CodecMemory.open()) {
                    try (var inner = CodecMemory.open(); var scratch = CodecMemory.scratch(1024)) {
                        CodecMemory.charge(128);
                        inner.keep("fixture", 128);
                    }
                    assertEquals(4096 + 128, budget.stats().used());
                }
                assertEquals(0, budget.stats().used());
                return 0L;
            }, Long::longValue);
        }
    }

    @Test void modifiedUtfBytesMatchDataOutputIncludingNullAndUnpairedSurrogates() throws Exception {
        for (String value : new String[] {"", "plain", "\u0000\u007f\u0080\u07ff\u0800",
                "\u4e2d\ud83d\ude00", "\ud800x\udc00", "\ud800"}) {
            var expected = new ByteArrayOutputStream();
            new DataOutputStream(expected).writeUTF(value);
            var actual = new ByteArrayOutputStream();
            try (var memory = CodecMemory.open()) {
                CodecMemory.writeModifiedUtf(new DataOutputStream(actual), value);
                assertArrayEquals(expected.toByteArray(), actual.toByteArray());
                assertEquals(value, CodecMemory.readModifiedUtf(new DataInputStream(new ByteArrayInputStream(actual.toByteArray()))));
            }
        }
    }

    @Test void utf8EncoderRetainsLegacyReplacementBehaviorForUnpairedSurrogates() throws Exception {
        for (String value : new String[] {"\ud800", "a\udc00b", "\ud800\ud800\udc00"}) {
            var actual = new ByteArrayOutputStream();
            try (var memory = CodecMemory.open()) {
                CodecMemory.writeUtf8(new DataOutputStream(actual), value, 4096);
            }
            byte[] expected = value.getBytes(StandardCharsets.UTF_8);
            try (var input = new DataInputStream(new ByteArrayInputStream(actual.toByteArray()))) {
                assertEquals(expected.length, input.readInt());
                assertArrayEquals(expected, input.readAllBytes());
            }
        }
    }

    @Test void validUtf8MatchesJdkAndRetainsOnlyDecodedString() throws Exception {
        for (String value : new String[] {"", "ascii", "\u0000\u4e2d\ud83d\ude00"}) {
            var encoded = new ByteArrayOutputStream();
            var output = new DataOutputStream(encoded);
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            output.writeInt(utf8.length); output.write(utf8);
            try (var budget = new AllocationBudget(32768)) {
                var result = CompiledAllocation.retainAll(budget, () -> {
                    try (var memory = CodecMemory.open()) {
                        String decoded = CodecMemory.readUtf8(new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())), 4096);
                        return memory.keep(decoded, CodecMemory.stringBytes(decoded.length()));
                    } catch (IOException error) { throw new UncheckedIOException(error); }
                });
                assertEquals(value, result);
                assertEquals(CodecMemory.stringBytes(value.length()), budget.stats().used());
            }
        }
    }

    @Test void invalidUtf8AndTruncationReleaseAllDecodeScratch() {
        for (byte[] payload : new byte[][] {{0, 0, 0, 2, (byte) 0xc0, (byte) 0x80},
                {0, 0, 0, 3, (byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {0, 0, 0, 4, (byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                {0, 0, 0, 2, (byte) 0xc2}}) {
            try (var budget = new AllocationBudget(32768)) {
                CompiledAllocation.run(budget, () -> {
                    assertThrows(IOException.class, () -> CodecIO.decode(payload, 4096, in -> CodecMemory.readUtf8(in, 4096)),
                            Arrays.toString(payload));
                    assertEquals(0, budget.stats().used());
                    return 0L;
                }, Long::longValue);
            }
        }
    }
}

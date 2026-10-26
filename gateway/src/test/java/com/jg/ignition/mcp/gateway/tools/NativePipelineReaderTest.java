package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inductiveautomation.ignition.common.ImmutableBytes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NativePipelineReaderTest extends InspectionTestSupport {
    static byte[] fixture() throws Exception {
        try (var stream = NativePipelineReaderTest.class.getResourceAsStream("/pipelines/disabled-delays.base64")) {
            return Base64.getMimeDecoder().decode(stream.readAllBytes());
        }
    }

    @Test
    void readsSdkAuthoredCompressedAndUncompressedPipelineWithSharedReferences() throws Exception {
        byte[] compressed = fixture();
        byte[] plain = new GZIPInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
        for (byte[] data : List.of(compressed, plain)) {
            var result = NativePipelineReader.read(new ObjectMapper(), data);
            assertEquals(2, result.path("binaryVersion").asInt());
            var p = result.path("properties");
            assertEquals(false, p.path("enabled").booleanValue());
            assertEquals(2, p.path("blocks").size());
            assertEquals(7000, p.at("/blocks/0/delay").asLong());
            assertEquals(11000, p.at("/blocks/1/delay").asLong());
            assertEquals(p.at("/blocks/0/blockId"), p.at("/startingBlock/outputId"));
            assertEquals(p.at("/blocks/1/blockId"), p.at("/blocks/0/outputId"));
            assertEquals("com.inductiveautomation.delayBlockFactory", p.at("/blocks/1/factoryId").asText());
            assertEquals(800, p.at("/ui-size/width").asInt());
            assertEquals(400, p.at("/blocks/1/ui-location/x").asInt());
        }
    }

    @Test
    void nativeToolReadsFixtureAndRejectsUnauthorizedContentBeforeParsing() throws Exception {
        var resource = resource("com.inductiveautomation.alarm-notification", "alarm-pipelines", "Fixture", "data.bin", "");
        when(resource.getData("data.bin")).thenReturn(Optional.of(ImmutableBytes.copyOf(fixture())));
        project(resource);
        var args = mapper.createObjectNode().put("project", "Plant").put("path", "Fixture").put("resourceType", "alarm-pipelines");
        var handler = new ProjectInspectionToolHandler("ignition.alarms.pipelines.read");
        var result = handler.execute(args, context());
        assertFalse(result.isError(), result.text());
        assertTrue(result.structuredContent().path("complete").asBoolean());
        assertEquals(7000, result.structuredContent().at("/configuration/data.bin/properties/blocks/0/delay").asInt());
        when(config.allowedProjectResourceReadPatterns()).thenReturn(List.of("Plant/ignition/*/*"));
        clearInvocations(resource);
        result = handler.execute(args, context());
        assertTrue(result.isError());
        assertEquals("ALLOWLIST_BLOCKED", result.structuredContent().path("code").asText());
        verify(resource, never()).getData(anyString());
    }

    @Test
    void rejectsTruncationUnknownVersionsAndTrailingBytes() throws Exception {
        byte[] plain = new GZIPInputStream(new ByteArrayInputStream(fixture())).readAllBytes();
        for (int length : List.of(0, 1, 16, 47, 55, plain.length - 1)) {
            rejects(Arrays.copyOf(plain, length));
        }
        byte[] unknownVersion = plain.clone();
        ByteBuffer.wrap(unknownVersion).putInt(16, 99);
        rejects(unknownVersion);
        byte[] oversizedDictionary = plain.clone();
        ByteBuffer.wrap(oversizedDictionary).putInt(48, Integer.MAX_VALUE);
        rejects(oversizedDictionary);
        rejects(Arrays.copyOf(plain, plain.length + 1));
        rejects(Arrays.copyOf(fixture(), fixture().length - 3));
    }

    @Test
    void rejectsCompressedBombsAndOversizedFiles() throws Exception {
        rejects(new byte[InspectionSupport.MAX_FILE_BYTES + 1]);
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(new byte[8_388_609]); }
        rejects(bytes.toByteArray());
    }

    @Test
    void rejectsExcessiveNestingUnknownReferencesAndUnknownCodecs() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeLong(0x98298faa43f74a4fL); out.writeLong(0xb28dca3c4896afc9L);
        out.writeInt(2); out.writeLong(0); out.write(new byte[20]);
        out.writeInt(1); out.writeInt(0); out.writeByte(0); out.writeUTF("objects");
        byte[] header = bytes.toByteArray();
        for (int i = 0; i < 66; i++) { out.writeByte(16); out.writeInt(0); out.writeInt(1); }
        out.writeByte(0); out.writeInt(0);
        rejects(bytes.toByteArray());
        bytes.reset(); out.write(header); out.writeByte(1); out.writeInt(200);
        rejects(bytes.toByteArray());
        bytes.reset(); out.write(header); out.writeByte(8); out.writeInt(0);
        out.writeByte(1); out.writeInt(0); out.writeByte(99);
        rejects(bytes.toByteArray());
    }

    @Test
    void keepsUnknownStoredClassesAsDataWithoutClassResolution() throws Exception {
        byte[] plain = new GZIPInputStream(new ByteArrayInputStream(fixture())).readAllBytes();
        byte[] known = "java.util.HashMap".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] unknown = "no.such.TestType!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(known.length, unknown.length);
        int offset = java.util.HexFormat.of().formatHex(plain).indexOf(java.util.HexFormat.of().formatHex(known)) / 2;
        assertTrue(offset > 0);
        System.arraycopy(unknown, 0, plain, offset, unknown.length);
        var result = NativePipelineReader.read(mapper, plain);
        assertTrue(result.toString().contains("no.such.TestType!"));
    }

    private void rejects(byte[] data) {
        var error = assertThrows(InspectionSupport.InspectionException.class,
            () -> NativePipelineReader.read(mapper, data));
        assertNotNull(error.getMessage());
    }
}

import java.awt.Dimension;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.inductiveautomation.ignition.alarming.common.pipelines.CommonBlockProperties;
import com.inductiveautomation.ignition.alarming.common.pipelines.DelayBlockProperties;
import com.inductiveautomation.ignition.alarming.common.pipelines.PipelineDescriptor;
import com.inductiveautomation.ignition.common.config.BasicProperty;
import com.inductiveautomation.ignition.common.config.BasicPropertySet;
import com.inductiveautomation.ignition.common.xmlserialization.serialization.XMLSerializer;

/** Standalone fixture author; requires the Ignition SDK and installed Alarm Notification common JAR.
 * Run outside the Gateway with an output directory argument. No notification/script blocks. */
public class CreateAlarmPipeline {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) { throw new IllegalArgumentException("Supply one new output directory"); }
        Path output = Path.of(args[0]);
        Files.createDirectory(output);
        var pipeline = new PipelineDescriptor();
        pipeline.set(PipelineDescriptor.ENABLED, false);
        pipeline.set(new BasicProperty<>("ui-size", Dimension.class), new Dimension(800, 600));

        var first = delay("11111111-1111-4111-8111-111111111111", 7000L, new Point(160, 160));
        var second = delay("22222222-2222-4222-8222-222222222222", 11000L, new Point(400, 160));
        first.set(CommonBlockProperties.OUTPUT_ID, second.get(CommonBlockProperties.BLOCK_ID));
        pipeline.addBlock(first);
        pipeline.addBlock(second);
        var start = new BasicPropertySet();
        start.set(CommonBlockProperties.OUTPUT_ID, first.get(CommonBlockProperties.BLOCK_ID));
        pipeline.set(PipelineDescriptor.STARTING_BLOCK, start);

        var serializer = new XMLSerializer().initDefaults();
        serializer.addObject(pipeline);
        Files.write(output.resolve("data.bin"), serializer.serializeBinary(true));
        Files.writeString(output.resolve("fixture.xml"), serializer.serializeXML());
        Files.writeString(output.resolve("resource.json"), """
            {"scope":"G","documentation":"Disabled MCP inspection fixture; two delays only.",
             "version":1,"restricted":false,"overridable":true,"files":["data.bin"],"attributes":{}}
            """);
    }

    private static BasicPropertySet delay(String id, long milliseconds, Point location) {
        var block = new BasicPropertySet();
        block.set(CommonBlockProperties.BLOCK_ID, UUID.fromString(id));
        block.set(CommonBlockProperties.FACTORY_ID, DelayBlockProperties.FACTORY);
        block.set(DelayBlockProperties.DELAY, milliseconds);
        block.set(new BasicProperty<>("ui-location", Point.class), location);
        return block;
    }
}

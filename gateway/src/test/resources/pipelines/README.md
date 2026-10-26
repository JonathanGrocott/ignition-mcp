# Native pipeline fixture

`disabled-delays.base64` contains our own SDK-authored pipeline, not an extracted vendor template. It is gzip-compressed Ignition binary serialization version 2, saved using `PipelineDescriptor` and `XMLSerializer.serializeBinary(true)` with the Ignition 8.3 SDK and Alarm Notification common API. The same format was accepted by a running Ignition 8.3.9 Gateway.

The pipeline is disabled. Its starting block connects to Delay A (7,000 ms), which connects to Delay B (11,000 ms). The block IDs are `11111111-1111-4111-8111-111111111111` and `22222222-2222-4222-8222-222222222222`. It includes Designer positions and an 800 × 600 canvas. It has no notification or script blocks and no contact/profile references.

The standalone author is [CreateAlarmPipeline.java](../../../../../scripts/fixtures/CreateAlarmPipeline.java). To regenerate, provide a Java 17 classpath containing the Ignition common SDK and its runtime dependencies plus the matching installed `alarm-notification-common` JAR, then run:

```bash
java -cp "$IGNITION_FIXTURE_CLASSPATH" scripts/fixtures/CreateAlarmPipeline.java /tmp/disabled-pipeline-fixture
```

Base64-encode the generated `data.bin` to update the test resource. Serialization timestamps may change the bytes. `fixture.xml` is an offline inspection aid and is not part of the imported resource.

For a disposable development Gateway, copy only `resource.json` and `data.bin` into the test project's `com.inductiveautomation.alarm-notification/alarm-pipelines/E2E_DisabledDelays/` resource folder. Reload the project through the Gateway's normal resource lifecycle, then verify that Alarm Pipelines lists it as inactive. Do not import fixtures into a production project.

Read using `ignition.alarms.pipelines.read` with the test project, `path: "E2E_DisabledDelays"`, and `resourceType: "alarm-pipelines"`. Check `configuration["data.bin"].properties` against the values above and require `complete: true`. These assertions qualify stored configuration inspection, not notification delivery.

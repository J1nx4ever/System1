# Field uplink Java messages

This directory builds the `mine.field.v1` messages required by the first-stage
AnyLogic field uplink.

Compatibility choices:

- build environment: conda `DRL`;
- code generator: `protoc 25.3`, matching `protobuf-java 3.25.3`;
- compiler/runtime: the JDK 17.0.9 bundled with AnyLogic 8.9.0;
- bytecode target: Java 17 (class-file major version 61).

Build from the workspace root:

```bash
conda run -n DRL bash field-uplink/build.sh
```

Add both `dist/field-uplink-1.0.0.jar` and the existing
`protobuf-java-3.25.3.jar` to the AnyLogic model dependencies. The generated
JAR deliberately does not bundle the protobuf runtime, so the model has only
one runtime version.

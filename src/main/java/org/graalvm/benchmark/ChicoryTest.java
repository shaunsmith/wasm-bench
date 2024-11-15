package org.graalvm.benchmark;

import com.dylibso.chicory.experimental.aot.AotMachine;
import com.dylibso.chicory.log.SystemLogger;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 3)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class ChicoryTest {
    private static final String INTERPRETER = "interpreter";
    private static final String RUNTIME_AOT = "runtime-aot";
    private static final String PRECOMPILED_AOT = "precompiled-aot";

    @State(Scope.Thread)
    public static class ChicoryFixture extends WasmTestFixture {
        public WasiPreview1 wasi;
        public Instance instance;

        @Param({
                INTERPRETER,
                RUNTIME_AOT,
                PRECOMPILED_AOT
        })
        private String mode;

        @Setup(Level.Trial)
        public void doSetup() throws IOException {
            super.doSetup();
            final var logger = new SystemLogger();
            // create our instance of wasip1
            wasi = new WasiPreview1(logger, WasiOptions.builder().build());
            final var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
            // create the module and instantiate (the module) and connect our imports
            var instanceBuilder = Instance.builder(Parser.parse(wasmBytes));
            switch (mode) {
                case INTERPRETER:
                    break;
                case RUNTIME_AOT:
                    instanceBuilder.withMachineFactory(AotMachine::new);
                    break;
                case PRECOMPILED_AOT:
                    instanceBuilder.withMachineFactory(DemoMachineFactory::create);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown Chicory mode " + mode);
            }
            instance = instanceBuilder.withImportValues(imports).build();
        }

        @TearDown(Level.Trial)
        public void doTeardown() {
            wasi.close();
        }
    }

    @Benchmark
    public void chicoryTest(ChicoryFixture fixture, Blackhole blackhole) throws IOException {
        // automatically exported by TinyGo
        final ExportFunction malloc = fixture.instance.export("malloc");
        final ExportFunction free = fixture.instance.export("free");
        final ExportFunction wasmFunc = fixture.instance.export(fixture.wasmFunctionName);
        final Memory memory = fixture.instance.memory();

        // allocate {fixture.paramLen} bytes of memory, this returns a pointer to that memory
        final int ptr = (int) malloc.apply(fixture.paramLen)[0];
        // We can now write the message to the module's memory:
        memory.writeString(ptr, fixture.param);

        // Call the wasm function
        final int result = (int) wasmFunc.apply(ptr, fixture.paramLen)[0];
        // free input string memory
        free.apply(ptr);

        // Extract position and size from the result
        final int valuePosition = (int) ((result >>> 32) & 0xFFFFFFFFL);
        final int valueSize = (int) (result & 0xFFFFFFFFL);

        // get byte[] of the result
        byte[] message = memory.readBytes(valuePosition, valueSize);

        blackhole.consume(message);
    }
}

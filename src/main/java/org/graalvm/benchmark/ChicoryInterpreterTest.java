package org.graalvm.benchmark;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.dylibso.chicory.runtime.Module;
import com.dylibso.chicory.wasm.Parser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.dylibso.chicory.log.SystemLogger;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasm.types.Value;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.HostImports;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiPreview1;

@Warmup(iterations = 3)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class ChicoryInterpreterTest {
  
    @State(Scope.Thread)
    public static class ChicoryInterpreterFixture extends WasmTestFixture {
        public WasiPreview1 wasi;
        public Instance instance;

        Instance buildInstance(byte[] wasmBytes, HostImports imports) {
            return Module.builder(wasmBytes)
                    .withHostImports(imports)
                    .build()
                    .instantiate();
        }

        @Setup(Level.Trial)
        public void doSetup() throws IOException {
            super.doSetup();
            final var logger = new SystemLogger();
            // create our instance of wasip1
            wasi = new WasiPreview1(logger, WasiOptions.builder().build());
            final var imports = new HostImports(wasi.toHostFunctions());
            // create the module and instantiate (the module) and connect our imports
            instance = buildInstance(this.wasmBytes, imports);
        }

        @TearDown(Level.Trial)
        public void doTeardown() {
            wasi.close();
        }
    }

    @Benchmark
    public void chicoryInterpreterTest(ChicoryInterpreterFixture fixture, Blackhole blackhole) throws IOException {
        // automatically exported by TinyGo
        final ExportFunction malloc = fixture.instance.export("malloc");
        final ExportFunction free = fixture.instance.export("free");
        final ExportFunction wasmFunc = fixture.instance.export(fixture.wasmFunctionName);
        final Memory memory = fixture.instance.memory();

        // allocate {fixture.paramLen} bytes of memory, this returns a pointer to that memory
        final int ptr = malloc.apply(Value.i32(fixture.paramLen))[0].asInt();
        // We can now write the message to the module's memory:
        memory.writeString(ptr, fixture.param);

        // Call the wasm function
        final Value result = wasmFunc.apply(Value.i32(ptr), Value.i32(fixture.paramLen))[0];
        // free input string memory
        free.apply(Value.i32(ptr), Value.i32(fixture.paramLen));

        // Extract position and size from the result
        final int valuePosition = (int) ((result.asLong() >>> 32) & 0xFFFFFFFFL);
        final int valueSize = (int) (result.asLong() & 0xFFFFFFFFL);

        // get byte[] of the result
        byte[] message = memory.readBytes(valuePosition, valueSize);

        blackhole.consume(message);   
    }
}
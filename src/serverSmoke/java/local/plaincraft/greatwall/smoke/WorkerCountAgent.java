package local.plaincraft.greatwall.smoke;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Test-only premain calibration, necessary because QoS loads before Mixin preparation. */
public final class WorkerCountAgent {
    private WorkerCountAgent() {}
    public static void premain(String arguments, Instrumentation instrumentation) {
        int workers = Integer.getInteger("greatwall.s0.terrainConcurrency", 0);
        if (!Boolean.getBoolean("greatwall.s0.probe") || !Boolean.getBoolean("greatwall.s0.terrain")
                || !Boolean.getBoolean("greatwall.s0.terrainFixedWorkers") || !List.of(1, 2, 8, 16).contains(workers)
                || !Files.isRegularFile(Path.of("greatwall_test_world"))) {
            throw new IllegalStateException("Worker calibration is restricted to explicit isolated S0 fixtures");
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                                               ProtectionDomain domain, byte[] input) {
                if (!name.equals("local/plaincraftc2meqos/RuntimeLimiter")) return null;
                var writer = new ClassWriter(0);
                int[] changed = {0};
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String method,
                            String descriptor, String signature, String[] exceptions) {
                        var target = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if ((method.equals("workerPoolSize") && descriptor.equals("(I)I"))
                                || (method.equals("currentLimit") && descriptor.equals("()I"))) {
                            target.visitCode();
                            target.visitIntInsn(Opcodes.BIPUSH, workers);
                            target.visitInsn(Opcodes.IRETURN);
                            target.visitMaxs(1, descriptor.equals("(I)I") ? 1 : 0);
                            target.visitEnd();
                            changed[0]++;
                            return null;
                        }
                        return target;
                    }
                }, 0);
                if (changed[0] != 2) throw new IllegalStateException("Pinned QoS calibration ABI changed");
                byte[] result = writer.toByteArray();
                try { Files.write(Path.of("greatwall-calibrated-runtime-limiter.class"), result); }
                catch (java.io.IOException error) { throw new IllegalStateException("Cannot record calibrated class", error); }
                System.out.println("[GreatWall:S0-03] TEST_ONLY_WORKER_CALIBRATION workers=" + workers + " methods=2");
                return result;
            }
        });
    }
}

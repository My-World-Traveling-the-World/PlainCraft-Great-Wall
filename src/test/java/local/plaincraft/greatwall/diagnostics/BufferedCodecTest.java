package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class BufferedCodecTest {
    @Test void byteLengthShaCrcAndTargetMatchAnIndependentUnbufferedReference() throws Exception {
        for(int length:new int[]{0,1,8191,8192,8193,32769}){
            byte[] payload=new byte[length];new Random(length).nextBytes(payload);
            CodecIO.Writer writer=out->{out.writeInt(length);for(byte b:payload)out.writeByte(b);out.write(payload);out.writeLong(-871);};
            var reference=new ByteArrayOutputStream();writer.write(new DataOutputStream(reference));
            byte[] expected=reference.toByteArray();var target=new CountingOutput();
            try(var budget=new AllocationBudget(1<<20)){
                var summary=CompiledAllocation.run(budget,()->{
                    try{return CodecIO.writeAndSummarize(target,1<<20,writer);}
                    catch(IOException error){throw new AssertionError(error);}
                },ignored->0);
                var crc=new CRC32C();crc.update(expected,0,expected.length);
                assertEquals(expected.length,summary.length());assertEquals(crc.getValue(),summary.crc());
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)),summary.sha256());
                assertArrayEquals(expected,target.toByteArray());assertEquals(0,target.singleWrites);
                assertTrue(target.bulkWrites<20);assertFalse(target.closed);assertEquals(0,budget.stats().used());
            }
            var targetOnly=new CountingOutput();CodecIO.write(targetOnly,1<<20,writer);
            assertArrayEquals(expected,targetOnly.toByteArray());assertFalse(targetOnly.closed);
        }
    }
    @Test void exhaustedBudgetRejectsTheFixedBufferBeforeCallingTheProducer(){
        try(var budget=new AllocationBudget(4096)){
            boolean[] entered={false};
            assertThrows(local.plaincraft.greatwall.model.WallFailure.class,()->CompiledAllocation.run(budget,()->{
                try{return CodecIO.summarize(100,out->{entered[0]=true;out.writeByte(1);});}
                catch(IOException error){throw new AssertionError(error);}
            },ignored->0));
            assertFalse(entered[0]);assertEquals(0,budget.stats().used());
        }
    }
    @Test void countingPassDoesNotAllocateAHarmlessButUnneededHashBuffer() throws Exception {
        try(var budget=new AllocationBudget(4096)){
            int size=CompiledAllocation.run(budget,()->{
                try{return CodecIO.size(100,out->out.writeLong(7));}
                catch(IOException error){throw new AssertionError(error);}
            },ignored->0);
            assertEquals(8,size);assertEquals(4096,budget.stats().peak());assertEquals(0,budget.stats().used());
        }
    }
    private static final class CountingOutput extends ByteArrayOutputStream {
        int singleWrites,bulkWrites;boolean closed;
        @Override public synchronized void write(int value){singleWrites++;super.write(value);}
        @Override public synchronized void write(byte[] data,int offset,int length){bulkWrites++;super.write(data,offset,length);}
        @Override public void close(){closed=true;}
    }
}
